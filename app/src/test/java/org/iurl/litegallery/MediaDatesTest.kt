package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MediaDatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")
    private val originalScanner = FileTransfer.localScanner

    @After
    fun tearDown() {
        FileTransfer.localScanner = originalScanner
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    @Test
    fun parse_readsTheExifDateInTheGivenZone() {
        assertEquals(
            utcMillis(2026, 10, 29, 15, 29),
            MediaDates.parseExifDate("2026:10:29 15:29:00", null, utc)
        )
    }

    @Test
    fun parse_usesTheOffsetStoredWithTheDate() {
        // Taken at 15:29 local time two hours ahead of UTC.
        assertEquals(
            utcMillis(2026, 7, 1, 13, 29),
            MediaDates.parseExifDate("2026:07:01 15:29:00", "+02:00", utc)
        )
    }

    @Test
    fun parse_rejectsEmptyAndPlaceholderDates() {
        assertNull(MediaDates.parseExifDate(null, null, utc))
        assertNull(MediaDates.parseExifDate("", null, utc))
        assertNull(MediaDates.parseExifDate("0000:00:00 00:00:00", null, utc))
        assertNull(MediaDates.parseExifDate("yesterday at noon", null, utc))
        assertNull(MediaDates.parseExifDate("2026:13:45 25:61:00", null, utc))
    }

    @Test
    fun format_isTheInverseOfParse() {
        val millis = utcMillis(2026, 3, 14, 9, 26, 53)
        val text = MediaDates.formatExifDate(millis, berlin)

        assertEquals("2026:03:14 10:26:53", text)
        assertEquals(millis, MediaDates.parseExifDate(text, MediaDates.formatOffset(millis, berlin), utc))
    }

    @Test
    fun offset_followsDaylightSavingAndOddZones() {
        assertEquals("+01:00", MediaDates.formatOffset(utcMillis(2026, 1, 15, 12, 0), berlin))
        assertEquals("+02:00", MediaDates.formatOffset(utcMillis(2026, 7, 15, 12, 0), berlin))
        assertEquals("+05:30", MediaDates.formatOffset(utcMillis(2026, 1, 15, 12, 0), TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals("-05:00", MediaDates.formatOffset(utcMillis(2026, 1, 15, 12, 0), TimeZone.getTimeZone("America/New_York")))
    }

    @Test
    fun exifCanOnlyBeWrittenIntoJpegFiles() {
        assertTrue(MediaDates.canWriteExif("/sdcard/DCIM/a.jpg"))
        assertTrue(MediaDates.canWriteExif("/sdcard/DCIM/A.JPEG"))
        assertFalse(MediaDates.canWriteExif("/sdcard/DCIM/a.png"))
        assertFalse(MediaDates.canWriteExif("/sdcard/DCIM/clip.mp4"))
    }

    @Test
    fun onlyFilesWithAPathCanBeChanged() {
        assertTrue(MediaDates.canChange("/storage/emulated/0/Pictures/Snapchat/a.jpg"))
        assertFalse(MediaDates.canChange("content://media/external/images/media/42"))
    }

    @Test
    fun setDate_onThePhoneSetsTheModificationDateAndAsksForARescan() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val scanned = mutableListOf<String>()
        FileTransfer.localScanner = { _, paths -> scanned.addAll(paths) }
        val file = File(tmp.root, "snap.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val wanted = utcMillis(2024, 8, 3, 18, 45)

        val ok = MediaDates.setDate(context, file.absolutePath, wanted, writeExif = true)

        assertTrue(ok)
        assertTrue(kotlin.math.abs(file.lastModified() - wanted) < 2_000L)
        assertEquals(listOf(file.absolutePath), scanned)
        // Not a JPEG: the content was left alone.
        assertTrue(file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun setDate_failsForMissingFiles() {
        val context: Context = ApplicationProvider.getApplicationContext()
        FileTransfer.localScanner = { _, _ -> }

        assertFalse(MediaDates.setDate(context, File(tmp.root, "gone.jpg").absolutePath, 0L, false))
    }
}
