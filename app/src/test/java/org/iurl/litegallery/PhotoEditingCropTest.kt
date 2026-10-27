package org.iurl.litegallery

import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a crop chosen on the turned preview lies in the stored pixels of the file. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhotoEditingCropTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        listOf(left, top, right, bottom)

    private fun PhotoEditing.rectOf(
        width: Int,
        height: Int,
        orientation: Int,
        rotation: Int,
        crop: RectF
    ): List<Int> {
        val r = storedRectFor(width, height, orientation, rotation, crop)
        return rect(r.left, r.top, r.right, r.bottom)
    }

    @Test
    fun uprightPictureWithoutRotation_cropIsScaledToPixels() {
        val result = PhotoEditing.rectOf(
            1000, 800, ExifInterface.ORIENTATION_NORMAL, 0, RectF(0.25f, 0.25f, 0.75f, 0.75f)
        )

        assertEquals(rect(250, 200, 750, 600), result)
    }

    @Test
    fun wholePictureGivesTheWholeFile() {
        val result = PhotoEditing.rectOf(
            1000, 800, ExifInterface.ORIENTATION_NORMAL, 0, RectF(0f, 0f, 1f, 1f)
        )

        assertEquals(rect(0, 0, 1000, 800), result)
    }

    @Test
    fun picturesStoredOnTheSide_exifOrientationIsUndone() {
        // Stored 4000x3000, shown upright as 3000x4000 (rotated 90 degrees clockwise).
        // The left half of the upright picture is the lower half of the stored one.
        val result = PhotoEditing.rectOf(
            4000, 3000, ExifInterface.ORIENTATION_ROTATE_90, 0, RectF(0f, 0f, 0.5f, 1f)
        )

        assertEquals(rect(0, 1500, 4000, 3000), result)
    }

    @Test
    fun userRotationIsUndone() {
        // Turned 90 degrees clockwise by the user: the left half of the turned picture is the
        // lower half of the original (the top of the original ends up on the right).
        val result = PhotoEditing.rectOf(
            1000, 800, ExifInterface.ORIENTATION_NORMAL, 90, RectF(0f, 0f, 0.5f, 1f)
        )

        assertEquals(rect(0, 400, 1000, 800), result)
    }

    @Test
    fun rotationBy180_mirrorsTheCropArea() {
        val result = PhotoEditing.rectOf(
            1000, 800, ExifInterface.ORIENTATION_NORMAL, 180, RectF(0f, 0f, 0.5f, 0.5f)
        )

        assertEquals(rect(500, 400, 1000, 800), result)
    }

    @Test
    fun cropOutsideThePictureIsClampedToTheFile() {
        val result = PhotoEditing.rectOf(
            1000, 800, ExifInterface.ORIENTATION_NORMAL, 0, RectF(-0.5f, -0.5f, 1.5f, 1.5f)
        )

        assertEquals(rect(0, 0, 1000, 800), result)
    }

    @Test(expected = java.io.IOException::class)
    fun emptyCropIsRejected() {
        PhotoEditing.storedRectFor(
            1000, 800, ExifInterface.ORIENTATION_NORMAL, 0, RectF(0.5f, 0.5f, 0.5f, 0.5f)
        )
    }
}
