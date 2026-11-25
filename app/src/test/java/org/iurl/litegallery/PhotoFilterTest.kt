package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFilterTest {

    private val bird = PhotoInfo("Canon EOS 7D Mark II", "EF100-400mm", 400.0, 5.6, 800)
    private val landscape = PhotoInfo("Canon EOS 7D Mark II", "EF-S10-22mm", 10.0, 8.0, 100)
    private val phone = PhotoInfo("Google Pixel 8", null, 6.9, 1.7, 50)

    @Test
    fun anEmptyFilter_letsEverythingPass() {
        val filter = PhotoFilter()
        assertTrue(filter.isEmpty)
        assertTrue(filter.matches(null, starred = false))
        assertTrue(filter.matches(bird, starred = false))
    }

    @Test
    fun onlyWithAStar() {
        val filter = PhotoFilter(starredOnly = true)
        assertTrue(filter.matches(bird, starred = true))
        assertFalse(filter.matches(bird, starred = false))
        // Videos (no photo data) with a star pass too.
        assertTrue(filter.matches(null, starred = true))
    }

    @Test
    fun byCameraAndLens() {
        val filter = PhotoFilter(cameras = setOf("Canon EOS 7D Mark II"), lenses = setOf("EF100-400mm"))
        assertTrue(filter.matches(bird, false))
        assertFalse(filter.matches(landscape, false))
        assertFalse(filter.matches(phone, false))
        assertFalse(filter.matches(null, false))
    }

    @Test
    fun byRanges() {
        val tele = PhotoFilter(focalMm = 300.0..400.0)
        assertTrue(tele.matches(bird, false))
        assertFalse(tele.matches(landscape, false))

        val open = PhotoFilter(fNumber = 1.4..5.6)
        // 5.6 as EXIF writes it (56/10, or 5.599 after rounding).
        assertTrue(open.matches(bird.copy(fNumber = 5.599), false))
        assertFalse(open.matches(landscape, false))

        val highIso = PhotoFilter(iso = 800..6400)
        assertTrue(highIso.matches(bird, false))
        assertFalse(highIso.matches(landscape, false))
        assertFalse(highIso.matches(bird.copy(iso = null), false))
    }

    @Test
    fun allConditionsTogether() {
        val filter = PhotoFilter(starredOnly = true, cameras = setOf("Canon EOS 7D Mark II"), iso = 400..1600)
        assertEquals(3, filter.activeCount)
        assertTrue(filter.matches(bird, true))
        assertFalse(filter.matches(bird, false))
        assertFalse(filter.matches(landscape, true))
    }

    @Test
    fun theChoices_areWhatOccurs() {
        val choices = PhotoFilter.choices(listOf(bird, bird.copy(focalMm = 286.0), landscape, phone))
        assertEquals(mapOf("Canon EOS 7D Mark II" to 3, "Google Pixel 8" to 1), choices.cameras)
        assertEquals(mapOf("EF100-400mm" to 2, "EF-S10-22mm" to 1), choices.lenses)
        assertEquals(listOf(6.9, 10.0, 286.0, 400.0), choices.focalMm)
        assertEquals(listOf(50, 100, 800), choices.iso)
    }
}
