package com.orangestudio.neonplayer.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the track hits, sampled evenly across it.
 *
 * The cover art glows off this. It is computed once when a track is decoded and then simply
 * indexed by the playhead, which is what lets the glow stay on the beat while the timeline is
 * dragged backwards or fast - a live audio tap could not do that.
 */
class BeatMap(
    /** How hard the track hits in each bucket, normalised to `0..1`. */
    private val buckets: FloatArray,
    val bucketsPerSecond: Float,
) {
    val isEmpty: Boolean get() = buckets.isEmpty()

    /**
     * How hard the track is hitting at [seconds].
     *
     * Reads backwards over a short window and lets each hit fade, so the glow decays between beats
     * instead of snapping back to nothing the instant a bucket ends.
     */
    fun strengthAt(seconds: Float, decayPerSecond: Float = 4.5f): Float {
        if (buckets.isEmpty() || seconds < 0f) return 0f

        val exact = seconds * bucketsPerSecond
        if (exact >= buckets.size) return 0f

        val newest = exact.toInt().coerceAtMost(buckets.size - 1)
        val lookBack = (LOOK_BACK_SECONDS * bucketsPerSecond).toInt().coerceAtLeast(1)
        val oldest = (newest - lookBack).coerceAtLeast(0)

        var strongest = 0f
        for (index in oldest..newest) {
            val age = (exact - index) / bucketsPerSecond
            val decayed = buckets[index] * exp(-age * decayPerSecond)
            if (decayed > strongest) strongest = decayed
        }
        return strongest.coerceIn(0f, 1f)
    }

    private companion object {
        /** How long a single hit keeps glowing. */
        const val LOOK_BACK_SECONDS = 0.25f
    }
}

/**
 * A compact description of how a track sounds, for finding similar ones.
 *
 * This is the "sound wave examination" behind adaptive mode: every value is read off the spectrum,
 * and the distance between two profiles says how alike two tracks are.
 */
