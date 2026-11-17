package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    private val minute = 60_000L

    /** Left the app at minute 10 (elapsed time), came back [awayMinutes] later. */
    private fun locks(awayMinutes: Double, timeoutMinutes: Long, tripStartedMinute: Double? = null): Boolean {
        val left = 10 * minute
        val now = left + (awayMinutes * minute).toLong()
        val trip = tripStartedMinute?.let { (it * minute).toLong() } ?: 0L
        return AppLock.locksOnReturn(left, now, trip, timeoutMinutes * minute)
    }

    @Test
    fun theSetting_isOffOrSeconds() {
        assertNull(AppLock.timeoutMillis("off"))
        assertNull(AppLock.timeoutMillis("nonsense"))
        assertEquals(0L, AppLock.timeoutMillis("0"))
        assertEquals(300_000L, AppLock.timeoutMillis("300"))
    }

    @Test
    fun atOnce_locksAfterAnyTimeAway() {
        assertTrue(locks(awayMinutes = 0.01, timeoutMinutes = 0))
    }

    @Test
    fun afterAWhile_locksOnlyOnceThatTimeIsOver() {
        assertFalse(locks(awayMinutes = 0.5, timeoutMinutes = 1))
        assertTrue(locks(awayMinutes = 1.5, timeoutMinutes = 1))
    }

    @Test
    fun editingInAnotherAppThatLiteGalleryOpened_doesNotLock() {
        // Snapseed opened at minute 9.9, app left at 10, back after 12 minutes.
        assertFalse(locks(awayMinutes = 12.0, timeoutMinutes = 0, tripStartedMinute = 9.9))
        // Chose the target in the share menu for a minute and a half.
        assertFalse(locks(awayMinutes = 3.0, timeoutMinutes = 0, tripStartedMinute = 8.5))
    }

    @Test
    fun aLongTripOrALaterLeaving_locksAsUsual() {
        // Away much longer than an edit takes.
        assertTrue(locks(awayMinutes = 45.0, timeoutMinutes = 0, tripStartedMinute = 9.9))
        // The other app was opened long before the app was left (e.g. the share menu was closed).
        assertTrue(locks(awayMinutes = 3.0, timeoutMinutes = 0, tripStartedMinute = 5.0))
    }
}
