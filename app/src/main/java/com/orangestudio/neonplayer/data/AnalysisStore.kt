package com.orangestudio.neonplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Remembers the acoustic fingerprint of every track that has been analysed.
 *
 * Analysing a library means decoding part of every file, which is far too slow to repeat on each
 * launch. Fingerprints are kept in the app's private preferences under the file's own URI, so a
 * library is measured once and then reused.
 *
 * Entries are keyed by URI and are not invalidated when a file changes on disk. That is a
 * deliberate trade: checking would mean querying every file's timestamp on every launch, and a
 * stale fingerprint only means adaptive mode picks a slightly different neighbour.
 */
class AnalysisStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun profileFor(uri: Uri): SpectralProfile? =
        preferences.getString(uri.toString(), null)?.let(SpectralProfile::decode)

    fun store(uri: Uri, profile: SpectralProfile) {
        preferences.edit().putString(uri.toString(), profile.encode()).apply()
    }

    /** Drops fingerprints for files that are no longer in the library. */
    fun retainOnly(uris: Collection<Uri>) {
        val live = uris.mapTo(HashSet()) { it.toString() }
        val stale = preferences.all.keys.filterNot { it in live }
        if (stale.isEmpty()) return
        preferences.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val NAME = "neonplayer_analysis"
    }
}

/**
 * Measures a library, one track at a time.
 *
 * Only the middle half-minute of each file is decoded - enough to characterise it, and the
 * difference between analysing a minute against ten.
 */
object LibraryAnalysis {

    /** How much of each track is listened to. */
    private const val WINDOW_SECONDS = 30f

    /** Analysis starts past the intro, where a fade-in would skew the measurements. */
    private const val SKIP_FRACTION = 0.1

    /** The rate every fingerprint is measured at, so profiles from different files compare. */
    private const val ANALYSIS_SAMPLE_RATE = 44100

    /**
     * Analyses one track and caches the result. Returns null when the file cannot be read or
     * decoded, so one bad file cannot stop a library being measured.
     */
    fun analyze(context: Context, uri: Uri, store: AnalysisStore): SpectralProfile? = try {
        val durationUs = durationOf(context, uri)

        val pcm = AudioDecoder.decodeWindow(
            context = context,
            uri = uri,
            startUs = (durationUs * SKIP_FRACTION).toLong(),
            wantedFrames = (WINDOW_SECONDS * ANALYSIS_SAMPLE_RATE).toInt(),
        )

        val profile = AudioAnalysis.profile(pcm)
        store.store(uri, profile)
        profile
    } catch (error: Exception) {
        null
    }

    /** The track's length in microseconds, or zero when the file will not say. */
    private fun durationOf(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.times(1000L)
                ?: 0L
        } catch (error: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
