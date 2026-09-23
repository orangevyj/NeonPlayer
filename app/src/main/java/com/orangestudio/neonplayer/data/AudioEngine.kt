package com.orangestudio.neonplayer.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.min

/** A decoded track together with the beat map read off its waveform. */
class LoadedAudio(val pcm: PcmAudio, val beats: BeatMap)

/**
 * Plays decoded PCM at any speed, in either direction.
 *
 * `AudioTrack` cannot do this on its own: `setPlaybackParams` rejects a speed of zero or less, so
 * there is no way to ask the platform to run backwards. Instead the engine renders every output
 * frame itself - it walks a fractional position through the decoded samples, stepping by the
 * playback rate and interpolating between the two samples it lands between. A negative step walks
 * that position backwards, so the same code covers reverse dragging, fast-forwarding and ordinary
 * playback with no special cases.
 *
 * Stepping that way also shifts pitch, which is not so much a shortcoming as the only consistent
 * option: a tape played backwards is lower, and a scrub that reverses has to sound like one.
 */
class AudioEngine {

    /**
     * Frames rendered per write to the output.
     *
     * Deliberately small: the audible lag behind a dragged timeline is about one output buffer, so
     * this is what keeps scrubbing feeling attached to the finger.
     */
    private val chunkFrames = 512

    private val _isPlaying = mutableStateOf(false)
    val isPlaying: State<Boolean> get() = _isPlaying

    private val _positionSeconds = mutableFloatStateOf(0f)
    val positionSeconds: State<Float> get() = _positionSeconds

    private val _loaded = mutableStateOf<LoadedAudio?>(null)
    val loaded: State<LoadedAudio?> get() = _loaded

    /**
     * Called on the pump's thread whenever a track runs off either end, so the queue can move on.
     *
     * A plain callback rather than Compose state: the queue has to keep advancing while the app is
     * in the background, and nothing recomposes there. Set by the owner, cleared on [release].
     */
    var onFinished: (() -> Unit)? = null

    // Written by the audio thread, read by the pump below. Volatile rather than Compose state
    // because these change many times a second and Compose state is not meant to be written from
    // an arbitrary thread.
    @Volatile private var rawPositionSeconds = 0f
    @Volatile private var rawPlaying = false
    @Volatile private var rawFinished = 0

    @Volatile private var loadedValue: LoadedAudio? = null

    /** Bumped on every load, so the audio thread knows to rebuild its output. */
    @Volatile private var generation = 0
    @Volatile private var running = true
    @Volatile private var playRequested = false
    @Volatile private var rate = 1f

    // Volatile makes double access atomic, so the audio thread and the UI thread can share these
    // without a lock.
    @Volatile private var positionFrames = 0.0
    @Volatile private var pendingSeekFrames = -1.0

    private var thread: Thread? = null
    private var pump: Job? = null

    /**
     * Copies the audio thread's position into Compose state, and reports end-of-track.
     *
     * Going through a pump rather than writing Compose state from the audio thread keeps every
     * state write on one thread, while the pump's own rate caps how often the UI is invalidated.
     *
     * The pump is the engine's only clock, so the scope it is given decides whether the engine
     * keeps working in the background: it must outlive the composition that started it. See
     * `MainActivity.playbackScope`.
     */
    fun startPump(scope: CoroutineScope) {
        ensureThread()
        if (pump != null) return

        pump = scope.launch {
            // Held locally so the transition off either end is reported exactly once, whatever the
            // pump's polling rate happens to be.
            var seenFinished = rawFinished
            while (isActive) {
                _positionSeconds.floatValue = rawPositionSeconds
                _isPlaying.value = rawPlaying
                if (seenFinished != rawFinished) {
                    seenFinished = rawFinished
                    onFinished?.invoke()
                }
                delay(PUMP_INTERVAL_MS)
            }
        }
    }

    /** Hands a newly decoded track to the engine, interrupting whatever was playing. */
    fun load(audio: LoadedAudio, autoPlay: Boolean) {
        ensureThread()
        loadedValue = audio
        _loaded.value = audio
        generation++
        rate = 1f
        pendingSeekFrames = 0.0
        playRequested = autoPlay
    }

    fun play() {
        if (loadedValue != null) playRequested = true
    }

    fun pause() {
        playRequested = false
    }

    fun toggle() {
        if (playRequested) pause() else play()
    }

    /**
     * Sets the playback rate. Negative rates play the track backwards.
     *
     * The magnitude is capped because beyond a few times real speed every output sample is reading
     * from somewhere unrelated to the last, which is noise rather than a scrub.
     */
    fun setRate(value: Float) {
        rate = value.coerceIn(-MAX_RATE, MAX_RATE)
    }

    fun seekTo(seconds: Float) {
        val audio = loadedValue ?: return
        val clamped = seconds.coerceIn(0f, audio.pcm.durationSeconds)
        pendingSeekFrames = clamped.toDouble() * audio.pcm.sampleRate
    }

