package org.iurl.litegallery

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PictureDetailsTest {

    @Test
    fun sampleSize_isAsSharpAsTheScreenNeeds() {
        // Zoomed past the original's real size: every pixel.
        assertEquals(1, PictureDetails.sampleSizeFor(0.5f, 500f, 500f, 4096))
        assertEquals(1, PictureDetails.sampleSizeFor(1.9f, 1000f, 1000f, 4096))
        // 3.7 original pixels on one screen pixel: every second one is enough.
        assertEquals(2, PictureDetails.sampleSizeFor(3.7f, 4000f, 3000f, 4096))
        assertEquals(4, PictureDetails.sampleSizeFor(4f, 4000f, 3000f, 4096))
    }

    @Test
    fun sampleSize_staysWithinMemoryAndWhatTheScreenCanDraw() {
        // 24 million pixels would be too much memory.
        assertEquals(2, PictureDetails.sampleSizeFor(1f, 6000f, 4000f, 16384))
        // Small enough, but wider than the screen can draw at once.
        assertEquals(2, PictureDetails.sampleSizeFor(1f, 5000f, 1000f, 4096))
    }

    @Test
    fun aTurnedPicture_isShownWithWidthAndHeightSwapped() {
        val geometry = PictureDetails.shownGeometry(ExifInterface.ORIENTATION_ROTATE_90, 4000f, 3000f)
        assertEquals(3000f, geometry.width, 0.01f)
        assertEquals(4000f, geometry.height, 0.01f)

        // The stored picture's top left corner is the shown picture's top right one.
        val point = floatArrayOf(100f, 200f)
        geometry.toShown.mapPoints(point)
        assertEquals(2800f, point[0], 0.01f)
        assertEquals(100f, point[1], 0.01f)

        // And back.
        geometry.toStored.mapPoints(point)
        assertEquals(100f, point[0], 0.01f)
        assertEquals(200f, point[1], 0.01f)
    }

    @Test
    fun anUnturnedPicture_staysAsItIs() {
        val geometry = PictureDetails.shownGeometry(ExifInterface.ORIENTATION_NORMAL, 4000f, 3000f)
        assertEquals(4000f, geometry.width, 0.01f)
        assertEquals(3000f, geometry.height, 0.01f)
        val point = floatArrayOf(100f, 200f)
        geometry.toShown.mapPoints(point)
        assertEquals(100f, point[0], 0.01f)
        assertEquals(200f, point[1], 0.01f)
    }

    @Test
    fun onlyABiggerOriginalOfTheSameShape_addsDetails() {
        assertTrue(PictureDetails.addsDetails(4000f, 3000f, 1080, 810))
        // Sizes are rounded when the picture is loaded.
        assertTrue(PictureDetails.addsDetails(4000f, 3000f, 1080, 811))
        // Turned the other way than the shown picture.
        assertFalse(PictureDetails.addsDetails(3000f, 4000f, 1080, 810))
        // Not bigger than what is shown.
        assertFalse(PictureDetails.addsDetails(1000f, 750f, 1080, 810))
        assertFalse(PictureDetails.addsDetails(0f, 0f, 1080, 810))
    }
}
