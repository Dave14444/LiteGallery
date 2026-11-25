package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhotoDataTest {

    private lateinit var context: Context
    private lateinit var fake: FakeMediaSource
    private val folder = "fake://nas/Bilder/Vögel"

    /** A small JPEG as a Canon EOS 7D Mark II writes its data: lens, 400 mm, f/5.6, ISO 800, GPS. */
    private val sample: ByteArray by lazy {
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("photo_data_sample.jpg")).use { it.readBytes() }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fake = FakeMediaSource()
        MediaSourceRegistry.register(fake)
        PhotoData.forgetAll()
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (MediaSourceRegistry.all() as MutableList<MediaSource>).remove(fake)
        PhotoData.forgetAll()
    }

    private fun assertSampleData(info: PhotoInfo?) {
        assertNotNull(info)
        info!!
        assertEquals("Canon EOS 7D Mark II", info.camera)
        assertEquals("EF100-400mm f/4.5-5.6L IS II USM", info.lens)
        assertEquals(400.0, info.focalMm!!, 0.01)
        assertEquals(5.6, info.fNumber!!, 0.01)
        assertEquals(800, info.iso)
        assertEquals(1.0 / 500, info.exposureSeconds!!, 1e-6)
        assertEquals(49.6917, info.latitude!!, 0.0001)
        assertEquals(8.62, info.longitude!!, 0.0001)
    }

    @Test
    fun theDataAreReadFromTheStartOfAPicture() {
        val exif = PhotoData.exifFromStart(sample)
        assertSampleData(exif?.let { PhotoData.fromExif(it) })
    }

    @Test
    fun aCutOffStartIsEnough_aslongAsItHoldsTheExifPart() {
        val segment = requireNotNull(PhotoData.jpegExifSegment(sample))
        // Cut right after the EXIF part (the image data are missing completely).
        val start = sample.indexOfSubArray(segment) + segment.size
        val cut = sample.copyOf(start + 3)
        assertSampleData(PhotoData.exifFromStart(cut)?.let { PhotoData.fromExif(it) })
    }

    @Test
    fun withoutExif_thereAreNoData() {
        assertNull(PhotoData.jpegExifSegment(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(PhotoData.jpegExifSegment(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2)))
    }

    private fun item(name: String, size: Long = 970L, modified: Long = 1_000L) = MediaItemSkeleton(
        id = name.hashCode().toLong(),
        path = "$folder/$name",
        name = name,
        dateModified = modified,
        size = size,
        isVideo = false
    )

    @Test
    fun aFolderIsReadOnce_andKeptForAllDevices() {
        fake.files["$folder/A.JPG"] = sample
        fake.files["$folder/B.JPG"] = sample
        val items = listOf(item("A.JPG"), item("B.JPG"))

        val first = PhotoData.load(context, folder, items)
        assertEquals(2, first.size)
        assertSampleData(first["$folder/B.JPG"])
        assertEquals(2, fake.inputsOpened)

        // Another device: nothing in memory, the data come from what is stored on the NAS.
        PhotoData.forgetAll()
        val again = PhotoData.load(context, folder, items)
        assertSampleData(again["$folder/A.JPG"])
        assertEquals(2, fake.inputsOpened)
    }

    @Test
    fun aChangedPicture_isReadAgain() {
        fake.files["$folder/A.JPG"] = sample
        PhotoData.load(context, folder, listOf(item("A.JPG")))
        PhotoData.load(context, folder, listOf(item("A.JPG", modified = 2_000L)))
        assertEquals(2, fake.inputsOpened)
    }

    @Test
    fun videos_areLeftOut() {
        val video = item("Clip.MP4").copy(isVideo = true)
        assertEquals(emptyMap<String, PhotoInfo>(), PhotoData.load(context, folder, listOf(video)))
        assertEquals(0, fake.inputsOpened)
    }

    @Test
    fun theStoredForm_isReadBack() {
        val entries = mapOf(
            "A.JPG" to PhotoData.Entry(970L, 1_000L, PhotoInfo("Cam", "Lens", 400.0, 5.6, 800, 0.002, 49.5, 8.5)),
            "B.JPG" to PhotoData.Entry(10L, 2L, PhotoInfo())
        )
        assertEquals(entries, PhotoData.parse(PhotoData.format(entries)))
    }

    private fun ByteArray.indexOfSubArray(part: ByteArray): Int {
        outer@ for (i in 0..size - part.size) {
            for (j in part.indices) if (this[i + j] != part[j]) continue@outer
            return i
        }
        return -1
    }
}
