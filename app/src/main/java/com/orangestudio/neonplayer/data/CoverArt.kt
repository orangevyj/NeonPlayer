package com.orangestudio.neonplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.orangestudio.neonplayer.R
import java.io.ByteArrayOutputStream

/**
 * Cover art helpers.
 *
 * Embedded artwork is normalised down to a small JPEG while scanning, so pointing the app at a
 * large library does not pin megabytes of full-size album art in memory.
 *
 * Files without embedded art fall back to one of the four bundled images (image1..image4),
 * chosen from the file name. The pick is a plain hash of the name rather than a real random
 * number, so a given song keeps the same cover on every launch and across app updates.
 */
object CoverArt {

    /** Longest edge kept for stored thumbnails, in pixels. */
    private const val THUMBNAIL_PX = 256

    private val PLACEHOLDERS = intArrayOf(
        R.drawable.image1,
        R.drawable.image2,
        R.drawable.image3,
        R.drawable.image4,
    )

    private const val JPEG_QUALITY = 85

    /** Stable pick in image1..image4 for [fileName]. */
    fun placeholderFor(fileName: String): Int =
        PLACEHOLDERS[Math.floorMod(fileName.hashCode(), PLACEHOLDERS.size)]

    /**
     * Re-encodes raw embedded artwork as a small JPEG. Returns null when [bytes] is absent or is
     * not decodable as an image.
     */
    fun thumbnailFrom(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMBNAIL_PX)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaled = scaleDown(decoded, THUMBNAIL_PX)

        return try {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** Decodes a thumbnail produced by [thumbnailFrom]. Returns null if decoding fails. */
    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** Largest power-of-two subsampling that still leaves the image at or above [maxPx]. */
    private fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        var longest = maxOf(width, height)
        var sample = 1
        while (longest / 2 >= maxPx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
