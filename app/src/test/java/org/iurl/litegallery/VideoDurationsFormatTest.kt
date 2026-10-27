package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class VideoDurationsFormatTest {

    @Test
    fun unknownOrEmptyLengthGivesNoLabel() {
        assertEquals("", VideoDurations.format(0))
        assertEquals("", VideoDurations.format(-5))
    }

    @Test
    fun shortVideosShowMinutesAndSeconds() {
        assertEquals("0:05", VideoDurations.format(5_000))
        assertEquals("0:59", VideoDurations.format(59_999))
        assertEquals("1:01", VideoDurations.format(61_000))
        assertEquals("12:34", VideoDurations.format(754_000))
    }

    @Test
    fun longVideosShowHours() {
        assertEquals("1:00:00", VideoDurations.format(3_600_000))
        assertEquals("1:02:05", VideoDurations.format(3_725_000))
        assertEquals("10:00:01", VideoDurations.format(36_001_000))
    }
}
