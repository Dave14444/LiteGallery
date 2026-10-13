package org.iurl.litegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Persistent thumbnail cache stored on the SMB share itself.
 * Thumbnails are JPEG files capped at 256 x 256 pixels.
 */
object SmbThumbnailCache {

    private const val CACHE_DIR = ".LiteGalleryThumbnails"
    private const val THUMB_SIZE = 256

    fun openThumbnailStream(context: Context, imageUrl: String): InputStream {
        val path = SmbPath.parse(imageUrl)
            ?: return RemoteClients.forUrl(imageUrl)!!.openInputStream(context, imageUrl)

        val client = RemoteClients.forUrl(imageUrl)
            ?: throw IllegalArgumentException("No remote client for $imageUrl")

        val fileSize = client.getFileSize(context, imageUrl)
        val cacheKey = sha256("$imageUrl|$fileSize")
        val cachePath = SmbPath.toUrl(
            path.host,
            path.share,
            "$CACHE_DIR/$cacheKey.jpg"
        )

        if (client.fileExists(context, cachePath)) {
            try {
                return client.openInputStream(context, cachePath)
            } catch (_: Exception) {
                // Invalid or unreadable cache entry: regenerate it.
            }
        }

        val thumbnailBytes = createThumbnail(context, imageUrl)
        try {
            SmbClient.writeBytes(
                context,
                path.host,
                path.share,
                "$CACHE_DIR/$cacheKey.jpg",
                thumbnailBytes
            )
        } catch (e: Exception) {
            android.util.Log.w(
                "SmbThumbnailCache",
                "Could not save thumbnail to SMB cache",
                e
            )
        }

        // Return the generated image even if the NAS cache could not be written.
        return ByteArrayInputStream(thumbnailBytes)
    }

    private fun createThumbnail(context: Context, imageUrl: String): ByteArray {
        val client = RemoteClients.forUrl(imageUrl)
            ?: throw IllegalArgumentException("No remote client for $imageUrl")

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        client.openInputStream(context, imageUrl).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val sampleSize = calculateSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            THUMB_SIZE
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val decoded = client.openInputStream(context, imageUrl).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalStateException("Could not decode $imageUrl")

        val thumbnail = centerCrop(decoded, THUMB_SIZE)
        if (thumbnail !== decoded) decoded.recycle()

        return try {
            ByteArrayOutputStream().use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray()
            }
        } finally {
            thumbnail.recycle()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target &&
            height / (sample * 2) >= target
        ) {
            sample *= 2
        }
        return sample
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, left, top, side, side)

        val result = if (cropped.width == size && cropped.height == size) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, size, size, true).also {
                if (cropped !== source) cropped.recycle()
            }
        }
        return result
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