class SpectralProfile(
    /** Detected hits per second - a stand-in for tempo. */
    val onsetsPerSecond: Float,
    /** Centre of gravity of the spectrum: how bright the track sounds. */
    val centroidHz: Float,
    /** Frequency below which most of the energy sits. */
    val rolloffHz: Float,
    val lowRatio: Float,
    val midRatio: Float,
    val highRatio: Float,
    val loudness: Float,
    /** How much the loudness moves about, i.e. how punchy the track is. */
    val dynamics: Float,
) {

    /**
     * How far apart two tracks sound.
     *
     * Each term is scaled to a comparable range before being combined, so no single measurement
     * dominates: without that the frequency terms, which run to thousands of hertz, would drown
     * out the band ratios, which run from zero to one.
     */
    fun distanceTo(other: SpectralProfile): Float {
        val onsets = ln((onsetsPerSecond + 0.2f) / (other.onsetsPerSecond + 0.2f)) * 0.9f
        val centroid = (centroidHz - other.centroidHz) / 3000f
        val rolloff = (rolloffHz - other.rolloffHz) / 6000f
        val bands = hypot(
            lowRatio - other.lowRatio,
            hypot(midRatio - other.midRatio, highRatio - other.highRatio),
        )
        val loudnessTerm = (loudness - other.loudness) * 2f
        val dynamicsTerm = (dynamics - other.dynamics) * 1.5f

        return sqrt(
            onsets * onsets +
                centroid * centroid +
                rolloff * rolloff +
                bands * bands +
                loudnessTerm * loudnessTerm +
                dynamicsTerm * dynamicsTerm
        )
    }

    fun encode(): String = listOf(
        onsetsPerSecond,
        centroidHz,
        rolloffHz,
        lowRatio,
        midRatio,
        highRatio,
        loudness,
        dynamics,
    ).joinToString(FIELD_SEPARATOR)

    companion object {
        private const val FIELD_SEPARATOR = ";"
        private const val FIELD_COUNT = 8

        /** The profile given to audio too short to measure. */
        val Unknown = SpectralProfile(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        fun decode(text: String): SpectralProfile? {
            val parts = text.split(FIELD_SEPARATOR)
            if (parts.size != FIELD_COUNT) return null
            val values = parts.map { it.toFloatOrNull() ?: return null }
            return SpectralProfile(
                onsetsPerSecond = values[0],
                centroidHz = values[1],
                rolloffHz = values[2],
                lowRatio = values[3],
                midRatio = values[4],
                highRatio = values[5],
                loudness = values[6],
                dynamics = values[7],
            )
        }
    }
}

/**
 * Reads the beat map and the acoustic fingerprint off decoded PCM.
 *
 * Needs no permission and no live audio session: everything comes from the samples already in
 * memory for playback.
 */
object AudioAnalysis {

    /** Samples per beat-map bucket - about 23 ms at 44.1 kHz. */
    private const val BUCKET_FRAMES = 1024

    /** Hits are looked for in the bass, which is where a kick drum lives. */
    private const val BASS_CUTOFF_HZ = 180.0

    /** A hit only counts up to the strength that this fraction of them fall below. */
    private const val ONSET_PERCENTILE = 0.95f

    /** How far back the local average reaches when deciding what counts as a hit. */
    private const val LOCAL_WINDOW_SECONDS = 1.5f

    /** Frames used for the spectral fingerprint, and how far apart they start. */
    private const val WINDOW = 1024
    private const val HOP = 512
    private const val LOW_CUTOFF_HZ = 250f
    private const val MID_CUTOFF_HZ = 2000f
    private const val ROLLOFF_FRACTION = 0.85f

    /**
     * Finds where the track hits.
     *
     * The signal is low-passed to the bass, averaged into buckets and then differenced: a bucket
     * carrying more bass energy than those just before it is an onset. Measuring against a running
     * local average is what makes this adaptive - a quiet intro and a loud chorus are judged
     * against their own surroundings rather than against each other.
     */
    fun beatMap(pcm: PcmAudio): BeatMap {
        val bucketsPerSecond = pcm.sampleRate.toFloat() / BUCKET_FRAMES
        if (pcm.frames < BUCKET_FRAMES) return BeatMap(FloatArray(0), bucketsPerSecond)

        val bucketCount = pcm.frames / BUCKET_FRAMES
        val samples = pcm.samples
        val channels = pcm.channels
        val total = bucketCount * BUCKET_FRAMES

        // A one-pole low pass: one multiply and add per sample, which keeps this cheap enough to
        // run over a whole track while the app is getting ready to play it.
        val coefficient = (1.0 - exp(-2.0 * PI * BASS_CUTOFF_HZ / pcm.sampleRate)).toFloat()

        val energy = FloatArray(bucketCount)
        var filtered = 0f
        var accumulator = 0f
        var bucket = 0
        var position = 0

        while (position < total) {
            var mono = 0f
            for (channel in 0 until channels) mono += samples[position * channels + channel]
            mono /= channels

            filtered += coefficient * (mono - filtered)
            accumulator += filtered * filtered

            if ((position + 1) % BUCKET_FRAMES == 0) {
                energy[bucket] = accumulator / BUCKET_FRAMES
                bucket++
                accumulator = 0f
            }
            position++
        }

        val localBuckets = (LOCAL_WINDOW_SECONDS * bucketsPerSecond).toInt().coerceAtLeast(1)
        val novelty = FloatArray(bucketCount)
        var runningTotal = 0f

        for (index in 1 until bucketCount) {
            runningTotal += energy[index - 1]
            val oldest = (index - localBuckets).coerceAtLeast(0)
            if (oldest > 0) runningTotal -= energy[oldest - 1]
            val average = runningTotal / (index - oldest).coerceAtLeast(1)
            novelty[index] = (energy[index] - average).coerceAtLeast(0f)
        }

        // Scaled by a high percentile rather than the maximum, so one clipped peak cannot flatten
        // every other hit into insignificance.
        val ceiling = percentile(novelty, ONSET_PERCENTILE)
        if (ceiling <= 0f) return BeatMap(FloatArray(bucketCount), bucketsPerSecond)

        for (index in 0 until bucketCount) {
            novelty[index] = (novelty[index] / ceiling).coerceIn(0f, 1f)
        }

        return BeatMap(novelty, bucketsPerSecond)
    }

    /**
     * Measures how a track sounds, for adaptive mode.
     *
     * Runs a short-time Fourier transform over the samples and reduces each frame to a handful of
     * numbers - brightness, the balance of bass to treble, how often it hits - then averages those
     * across the window. The result is what "closest to this one" is judged on.
     */
    fun profile(pcm: PcmAudio): SpectralProfile {
        if (pcm.frames < WINDOW) return SpectralProfile.Unknown

        val mono = pcm.monoSamples()
        val frameCount = (mono.size - WINDOW) / HOP + 1
        if (frameCount <= 0) return SpectralProfile.Unknown

        val window = FloatArray(WINDOW) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (WINDOW - 1))).toFloat()
        }
        // The window scales the signal down, so loudness has to be divided back out to stay
        // comparable with the raw samples.
        var windowSquareSum = 0f
        for (value in window) windowSquareSum += value * value
        val windowRms = sqrt(windowSquareSum / WINDOW)

        val real = FloatArray(WINDOW)
        val imaginary = FloatArray(WINDOW)
        val magnitudes = FloatArray(WINDOW / 2)
        val previous = FloatArray(WINDOW / 2)
        val fluxes = FloatArray(frameCount)
        val energies = FloatArray(frameCount)

        val binHz = pcm.sampleRate.toFloat() / WINDOW
        var centroidSum = 0f
        var rolloffSum = 0f
        var lowSum = 0f
        var midSum = 0f
        var highSum = 0f
        var loudnessSquareSum = 0f

        for (frame in 0 until frameCount) {
            val offset = frame * HOP
            var energy = 0f
            for (index in 0 until WINDOW) {
                val value = mono[offset + index] * window[index]
                real[index] = value
                imaginary[index] = 0f
                energy += value * value
            }

            Fft.transform(real, imaginary)

            var magnitudeSum = 0f
            var weighted = 0f
            var flux = 0f
            for (bin in 1 until WINDOW / 2) {
                val magnitude = hypot(real[bin], imaginary[bin])
                magnitudes[bin] = magnitude
                magnitudeSum += magnitude
                weighted += magnitude * bin * binHz

                // Spectral flux: how much this frame has gained over the last one. Partials
                // that are fading out are not counted, which is what makes a note start
                // register as a change.
                if (magnitude > previous[bin]) flux += magnitude - previous[bin]
                previous[bin] = magnitude

                val frequency = bin * binHz
                when {
                    frequency < LOW_CUTOFF_HZ -> lowSum += magnitude
                    frequency < MID_CUTOFF_HZ -> midSum += magnitude
                    else -> highSum += magnitude
                }
            }

            if (magnitudeSum > 0f) {
                centroidSum += weighted / magnitudeSum

                // The frequency below which the chosen fraction of the energy has arrived.
                val threshold = magnitudeSum * ROLLOFF_FRACTION
                var running = 0f
                for (bin in 1 until WINDOW / 2) {
                    running += magnitudes[bin]
                    if (running >= threshold) {
                        rolloffSum += bin * binHz
                        break
                    }
                }
            }

            fluxes[frame] = flux
            energies[frame] = sqrt(energy / WINDOW)
            loudnessSquareSum += energy
        }

        var fluxTotal = 0f
        var fluxSquareTotal = 0f
        for (value in fluxes) {
            fluxTotal += value
            fluxSquareTotal += value * value
        }
        val fluxMean = fluxTotal / frameCount
        val fluxDeviation = sqrt(
            (fluxSquareTotal / frameCount - fluxMean * fluxMean).coerceAtLeast(0f)
        )

        // A frame counts as a hit when its spectral change stands out from the track's own typical
        // amount of change; dividing by the window length turns that into a rate.
        val onsetThreshold = fluxMean + fluxDeviation
        var onsetFrames = 0
        for (value in fluxes) if (value > onsetThreshold) onsetFrames++

        val windowSeconds = frameCount.toFloat() * HOP / pcm.sampleRate
        val bands = lowSum + midSum + highSum

        var energyTotal = 0f
        for (value in energies) energyTotal += value
        val energyMean = energyTotal / frameCount

        var energySquareTotal = 0f
        for (value in energies) energySquareTotal += value * value
        val energyDeviation = sqrt(
            (energySquareTotal / frameCount - energyMean * energyMean).coerceAtLeast(0f)
        )

        // Dividing the window's energy by the window itself is the same as summing the raw samples'
        // energy only approximately, so the window's own RMS is divided back out.
        val loudness = sqrt(loudnessSquareSum / (frameCount * WINDOW)) / windowRms

        return SpectralProfile(
            onsetsPerSecond = if (windowSeconds > 0f) onsetFrames / windowSeconds else 0f,
            centroidHz = centroidSum / frameCount,
            rolloffHz = rolloffSum / frameCount,
            lowRatio = if (bands > 0f) lowSum / bands else 0f,
            midRatio = if (bands > 0f) midSum / bands else 0f,
            highRatio = if (bands > 0f) highSum / bands else 0f,
            loudness = loudness,
            dynamics = if (energyMean > 0f) energyDeviation / energyMean else 0f,
        )
    }

    /** Averages every channel to one, so analysis happens once rather than per channel. */
    private fun PcmAudio.monoSamples(): FloatArray {
        val channels = this.channels
        val result = FloatArray(frames)

        if (channels == 1) {
            for (index in result.indices) result[index] = samples[index] / 32768f
            return result
        }

        for (frame in result.indices) {
            val base = frame * channels
            var sum = 0f
            for (channel in 0 until channels) sum += samples[base + channel]
            result[frame] = sum / channels / 32768f
        }
        return result
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        sorted.sort()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

/**
 * An in-place, iterative radix-2 FFT.
 *
 * Written out rather than pulled in because it is the only signal processing the app needs and it
 * is a few dozen lines.
 */
private object Fft {

    fun transform(real: FloatArray, imaginary: FloatArray) {
        val size = real.size

        // Reorder into bit-reversed positions.
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed or bit

            if (index < reversed) {
                val swapReal = real[index]
                real[index] = real[reversed]
                real[reversed] = swapReal

                val swapImaginary = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = swapImaginary
            }
        }

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()

            var start = 0
            while (start < size) {
                var currentReal = 1f
                var currentImaginary = 0f

                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2

                    val oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary
                    val oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal

                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary

                    val nextReal = currentReal * stepReal - currentImaginary * stepImaginary
                    currentImaginary = currentReal * stepImaginary + currentImaginary * stepReal
                    currentReal = nextReal
                }

                start += length
            }

            length = length shl 1
        }
    }
}

