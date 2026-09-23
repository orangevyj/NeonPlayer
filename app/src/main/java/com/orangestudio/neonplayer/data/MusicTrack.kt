package com.orangestudio.neonplayer.data

import android.net.Uri

/**
 * One audio file found by [MusicScanner].
 *
 * [title] and [artist] are taken from the file's own tags when they exist and fall back to the
 * file name and a null artist when they do not, so nothing is silently dropped from the list.
 * The "Unknown Artist" wording lives in the UI layer so it stays localizable.
 */
data class MusicTrack(
    val uri: Uri,
    val fileName: String,
    val title: String,
    /** Tagged artist, or null when the file carries no artist tag. */
    val artist: String?,
    /** Small JPEG of the embedded cover art, or null when the file has none. */
    val coverArt: ByteArray?,
)
