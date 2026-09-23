package com.orangestudio.neonplayer.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * One track's audio, decoded once and kept as interleaved 16-bit samples.
 *
 * The player reads straight out of this array rather than streaming from the decoder, because the
 * timeline can be dragged in either direction at any speed (see [AudioEngine]) and a one-pass
 * decoder cannot be run backwards. Holding the whole track also means seeking is instant and the
 * waveform is available to analyse for the beat map.
 */
class PcmAudio(
    val samples: ShortArray,
    /** Channels as stored in [samples] - the stride between one frame and the next. */
    val channels: Int,
    val sampleRate: Int,
) {
    /** Frames: one sample for every channel. */
    val frames: Int = samples.size / channels

    val durationSeconds: Float get() = frames.toFloat() / sampleRate

    /** How much heap the decoded buffer occupies. */
    val bytes: Long get() = samples.size.toLong() * 2L
}

/** Raised when a file cannot be turned into playable PCM. */
class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a compressed audio file into raw PCM with `MediaCodec`.
 *
 * The file is read through its `content://` URI, so it works with the folders picked in setup
 * without any filesystem permission.
 */
object AudioDecoder {

    /**
     * Ceiling on a fully decoded track.
     *
     * Playback needs the whole song in memory, so a very long file - a DJ set, an audiobook -
     * would otherwise exhaust the heap. Files over the cap are rejected with a readable message
     * instead of crashing the app.
     */
    const val MAX_PCM_BYTES = 120L * 1024L * 1024L

    private const val TIMEOUT_US = 10_000L
    private const val INITIAL_SAMPLES = 1 shl 16

    /** Decodes a whole track. */
    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmAudio = decodeWindow(
        context = context,
        uri = uri,
        startUs = 0L,
        wantedFrames = 0,
        onProgress = onProgress,
        isCancelled = isCancelled,
    )

    /**
     * Decodes at most [wantedFrames] frames starting from [startUs], leaving the decoder as soon as
     * it has enough.
     *
     * This is what makes analysing a whole library affordable: an acoustic fingerprint only needs
     * half a minute from the middle of a track, so the other four minutes are never decoded.
     * A [wantedFrames] of zero means "to the end", which is what playback needs.
     */
    fun decodeWindow(
        context: Context,
        uri: Uri,
        startUs: Long,
        wantedFrames: Int,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PcmAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .stringOrNull(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw DecodeException("The file has no audio track.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.stringOrNull(MediaFormat.KEY_MIME)
                ?: throw DecodeException("The file has no audio track.")
            val durationUs = inputFormat.longOr(MediaFormat.KEY_DURATION, 0L)

            if (startUs > 0L) {
                // The nearest sync frame at or before the requested time; the samples up to
                // startUs are then discarded below, so the window still starts where asked.
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: IOException) {
                throw DecodeException("This device has no decoder for $mime.", error)
            }
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val fullDecode = wantedFrames <= 0
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            var samples = ShortArray(INITIAL_SAMPLES)
            var length = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (isCancelled()) throw DecodeException("Cancelled.")

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw DecodeException("The decoder handed back no input buffer.")

                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder decides the real rate and channel count, which can differ from
                    // what the container claimed.
                    val output = codec.outputFormat
                    channels = output.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    sampleRate = output.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    encoding = output.intOr(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                } else if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    val startOfWindow =
                        info.presentationTimeUs >= startUs || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                    if (buffer != null && info.size > 0 && startOfWindow) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)

                        // Two bytes is the most a single sample can occupy for either encoding,
                        // so this is a safe upper bound on how much room is still needed.
                        val needed = length + info.size / 2 + 1
                        if (fullDecode && needed.toLong() * 2L > MAX_PCM_BYTES) {
                            throw DecodeException("This file is too long to prepare.")
                        }
                        if (needed > samples.size) {
                            samples = samples.copyOf(maxOf(needed, samples.size * 2))
                        }

                        length = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            val count = floats.remaining()
                            for (index in 0 until count) {
                                samples[length + index] = floats.get(index).toPcm16()
                            }
                            length + count
                        } else {
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            shorts.get(samples, length, count)
                            length + count
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (durationUs > 0L && fullDecode) {
                        onProgress(
                            (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                        )
                    }

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    if (!fullDecode && length >= wantedFrames * channels) {
                        outputDone = true
                    }
                }
            }

            if (length == 0) throw DecodeException("The file decoded to no audio.")

            val wanted = if (fullDecode) length else minOf(length, wantedFrames * channels)
            return PcmAudio(
                samples = samples.copyOf(wanted),
                channels = channels.coerceAtLeast(1),
                sampleRate = sampleRate,
            )
        } finally {
            codec?.let { decoder ->
                runCatching { decoder.stop() }
                decoder.release()
            }
            extractor.release()
        }
    }

    /** Clamps a float sample into the signed 16-bit range. */
    private fun Float.toPcm16(): Short = (coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.longOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback
}
