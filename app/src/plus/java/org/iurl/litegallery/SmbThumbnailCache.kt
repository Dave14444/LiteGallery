package org.iurl.litegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Persistent thumbnail cache stored on the SMB share itself.
 * Thumbnails are square JPEG files of [THUMB_SIZE] x [THUMB_SIZE] pixels.
 */
object SmbThumbnailCache {

    private const val CACHE_DIR = ".LiteGalleryThumbnails"
    private const val CACHE_VERSION = "thumb-v5"
    private const val THUMB_SIZE = 384

    /** Position of the video frame used as thumbnail. */
    private const val VIDEO_FRAME_TIME_US = 1_000_000L

    /** Read-ahead block when a video is read over the network. */
    private const val VIDEO_READ_BLOCK_SIZE = 512 * 1024

    /** Images up to this size are downloaded once and decoded from memory. */
    private const val MAX_IN_MEMORY_SOURCE_BYTES = 48L * 1024 * 1024

    private val cacheLock = Any()

    fun openThumbnailStream(context: Context, imageUrl: String): InputStream {
        return synchronized(cacheLock) {
            openThumbnailStreamLocked(context, imageUrl)
        }
    }

    enum class PrepareResult { CREATED, ALREADY_THERE }

    /** File names of the thumbnails already stored on the share of [anyUrlOnShare]. */
    fun listCachedNames(context: Context, anyUrlOnShare: String): Set<String> {
        val smb = SmbPath.parse(anyUrlOnShare) ?: return emptySet()
        return try {
            SmbFileClient.listFiles(context, SmbPath.toUrl(smb.host, smb.share, CACHE_DIR))
                .map { it.name }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Makes sure the thumbnail of [imageUrl] exists on the share. [knownCachedNames] (from
     * [listCachedNames]) saves a request per file. The file must have been listed before, so
     * that its size and date are known.
     */
    fun prepare(context: Context, imageUrl: String, knownCachedNames: Set<String>): PrepareResult {
        val path = SmbPath.parse(imageUrl)
            ?: throw IllegalArgumentException("Not an SMB file: $imageUrl")
        val meta = SmbFileMetaCache.get(imageUrl)
            ?: throw IOException("File was not listed: $imageUrl")

        val cacheFile = "$CACHE_DIR/${sha256("$imageUrl|${meta.size}|${meta.lastModified}|$CACHE_VERSION")}.jpg"
        if (cacheFile.substringAfterLast('/') in knownCachedNames) return PrepareResult.ALREADY_THERE

        return synchronized(cacheLock) {
            if (SmbClient.fileExists(context, path.host, path.share, cacheFile)) {
                PrepareResult.ALREADY_THERE
            } else {
                val thumbnailBytes = createThumbnail(context, imageUrl, meta.size)
                SmbClient.writeBytes(context, path.host, path.share, cacheFile, thumbnailBytes)
                PrepareResult.CREATED
            }
        }
    }

    private fun openThumbnailStreamLocked(context: Context, imageUrl: String): InputStream {
        val path = SmbPath.parse(imageUrl)
            ?: return RemoteClients.forUrl(imageUrl)!!.openInputStream(context, imageUrl)

        val client = RemoteClients.forUrl(imageUrl)
            ?: throw IllegalArgumentException("No remote client for $imageUrl")

        // Size and date normally come from the folder listing. Only ask the server if the
        // image was not listed before.
        val meta = SmbFileMetaCache.get(imageUrl)
        val (fileSize, lastModified) = if (meta != null) {
            Pair(meta.size, meta.lastModified)
        } else {
            SmbClient.getFileSizeAndLastModified(context, path.host, path.share, path.path)
        }
        if (fileSize < 0L) {
            throw IOException("Could not read file information for $imageUrl")
        }

        val cacheKey = sha256("$imageUrl|$fileSize|$lastModified|$CACHE_VERSION")
        val cacheFile = "$CACHE_DIR/$cacheKey.jpg"
        val cachePath = SmbPath.toUrl(path.host, path.share, cacheFile)

        try {
            return client.openInputStream(context, cachePath)
        } catch (_: Exception) {
            // Not cached yet (or unreadable cache entry): generate it below.
        }

        val thumbnailBytes = createThumbnail(context, imageUrl, fileSize)
        try {
            SmbClient.writeBytes(context, path.host, path.share, cacheFile, thumbnailBytes)
        } catch (e: Exception) {
            // Still display the generated thumbnail even if saving it failed.
            android.util.Log.e("SmbThumbnailCache", "Could not save thumbnail to SMB cache: $cachePath", e)
        }
        return ByteArrayInputStream(thumbnailBytes)
    }

    private fun createThumbnail(context: Context, imageUrl: String, fileSize: Long): ByteArray {
        val source = if (RemoteFileTypes.isVideo(imageUrl)) {
            decodeVideoFrame(context, imageUrl)
        } else {
            decodeImage(context, imageUrl, fileSize)
        }

        val thumbnail = centerCrop(source, THUMB_SIZE)
        if (thumbnail !== source) source.recycle()

        return try {
            ByteArrayOutputStream().use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray()
            }
        } finally {
            thumbnail.recycle()
        }
    }

    /** Decodes an image, scaled down and rotated according to its EXIF orientation. */
    private fun decodeImage(context: Context, imageUrl: String, fileSize: Long): Bitmap {
        val client = RemoteClients.forUrl(imageUrl)
            ?: throw IllegalArgumentException("No remote client for $imageUrl")

        // Bounds, EXIF orientation and pixels each need the image data: download it only once.
        val data: ByteArray? = if (fileSize in 1..MAX_IN_MEMORY_SOURCE_BYTES) {
            client.openInputStream(context, imageUrl).use { it.readBytes() }
        } else {
            null
        }
        fun open(): InputStream =
            if (data != null) ByteArrayInputStream(data) else client.openInputStream(context, imageUrl)

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        open().use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, THUMB_SIZE)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val orientation = open().use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }

