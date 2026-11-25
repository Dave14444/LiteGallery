package org.iurl.litegallery

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** What the camera noted about a picture (EXIF), as far as it is there. */
data class PhotoInfo(
    val camera: String? = null,
    val lens: String? = null,
    val focalMm: Double? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val exposureSeconds: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/**
 * The photo data (camera, lens, focal length, aperture, ISO, place) of the pictures of a folder,
 * for filtering and the map.
 *
 * Read once per picture and then kept with the folder (see FolderData): on the NAS for all
 * devices. Of a picture on the NAS only its start is read ([HEADER_BYTES], where a camera keeps
 * these data) instead of the whole file.
 */
object PhotoData {

    private const val KEY = "photo-data"

    /** A JPEG keeps its photo data in its first segment of at most 64 KB. */
    internal const val HEADER_BYTES = 128 * 1024

    /** New entries are stored after this many, so a cancelled reading keeps what it had. */
    private const val STORE_EVERY = 50

    /** A stored entry: valid as long as the file has the same size and time. */
    internal data class Entry(val size: Long, val modified: Long, val info: PhotoInfo)

    /** Folder → name → entry, as last read or written. */
    private val byFolder = HashMap<String, Map<String, Entry>>()

    /**
     * The photo data of the pictures among [items] (all in [folderPath]), by path. Reads what is
     * not known yet (blocking; on the NAS the start of each new picture) and stores it.
     * [progress] gets (done, total) for the pictures still to read; [keepGoing] can stop early.
     */
    fun load(
        context: Context,
        folderPath: String,
        items: List<MediaItemSkeleton>,
        keepGoing: () -> Boolean = { true },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, PhotoInfo> {
        val folder = FolderData.normalize(folderPath)
        val stored = HashMap(known(context, folder))
        val pictures = items.filter { !it.isVideo }
        val missing = pictures.filter { item ->
            val entry = stored[item.name]
            entry == null || entry.size != item.size || entry.modified != item.dateModified
        }
        var added = 0
        for ((index, item) in missing.withIndex()) {
            if (!keepGoing()) break
            progress(index, missing.size)
            val info = try {
                read(context, item.path)
            } catch (e: Exception) {
                android.util.Log.w("PhotoData", "No photo data of ${item.path}", e)
                null
            }
            // Also without data (a picture without EXIF): it is not read again.
            stored[item.name] = Entry(item.size, item.dateModified, info ?: PhotoInfo())
            added++
            if (added % STORE_EVERY == 0) store(context, folder, stored)
        }
        if (added > 0) {
            progress(missing.size, missing.size)
            store(context, folder, stored)
        }
        return pictures.mapNotNull { item -> stored[item.name]?.let { item.path to it.info } }.toMap()
    }

    /** What is known of [folderPath] without reading any picture (from memory or the stored data). */
    private fun known(context: Context, folder: String): Map<String, Entry> {
        synchronized(byFolder) { byFolder[folder] }?.let { return it }
        val entries = try {
            parse(FolderData.read(context, folder, KEY))
        } catch (e: Exception) {
            android.util.Log.w("PhotoData", "Stored photo data of $folder not readable", e)
            emptyMap()
        }
        synchronized(byFolder) { byFolder[folder] = entries }
        return entries
    }

    private fun store(context: Context, folder: String, entries: Map<String, Entry>) {
        val copy = HashMap(entries)
        synchronized(byFolder) { byFolder[folder] = copy }
        try {
            FolderData.write(context, folder, KEY, format(copy))
        } catch (e: Exception) {
            // Kept in memory; read again next time.
            android.util.Log.w("PhotoData", "Photo data of $folder not stored", e)
        }
    }

    // ---------------------------------------------------------------- reading a picture

    /** The photo data of the picture at [path] (on the device or on a share). */
    fun read(context: Context, path: String): PhotoInfo? {
        val source = MediaSourceRegistry.forPath(path)
        val exif = when {
            source != null -> exifFromStart(source.readStart(context, path, HEADER_BYTES))
            path.startsWith("content://") -> null
            else -> File(path).takeIf { it.isFile }?.let { ExifInterface(it) }
        } ?: return null
        return fromExif(exif)
    }

    /**
     * The photo data from the start of a picture. Of a JPEG only its EXIF segment is handed to
     * ExifInterface (closed as an image by itself), so the cut-off rest does not matter.
     */
    internal fun exifFromStart(bytes: ByteArray): ExifInterface? {
        val segment = jpegExifSegment(bytes)
        val data = if (segment != null) {
            ByteArrayOutputStream(segment.size + 8).apply {
                write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                write(segment)
                // Start of the image data: the reading ends here.
                write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02))
            }.toByteArray()
        } else {
            bytes
        }
        return try {
            ExifInterface(ByteArrayInputStream(data))
        } catch (e: Exception) {
            null
        }
    }

    /** The APP1 "Exif" segment (marker included) of a JPEG starting with [bytes], or null. */
    internal fun jpegExifSegment(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var position = 2
        while (position + 4 <= bytes.size) {
            if (bytes[position] != 0xFF.toByte()) return null
            val marker = bytes[position + 1].toInt() and 0xFF
            // Start of the image data or end: no EXIF before it.
            if (marker == 0xDA || marker == 0xD9) return null
            val length = ((bytes[position + 2].toInt() and 0xFF) shl 8) or (bytes[position + 3].toInt() and 0xFF)
            val end = position + 2 + length
            if (length < 2 || end > bytes.size) return null
            val isExif = marker == 0xE1 && length >= 8 &&
                String(bytes, position + 4, 4, Charsets.US_ASCII) == "Exif"
            if (isExif) return bytes.copyOfRange(position, end)
            position = end
        }
        return null
    }

    internal fun fromExif(exif: ExifInterface): PhotoInfo {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
        val camera = when {
            model.isEmpty() -> make
            model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }.trim().ifEmpty { null }
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }
        // 0/0 is what some devices write when they had no position.
        val place = exif.latLong?.takeIf { it.size == 2 && !(it[0] == 0.0 && it[1] == 0.0) }
        return PhotoInfo(
            camera = camera,
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifEmpty { null },
            focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 },
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0.0 },
            iso = iso,
            exposureSeconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0.0 },
            latitude = place?.get(0),
            longitude = place?.get(1)
        )
    }

    // ---------------------------------------------------------------- stored form

    internal fun parse(json: String?): Map<String, Entry> {
        if (json.isNullOrBlank()) return emptyMap()
        val files = JSONObject(json).optJSONArray("files") ?: return emptyMap()
        val result = HashMap<String, Entry>()
        for (i in 0 until files.length()) {
            val o = files.getJSONObject(i)
            fun d(name: String): Double? = if (o.has(name)) o.getDouble(name) else null
            result[o.getString("name")] = Entry(
                size = o.optLong("size", -1L),
                modified = o.optLong("modified", 0L),
                info = PhotoInfo(
                    camera = o.optString("camera").ifEmpty { null },
                    lens = o.optString("lens").ifEmpty { null },
                    focalMm = d("focal"),
                    fNumber = d("f"),
                    iso = if (o.has("iso")) o.getInt("iso") else null,
                    exposureSeconds = d("exposure"),
                    latitude = d("lat"),
                    longitude = d("lon")
                )
            )
        }
        return result
    }

    internal fun format(entries: Map<String, Entry>): String {
        val files = JSONArray()
        for ((name, entry) in entries.toSortedMap()) {
            val info = entry.info
            val o = JSONObject()
                .put("name", name)
                .put("size", entry.size)
                .put("modified", entry.modified)
            info.camera?.let { o.put("camera", it) }
            info.lens?.let { o.put("lens", it) }
            info.focalMm?.let { o.put("focal", it) }
            info.fNumber?.let { o.put("f", it) }
            info.iso?.let { o.put("iso", it) }
            info.exposureSeconds?.let { o.put("exposure", it) }
            info.latitude?.let { o.put("lat", it) }
            info.longitude?.let { o.put("lon", it) }
            files.put(o)
        }
        return JSONObject().put("version", 1).put("files", files).toString()
    }

    /** For tests: nothing kept in memory. */
    internal fun forgetAll() {
        synchronized(byFolder) { byFolder.clear() }
    }
}
