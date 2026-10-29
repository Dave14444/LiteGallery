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
 * Thumbnails are square JPEG files of [THUMB_SIZE] x [THUMB_SIZE] pixels. Changing the size needs
 * a new [CACHE_VERSION] (new files on the share) and a new SmbThumbnailModel.PREFIX (so the
 * phone forgets its copies of the old ones).
 */
object SmbThumbnailCache {

    internal const val CACHE_DIR = ".LiteGalleryThumbnails"
    private const val CACHE_VERSION = "thumb-v6"
    /** Sharp also in the large two-column view and on the folder cards. */
    private const val THUMB_SIZE = 640
    private const val JPEG_QUALITY = 90

    /** Position of the video frame used as thumbnail. */
    private const val VIDEO_FRAME_TIME_US = 1_000_000L

    /** Read-ahead block when a video is read over the network. */
    private const val VIDEO_READ_BLOCK_SIZE = 512 * 1024

    /** Images up to this size are downloaded once and decoded from memory. */
    private const val MAX_IN_MEMORY_SOURCE_BYTES = 48L * 1024 * 1024

    /**
     * At most this many thumbnails are made at the same time. Each one downloads a whole picture,
     * so more would only slow the connection down and use up memory. Reading thumbnails that
     * already exist is not limited by this.
     */
    private const val MAX_PARALLEL_CREATIONS = 3

    private val creationPermits = java.util.concurrent.Semaphore(MAX_PARALLEL_CREATIONS)
    private const val MAX_PERMIT_WAIT_SECONDS = 120L

    // Two requests for the same thumbnail must not both create it. Different thumbnails are made
    // in parallel: a fixed set of locks, picked by the thumbnail's key, keeps this simple.
    private const val LOCK_STRIPES = 64
    private val stripeLocks = Array(LOCK_STRIPES) { Any() }