    fun release() {
        running = false
        onFinished = null
        pump?.cancel()
        pump = null
        thread?.interrupt()
        thread = null
    }

    private fun ensureThread() {
        if (thread != null) return
        thread = Thread(::loop, "neonplayer-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var output: AudioTrack? = null
        var builtFor = -1
        var started = false
        var chunk = ShortArray(0)

        while (running) {
            val audio = loadedValue
            if (audio == null) {
                if (idle()) return
                continue
            }

            if (builtFor != generation) {
                output?.let { old ->
                    runCatching { old.stop() }
                    old.release()
                }
                output = buildOutput(audio.pcm)
                builtFor = generation
                started = false
                positionFrames = 0.0
                chunk = ShortArray(chunkFrames * outputChannels(audio.pcm))
            }

            val track = output ?: continue
            val pcm = audio.pcm

            if (!playRequested) {
                if (started) {
                    runCatching { track.pause() }
                    started = false
                }
                rawPlaying = false
                if (idle()) return
                continue
            }

            // Applied here rather than at the call site so a drag cannot be overwritten when the
            // render loop writes its own position back.
            val target = pendingSeekFrames
            if (target >= 0.0) {
                positionFrames = target
                pendingSeekFrames = -1.0
                rawPositionSeconds = (positionFrames / pcm.sampleRate).toFloat()
            }

            val step = rate.toDouble()
            val produced = render(pcm, chunk, chunkFrames, step)

            if (produced <= 0) {
                // Ran off one end or the other.
                runCatching { track.pause() }
                started = false
                playRequested = false
                rawPlaying = false
                val atEnd = step >= 0.0
                positionFrames = if (atEnd) pcm.frames.toDouble() else 0.0
                rawPositionSeconds = if (atEnd) pcm.durationSeconds else 0f
                rawFinished++
                continue
            }

            if (!started) {
                runCatching { track.play() }
                started = true
            }
            rawPlaying = true

            track.write(chunk, 0, produced * outputChannels(pcm))
            rawPositionSeconds = (positionFrames / pcm.sampleRate).toFloat()
        }

        output?.let { old ->
            runCatching { old.stop() }
            old.release()
        }
    }

    /**
     * Renders one buffer, walking a fractional position through the samples.
     *
     * Returns how many frames were produced, which falls short of [framesWanted] only when the
     * playhead ran off the end of the track - the caller reads that as "finished".
     */
    private fun render(pcm: PcmAudio, out: ShortArray, framesWanted: Int, step: Double): Int {
        val source = pcm.samples
        val sourceChannels = pcm.channels
        val outChannels = outputChannels(pcm)
        val lastFrame = pcm.frames - 1
        var position = positionFrames
        var produced = 0

        while (produced < framesWanted) {
            // The playhead runs from frame 0 to the last frame inclusive, so it is only off the
            // end once it has passed the last frame - not when it is sitting on it. Testing
            // `<= 0.0` here would break on the very first frame of every track and make the whole
            // thing look instantly finished.
            if (position < 0.0 || position > lastFrame.toDouble()) break

            val lower = floor(position).toInt().coerceIn(0, lastFrame)
            val upper = if (lower < lastFrame) lower + 1 else lower
            val fraction = (position - lower).toFloat()
            val from = lower * sourceChannels
            val to = upper * sourceChannels
            val at = produced * outChannels

            for (channel in 0 until outChannels) {
                val first = source[from + channel].toFloat()
                val second = source[to + channel].toFloat()
                out[at + channel] = (first + (second - first) * fraction).toInt().toShort()
            }

            produced++
            position += step
        }

        positionFrames = position
        return produced
    }

    /** Sleeps briefly while idle. Returns true once the engine has been shut down. */
    private fun idle(): Boolean = try {
        Thread.sleep(IDLE_SLEEP_MS)
        !running
    } catch (interrupted: InterruptedException) {
        true
    }

    private fun buildOutput(pcm: PcmAudio): AudioTrack {
        val channels = outputChannels(pcm)
        val mask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val minimum = AudioTrack.getMinBufferSize(
            pcm.sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val wanted = chunkFrames * channels * 2 * 2

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(if (minimum > 0) maxOf(minimum, wanted) else wanted)
            .build()
    }

    /**
     * How many channels the track is played in.
     *
     * Surround files are played as their front pair: every sample is read out of a flat array, and
     * mono or stereo is what a phone speaker or a pair of headphones can actually reproduce.
     */
    private fun outputChannels(pcm: PcmAudio): Int = min(pcm.channels, 2).coerceAtLeast(1)

    private companion object {
        /** Beyond this the audio is noise rather than a scrub. */
        const val MAX_RATE = 8f

        const val IDLE_SLEEP_MS = 5L
        const val PUMP_INTERVAL_MS = 16L
    }
}
