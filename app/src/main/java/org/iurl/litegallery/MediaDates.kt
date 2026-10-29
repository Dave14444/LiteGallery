package org.iurl.litegallery

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Reading the date a picture was taken (EXIF) and setting the date the gallery sorts by.
 *
 * The gallery sorts and groups by the modification date of the file, on the phone and on
 * network shares. Pictures without a date taken (e.g. saved from Snapchat) get the date of the
 * moment they were copied or moved; setting the modification date puts them back in place. For
 * JPEG files the date can also be written into the EXIF data, so it survives copying into other
 * apps or a cloud that do not keep the modification date.
 */
object MediaDates {

    private const val EXIF_PATTERN = "yyyy:MM:dd HH:mm:ss"
    private const val WORK_DIRECTORY = "date_edit"
    private val EXIF_WRITABLE_EXTENSIONS = setOf("jpg", "jpeg")

    /** Whether [setDate] can also write the date into the file's EXIF data. */
    fun canWriteExif(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in EXIF_WRITABLE_EXTENSIONS

    /** Whether the date of [path] can be changed (files on the phone and on writable shares). */
    fun canChange(path: String): Boolean {
        val source = MediaSourceRegistry.forPath(path)
        return if (source == null) path.startsWith("/") else source.supportsFileOperations(path)
    }

    /** The date taken from the EXIF data of [path] (phone or share), or null. Blocking. */
    fun readTakenDate(context: Context, path: String): Long? {
        val exif = try {
            val source = MediaSourceRegistry.forPath(path)
            if (source == null) {
                ExifInterface(path)
            } else {
                source.openInput(context, path).use { ExifInterface(it) }
            }
        } catch (e: Exception) {
            return null
        }
        val zone = TimeZone.getDefault()
        return parseExifDate(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            zone
        ) ?: parseExifDate(
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
            zone
        )
    }

    /**
     * Gives [path] the date [millis]: as modification date (what the gallery sorts by) and, with
     * [writeExif] for JPEG files, also as date taken in the EXIF data. Returns true on success.
     * Blocking.
     */
    fun setDate(context: Context, path: String, millis: Long, writeExif: Boolean): Boolean {
        val withExif = writeExif && canWriteExif(path)
        val source = MediaSourceRegistry.forPath(path)
        return if (source == null) {
            setLocal(context, File(path), millis, withExif)
        } else {
            setOnSource(context, source, path, millis, withExif)
        }
    }

    private fun setLocal(context: Context, file: File, millis: Long, withExif: Boolean): Boolean {
        if (!file.isFile) return false
        // Writing the EXIF data changes the file, so the modification date is set afterwards.
        if (withExif) writeExifDate(file, millis)
        val ok = file.setLastModified(millis) && abs(file.lastModified() - millis) < 2_000L
        // MediaStore (where the gallery reads the date) has to read the file again.
        FileTransfer.localScanner(context, arrayOf(file.absolutePath))
        return ok
    }

    private fun setOnSource(
        context: Context,
        source: MediaSource,
        path: String,
        millis: Long,
        withExif: Boolean
    ): Boolean {
        if (!withExif) return source.setLastModified(context, path, millis)

        // EXIF data can only be written into a local file: edit a copy and put it in place of
        // the original (the original survives if this fails).
        val directory = File(context.cacheDir, WORK_DIRECTORY).apply { mkdirs() }
        val copy = File(directory, "date_${System.nanoTime()}.jpg")
        try {
            source.openInput(context, path).use { input ->
                copy.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            writeExifDate(copy, millis)
            return copy.inputStream().use { input -> source.replaceFile(context, path, input, millis) }
        } finally {
            copy.delete()
        }
    }

    private fun writeExifDate(file: File, millis: Long) {
        val zone = TimeZone.getDefault()
        val date = formatExifDate(millis, zone)
        val offset = formatOffset(millis, zone)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
            saveAttributes()
        }
    }

    // ---------------------------------------------------------------- EXIF date text

    /**
     * Reads an EXIF date ("2026:10:29 15:29:00"). [offset] ("+01:00") is the time zone it was
     * taken in; without it the date counts as local time in [defaultZone].
     */
    internal fun parseExifDate(value: String?, offset: String?, defaultZone: TimeZone): Long? {
        val text = value?.trim().orEmpty()
        if (text.length < EXIF_PATTERN.length || text.startsWith("0000")) return null
        val zone = offset?.let { parseOffset(it) } ?: defaultZone
        return try {
            SimpleDateFormat(EXIF_PATTERN, Locale.US).apply {
                timeZone = zone
                isLenient = false
            }.parse(text.substring(0, EXIF_PATTERN.length))?.time
        } catch (e: Exception) {
            null
        }
    }

    internal fun formatExifDate(millis: Long, zone: TimeZone): String =
        SimpleDateFormat(EXIF_PATTERN, Locale.US).apply { timeZone = zone }.format(Date(millis))

    /** The offset of [zone] at [millis] as EXIF writes it, e.g. "+01:00" or "-05:30". */
    internal fun formatOffset(millis: Long, zone: TimeZone): String {
        val minutes = zone.getOffset(millis) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        val absolute = abs(minutes)
        return String.format(Locale.US, "%s%02d:%02d", sign, absolute / 60, absolute % 60)
    }

    private fun parseOffset(offset: String): TimeZone? {
        val match = Regex("^([+-])(\\d{2}):(\\d{2})$").matchEntire(offset.trim()) ?: return null
        val (sign, hours, minutes) = match.destructured
        return TimeZone.getTimeZone("GMT$sign$hours:$minutes")
    }
}
