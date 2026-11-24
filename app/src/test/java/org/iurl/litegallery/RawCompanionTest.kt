package org.iurl.litegallery

import androidx.test.core.app.ApplicationProvider
import org.iurl.litegallery.RawCompanion.Entry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RawCompanionTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() = RawCompanion.forgetAll()

    @After
    fun tearDown() = RawCompanion.forgetAll()

    // ---------------------------------------------------------------- the search, with folders as maps

    private fun file(folder: String, name: String) = Entry(name, "$folder/$name", false)
    private fun folder(parent: String, name: String) = Entry(name, "$parent/$name", true)

    /** A share: folder path → what it holds. Records which folders were read. */
    private class Share(val folders: Map<String, List<Entry>>) {
        val read = mutableListOf<String>()
        fun list(path: String): List<Entry> {
            read += path
            return folders[path].orEmpty()
        }
    }

    @Test
    fun theRawFile_isFoundInAFolderInsideThePicturesFolder() {
        val share = Share(
            mapOf(
                "/Urlaub" to listOf(
                    file("/Urlaub", "IMG_1234.JPG"),
                    folder("/Urlaub", "Bearbeitet"),
                    folder("/Urlaub", "CR2 Dateien")
                ),
                "/Urlaub/Bearbeitet" to listOf(file("/Urlaub/Bearbeitet", "IMG_1234.jpg")),
                "/Urlaub/CR2 Dateien" to listOf(
                    file("/Urlaub/CR2 Dateien", "IMG_1233.CR2"),
                    file("/Urlaub/CR2 Dateien", "IMG_1234.CR2")
                )
            )
        )

        val found = RawCompanion.search("IMG_1234.JPG", "/Urlaub", list = share::list)

        assertEquals("/Urlaub/CR2 Dateien/IMG_1234.CR2", found?.path)
        // The folder that sounds like RAW files was looked in first (the other one not at all).
        assertEquals(listOf("/Urlaub", "/Urlaub/CR2 Dateien"), share.read)
    }

    @Test
    fun aFolderWithAnyName_isSearchedToo() {
        val share = Share(
            mapOf(
                "/2024" to listOf(file("/2024", "DSC_0001.jpg"), folder("/2024", "Kamera")),
                "/2024/Kamera" to listOf(file("/2024/Kamera", "dsc_0001.cr2"))
            )
        )
        assertEquals("/2024/Kamera/dsc_0001.cr2", RawCompanion.search("DSC_0001.jpg", "/2024", list = share::list)?.path)
    }

    @Test
    fun aRawFileNextToThePicture_isFoundWithoutLookingFurther() {
        val share = Share(
            mapOf(
                "/a" to listOf(file("/a", "IMG_1.JPG"), file("/a", "IMG_1.CR2"), folder("/a", "RAW"))
            )
        )
        assertEquals("/a/IMG_1.CR2", RawCompanion.search("IMG_1.JPG", "/a", list = share::list)?.path)
        assertEquals(listOf("/a"), share.read)
    }

    @Test
    fun onlyTheSameName_counts() {
        val share = Share(
            mapOf(
                "/a" to listOf(file("/a", "IMG_12.JPG"), folder("/a", "RAW")),
                "/a/RAW" to listOf(file("/a/RAW", "IMG_123.CR2"), file("/a/RAW", "IMG_1.CR2"), file("/a/RAW", "IMG_12.jpg"))
            )
        )
        assertNull(RawCompanion.search("IMG_12.JPG", "/a", list = share::list))
    }

    @Test
    fun theCamerasOwnFormat_comesBeforeADngMadeFromIt() {
        val entries = listOf(file("/r", "IMG_5.dng"), file("/r", "IMG_5.CR2"))
        assertEquals("IMG_5.CR2", RawCompanion.pick("IMG_5", entries)?.name)
        assertEquals("IMG_5.dng", RawCompanion.pick("IMG_5", listOf(file("/r", "IMG_5.dng")))?.name)
    }

    @Test
    fun hiddenAndSystemFolders_areLeftOut() {
        val order = RawCompanion.searchOrder(
            listOf(
                folder("/a", "Zeug"),
                folder("/a", ".wdmc"),
                folder("/a", "@eaDir"),
                folder("/a", "RAW-Bilder"),
                folder("/a", "Andere"),
                file("/a", "IMG_1.JPG")
            )
        ).map { it.name }
        assertEquals(listOf("RAW-Bilder", "Andere", "Zeug"), order)
    }

    @Test
    fun cancelling_stopsBeforeTheNextFolder() {
        val share = Share(
            mapOf(
                "/a" to listOf(file("/a", "IMG_1.JPG"), folder("/a", "B"), folder("/a", "C")),
                "/a/C" to listOf(file("/a/C", "IMG_1.CR2"))
            )
        )
        var folders = 0
        val found = RawCompanion.search("IMG_1.JPG", "/a", keepGoing = { ++folders <= 1 }, list = share::list)
        assertNull(found)
        assertEquals(listOf("/a", "/a/B"), share.read)
    }

    // ---------------------------------------------------------------- all RAW files of a folder

    @Test
    fun collect_findsTheRawFilesOfAllPictures() {
        val share = Share(
            mapOf(
                "/Urlaub" to listOf(
                    file("/Urlaub", "IMG_1.JPG"),
                    file("/Urlaub", "IMG_2.JPG"),
                    file("/Urlaub", "IMG_3.jpg"),
                    file("/Urlaub", "Clip.MP4"),
                    folder("/Urlaub", "snapseed"),
                    folder("/Urlaub", "RAW")
                ),
                "/Urlaub/RAW" to listOf(
                    file("/Urlaub/RAW", "IMG_1.CR2"),
                    file("/Urlaub/RAW", "IMG_2.CR2"),
                    // Its picture was deleted: not needed.
                    file("/Urlaub/RAW", "IMG_9.CR2")
                ),
                "/Urlaub/snapseed" to listOf(file("/Urlaub/snapseed", "IMG_3.cr2"))
            )
        )

        val found = RawCompanion.collect("/Urlaub", list = share::list)

        assertEquals(
            mapOf(
                "IMG_1" to "/Urlaub/RAW/IMG_1.CR2",
                "IMG_2" to "/Urlaub/RAW/IMG_2.CR2",
                "IMG_3" to "/Urlaub/snapseed/IMG_3.cr2"
            ),
            found.mapValues { it.value.path }
        )
    }

    @Test
    fun collect_stopsOnceEveryPictureHasItsRawFile() {
        val share = Share(
            mapOf(
                "/a" to listOf(file("/a", "IMG_1.JPG"), folder("/a", "RAW"), folder("/a", "Zeug")),
                "/a/RAW" to listOf(file("/a/RAW", "IMG_1.CR2")),
                "/a/Zeug" to listOf(file("/a/Zeug", "IMG_1.dng"))
            )
        )
        val found = RawCompanion.collect("/a", list = share::list)
        assertEquals("/a/RAW/IMG_1.CR2", found["IMG_1"]?.path)
        assertEquals(listOf("/a", "/a/RAW"), share.read)
    }

    @Test
    fun collect_keepsTheSizes() {
        val share = Share(
            mapOf(
                "/a" to listOf(file("/a", "IMG_1.JPG"), folder("/a", "RAW")),
                "/a/RAW" to listOf(Entry("IMG_1.CR2", "/a/RAW/IMG_1.CR2", false, 27_000_000L))
            )
        )
        assertEquals(27_000_000L, RawCompanion.collect("/a", list = share::list)["IMG_1"]?.size)
    }

    // ---------------------------------------------------------------- on the phone

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun touch(file: File): File = file.apply { parentFile?.mkdirs(); writeText("x") }

    @Test
    fun onThePhone_theRawFileIsFound() {
        val pictures = temp.newFolder("Urlaub")
        val jpg = touch(File(pictures, "IMG_1234.JPG"))
        val raw = touch(File(pictures, "Rohdaten/IMG_1234.CR2"))

        val found = RawCompanion.find(context, jpg.path, jpg.name)

        assertEquals(raw.path, found?.path)
    }

    @Test
    fun onThePhone_aRawFileAddedAfterAFailedSearch_isFoundNextTime() {
        val pictures = temp.newFolder("Urlaub")
        val jpg = touch(File(pictures, "IMG_7.JPG"))
        File(pictures, "RAW").mkdirs()
        assertNull(RawCompanion.find(context, jpg.path, jpg.name))

        val raw = touch(File(pictures, "RAW/IMG_7.CR2"))
        assertEquals(raw.path, RawCompanion.find(context, jpg.path, jpg.name)?.path)
    }

    @Test
    fun aPictureHandedOverByAnotherApp_hasNoFolderToLookIn() {
        assertNull(RawCompanion.find(context, "content://media/external/images/media/5", "IMG_5.JPG"))
    }
}
