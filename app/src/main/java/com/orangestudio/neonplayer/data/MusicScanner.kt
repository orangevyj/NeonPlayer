package com.orangestudio.neonplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orangestudio.neonplayer.R

/**
 * Walks the directory trees the user picked and collects every supported audio file inside them.
 *
 * Traversal goes through the Storage Access Framework, so the app can only ever read the folders
 * that were explicitly granted to it and no broad storage permission is involved.
 */
object MusicScanner {

    /** Extensions treated as music. */
    val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "ogg")

    /** Guards against pathological or looping provider trees. */
    private const val MAX_DEPTH = 32

    /**
     * Scans [roots] and returns everything found, sorted by title.
     *
     * This blocks, so call it from a background dispatcher. Unreadable folders and files whose
     * tags cannot be parsed are skipped or listed without tags rather than failing the whole scan.
     */
    fun scan(context: Context, roots: List<Uri>): List<MusicTrack> {
        val appContext = context.applicationContext
        val found = mutableListOf<MusicTrack>()
        val visitedDirectories = mutableSetOf<String>()

        roots.forEach { root ->
            val directory = DocumentFile.fromTreeUri(appContext, root) ?: return@forEach
            walk(appContext, directory, found, visitedDirectories, depth = 0)
        }

        // Distinct by document URI: overlapping trees must not produce duplicate rows, since the
        // list uses the URI as its stable key.
        return found.distinctBy { it.uri }
            .sortedWith(Comparator { first, second ->
                first.title.compareTo(second.title, ignoreCase = true)
            })
    }

    private fun walk(
        context: Context,
        directory: DocumentFile,
        found: MutableList<MusicTrack>,
        visitedDirectories: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        // A provider may expose the same document through several paths; walk each one once.
        if (!visitedDirectories.add(directory.uri.toString())) return

        val children = try {
            directory.listFiles()
        } catch (ignored: Exception) {
            // The grant may have been revoked, or the storage may have gone away mid-scan.
            return
        }

        children.forEach { child ->
            when {
                child.isDirectory -> walk(
                    context,
                    child,
                    found,
                    visitedDirectories,
                    depth + 1,
                )

                child.isFile && isSupportedAudio(child.name) -> found += readTrack(context, child)
            }
        }
    }

    private fun isSupportedAudio(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            .orEmpty()
        return extension in SUPPORTED_EXTENSIONS
    }

    private fun readTrack(context: Context, file: DocumentFile): MusicTrack {
        val fileName = file.name
            ?: file.uri.lastPathSegment
            ?: context.getString(R.string.untitled)

        var title: String? = null
        var artist: String? = null
        var coverArt: ByteArray? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file.uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            coverArt = CoverArt.thumbnailFrom(retriever.embeddedPicture)
        } catch (ignored: Exception) {
            // Corrupt or unreadable files are still listed, just without tags.
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
                // release() throws IOException on older API levels; nothing to do about it.
            }
        }

        return MusicTrack(
            uri = file.uri,
            fileName = fileName,
            title = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
            artist = artist?.takeIf { it.isNotBlank() },
            coverArt = coverArt,
        )
    }
}