        val decoded = open().use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalStateException("Could not decode $imageUrl")

        val oriented = ImageOrientation.apply(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        return oriented
    }

    /**
     * Grabs one frame of a video. Only the parts of the file the decoder asks for are read
     * over the network, not the whole video.
     */
    private fun decodeVideoFrame(context: Context, videoUrl: String): Bitmap {
        val client = RemoteClients.forUrl(videoUrl)
            ?: throw IllegalArgumentException("No remote client for $videoUrl")

        val file = client.openRandomAccess(context, videoUrl)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(BufferedRemoteDataSource(file))
            return retriever.getFrameAtTime(
                VIDEO_FRAME_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("No video frame available for $videoUrl")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            try {
                file.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Lets [MediaMetadataRetriever] read a remote file, fetching it in larger blocks. */
    private class BufferedRemoteDataSource(
        private val file: RemoteRandomAccessFile
    ) : MediaDataSource() {

        private val block = ByteArray(VIDEO_READ_BLOCK_SIZE)
        private var blockStart = -1L
        private var blockLength = 0

        override fun getSize(): Long = file.size

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0

            var copied = 0
            var current = position
            while (copied < size && current < file.size) {
                val inBlock = blockStart >= 0 &&
                    current >= blockStart &&
                    current < blockStart + blockLength
                if (!inBlock) {
                    val read = file.readAt(current, block, 0, block.size)
                    if (read <= 0) break
                    blockStart = current
                    blockLength = read
                }
                val offsetInBlock = (current - blockStart).toInt()
                val chunk = minOf(size - copied, blockLength - offsetInBlock)
                System.arraycopy(block, offsetInBlock, buffer, offset + copied, chunk)
                copied += chunk
                current += chunk
            }
            return if (copied == 0) -1 else copied
        }

        override fun close() {
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

        return if (cropped.width == size && cropped.height == size) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, size, size, true).also {
                if (cropped !== source) cropped.recycle()
            }
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