    private fun <T> withKeyLock(key: String, block: () -> T): T =
        synchronized(stripeLocks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]) { block() }

    private fun <T> limitedCreation(block: () -> T): T {
        // A creation that hangs (e.g. in the video decoder) keeps its permit. Waiting for a
        // permit is therefore limited, so such a file can never block all others for good.
        val acquired = creationPermits.tryAcquire(MAX_PERMIT_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!acquired) {
            android.util.Log.w("SmbThumbnailCache", "No free slot after $MAX_PERMIT_WAIT_SECONDS s; going ahead anyway")
        }
        try {
            return block()
        } finally {
            if (acquired) creationPermits.release()
        }
    }

    fun openThumbnailStream(context: Context, imageUrl: String): InputStream =
        openThumbnailStreamImpl(context, imageUrl)

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

        val key = cacheKey(imageUrl, meta.size, meta.lastModified)
        val thumbnailName = "$key.jpg"
        val durationName = "$key.dur"
        val isVideo = RemoteFileTypes.isVideo(imageUrl)
        val hasThumbnail = thumbnailName in knownCachedNames
        val hasDuration = !isVideo || durationName in knownCachedNames
        if (hasThumbnail && hasDuration) return PrepareResult.ALREADY_THERE

        return withKeyLock(key) {
            val thumbnailFile = "$CACHE_DIR/$thumbnailName"
            val durationFile = "$CACHE_DIR/$durationName"
            if (hasThumbnail || SmbClient.fileExists(context, path.host, path.share, thumbnailFile)) {
                // Only the length of the video is missing.
                if (isVideo && !hasDuration) {
                    val milliseconds = limitedCreation { readVideoDuration(context, imageUrl) }
                    if (milliseconds > 0L) writeDuration(context, path, durationFile, milliseconds)
                }
                PrepareResult.ALREADY_THERE
            } else {
                val created = limitedCreation { createThumbnail(context, imageUrl, meta.size) }
                SmbClient.writeBytes(context, path.host, path.share, thumbnailFile, created.bytes)
                if (created.durationMs > 0L) writeDuration(context, path, durationFile, created.durationMs)
                PrepareResult.CREATED
            }
        }
    }

    /**
     * Length of a video in milliseconds (0 if unknown). It is stored on the share next to the
     * video's thumbnail, so a video is opened for this only once.
     */
    fun videoDuration(context: Context, videoUrl: String): Long {
        val path = SmbPath.parse(videoUrl) ?: return 0L
        val meta = SmbFileMetaCache.get(videoUrl) ?: return 0L
        val durationFile = "$CACHE_DIR/${cacheKey(videoUrl, meta.size, meta.lastModified)}.dur"

        readDuration(context, path, durationFile)?.let { return it }

        return withKeyLock(durationFile) {
            readDuration(context, path, durationFile) ?: run {
                val milliseconds = limitedCreation { readVideoDuration(context, videoUrl) }
                if (milliseconds > 0L) writeDuration(context, path, durationFile, milliseconds)
                milliseconds
            }
        }
    }

    /** Name of the thumbnail files of a picture: "<key>.jpg" (and "<key>.dur" for videos). */
    internal fun cacheKey(url: String, fileSize: Long, lastModified: Long): String =
        sha256("$url|$fileSize|$lastModified|$CACHE_VERSION")

    private fun readDuration(context: Context, path: SmbPath, durationFile: String): Long? {
        if (!SmbClient.fileExists(context, path.host, path.share, durationFile)) return null
        return try {
            SmbClient.openInputStream(context, path.host, path.share, durationFile).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8).trim().toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDuration(context: Context, path: SmbPath, durationFile: String, milliseconds: Long) {
        try {
            SmbClient.writeBytes(
                context, path.host, path.share, durationFile,
                milliseconds.toString().toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            android.util.Log.w("SmbThumbnailCache", "Could not store the video length", e)
        }
    }

    /** Reads only the length from the video file (the decoder asks for a few blocks). */
    private fun readVideoDuration(context: Context, videoUrl: String): Long {
        val client = RemoteClients.forUrl(videoUrl) ?: return 0L
        val file = client.openRandomAccess(context, videoUrl)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(BufferedRemoteDataSource(file))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
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

    private fun openThumbnailStreamImpl(context: Context, imageUrl: String): InputStream {
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

        val key = cacheKey(imageUrl, fileSize, lastModified)
        val cacheFile = "$CACHE_DIR/$key.jpg"
        val cachePath = SmbPath.toUrl(path.host, path.share, cacheFile)

        // Fast path, no lock: most thumbnails exist already and only have to be read.
        try {
            return client.openInputStream(context, cachePath)
        } catch (_: Exception) {
            // Not cached yet (or unreadable cache entry): generate it below.
        }

        return withKeyLock(key) {
            // Another request may have made it while this one was waiting.
            if (SmbClient.fileExists(context, path.host, path.share, cacheFile)) {
                try {
                    return@withKeyLock client.openInputStream(context, cachePath)
                } catch (_: Exception) {
                    // Fall through and make it again.
                }
            }

            val created = limitedCreation { createThumbnail(context, imageUrl, fileSize) }
            try {
                SmbClient.writeBytes(context, path.host, path.share, cacheFile, created.bytes)
                if (created.durationMs > 0L) {
                    writeDuration(context, path, "$CACHE_DIR/$key.dur", created.durationMs)
                }
            } catch (e: Exception) {
                // Still display the generated thumbnail even if saving it failed.
                android.util.Log.e("SmbThumbnailCache", "Could not save thumbnail to SMB cache: $cachePath", e)
            }
            ByteArrayInputStream(created.bytes)
        }
    }

    /**
     * Which of the files in the thumbnail folder ([cachedNames]) no picture needs any more:
     * the picture was deleted, renamed or changed, or the thumbnail is from an older version of
     * the app. [validKeys] are the keys ([cacheKey]) of all pictures that exist now. Files that
     * are not thumbnails or video lengths are never touched.
     */
    internal fun unusedNames(cachedNames: Collection<String>, validKeys: Set<String>): List<String> =
        cachedNames.filter { name ->
            (name.endsWith(".jpg") || name.endsWith(".dur")) &&
                name.substringBeforeLast('.') !in validKeys
        }

    private class Created(val bytes: ByteArray, val durationMs: Long)

    private fun createThumbnail(context: Context, imageUrl: String, fileSize: Long): Created {
        var durationMs = 0L
        val source = if (RemoteFileTypes.isVideo(imageUrl)) {
            val frame = decodeVideoFrame(context, imageUrl)
            durationMs = frame.durationMs
            frame.bitmap
        } else {
            decodeImage(context, imageUrl, fileSize)
        }

        val thumbnail = centerCrop(source, THUMB_SIZE)
        if (thumbnail !== source) source.recycle()

        val bytes = try {
            ByteArrayOutputStream().use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            thumbnail.recycle()
        }
        return Created(bytes, durationMs)
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
            // Full colour depth: RGB_565 shows bands in skies and skin tones.
            inPreferredConfig = Bitmap.Config.ARGB_8888
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
    private class VideoFrame(val bitmap: Bitmap, val durationMs: Long)

    private fun decodeVideoFrame(context: Context, videoUrl: String): VideoFrame {
        val client = RemoteClients.forUrl(videoUrl)
            ?: throw IllegalArgumentException("No remote client for $videoUrl")

        val file = client.openRandomAccess(context, videoUrl)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(BufferedRemoteDataSource(file))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frame = retriever.getFrameAtTime(
                VIDEO_FRAME_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("No video frame available for $videoUrl")
            return VideoFrame(frame, durationMs)
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