/**
 * Reads the live spectrum off the track, for the visualizer on the collapsed player.
 *
 * This is the same bargain the beat map makes: the whole track is already decoded in memory, so a
 * window can be measured wherever the playhead happens to be - which is what keeps the bars on the
 * music while the timeline is dragged, forwards or backwards, where a live audio tap would either
 * fall silent or drift away from what is being heard.
 *
 * One instance holds its own buffers, so measuring a frame allocates nothing and can be done from
 * inside a draw pass.
 */
class SpectrumAnalyzer(
    /** How many bars the visualizer draws. */
    val bandCount: Int,
    private val window: Int = 1024,
) {

    init {
        require(window > 0 && window and (window - 1) == 0) { "The window must be a power of two." }
    }

    /** The Hann taper, so the ends of the slice do not ring and smear across every band. */
    private val taper = FloatArray(window) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (window - 1))).toFloat()
    }

    private val real = FloatArray(window)
    private val imaginary = FloatArray(window)

    /**
     * Fills [out] with how loud each band is at [atSeconds], each between `0` and `1`.
     *
     * Left flat when the track is shorter than one window - there is nothing to measure yet.
     */
    fun measure(pcm: PcmAudio, atSeconds: Float, out: FloatArray) {
        out.fill(0f)
        if (out.size != bandCount || pcm.frames < window) return

        // Centred on the playhead, then slid back inside the track at either end.
        val start = ((atSeconds * pcm.sampleRate).toInt() - window / 2)
            .coerceIn(0, pcm.frames - window)

        for (index in 0 until window) {
            real[index] = sampleAt(pcm, start + index) * taper[index]
            imaginary[index] = 0f
        }

        Fft.transform(real, imaginary)

        val binHz = pcm.sampleRate.toFloat() / window
        val highestBin = window / 2 - 1

        // Bands grow by a ratio rather than by a fixed width of hertz: an octave is worth the same
        // slice of the bar wherever it sits, which is what stops the bass from owning the display.
        val ratio = exp(ln(MAX_HZ / MIN_HZ) / bandCount)
        var lowHz = MIN_HZ

        for (band in 0 until bandCount) {
            val highHz = lowHz * ratio
            val lowBin = (lowHz / binHz).toInt().coerceIn(1, highestBin)
            val highBin = (highHz / binHz).toInt().coerceIn(lowBin, highestBin)

            var sum = 0f
            for (bin in lowBin..highBin) sum += hypot(real[bin], imaginary[bin])
            val average = sum / (highBin - lowBin + 1) / (window / 4f)

            // A square root, because loudness is heard logarithmically: without it the bars would
            // sit on the floor for everything quieter than the loudest passage in the track.
            out[band] = sqrt((average * GAIN).coerceIn(0f, 1f))

            lowHz = highHz
        }
    }

    /** One mono frame, averaged across the channels the file happens to carry. */
    private fun sampleAt(pcm: PcmAudio, frame: Int): Float {
        val base = frame * pcm.channels
        if (pcm.channels == 1) return pcm.samples[base] / 32768f

        var sum = 0f
        for (channel in 0 until pcm.channels) sum += pcm.samples[base + channel]
        return sum / pcm.channels / 32768f
    }

    private companion object {
        const val MIN_HZ = 55f
        const val MAX_HZ = 12000f

        /** Tuned so a full-scale passage puts the loudest bands near the top of their travel. */
        const val GAIN = 7f
    }
}
