package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectSelectionTest {

    @Test
    fun dragging_addsEverythingBetweenStartAndFinger() {
        val result = DragSelectTouchListener.selectionFor(
            initial = emptySet(),
            range = listOf("a", "b", "c"),
            selecting = true
        )

        assertEquals(listOf("a", "b", "c"), result.toList())
    }

    @Test
    fun dragging_keepsWhatWasSelectedBefore() {
        val result = DragSelectTouchListener.selectionFor(
            initial = setOf("x"),
            range = listOf("a", "b"),
            selecting = true
        )

        assertEquals(listOf("x", "a", "b"), result.toList())
    }

    @Test
    fun movingBack_deselectsWhatTheFingerLeft() {
        // First the finger went over a, b, c; then back to b: c is no longer in the range.
        val initial = setOf("x")
        val wider = DragSelectTouchListener.selectionFor(initial, listOf("a", "b", "c"), selecting = true)
        val narrower = DragSelectTouchListener.selectionFor(initial, listOf("a", "b"), selecting = true)

        assertEquals(setOf("x", "a", "b", "c"), wider)
        assertEquals(setOf("x", "a", "b"), narrower)
    }

    @Test
    fun startingOnASelectedPicture_deselectsTheRange() {
        val result = DragSelectTouchListener.selectionFor(
            initial = setOf("a", "b", "c", "d"),
            range = listOf("b", "c"),
            selecting = false
        )

        assertEquals(listOf("a", "d"), result.toList())
    }

    @Test
    fun selectionOrderFollowsTheFinger() {
        // Dragging upwards: the pictures are added in the order the finger passed them.
        val result = DragSelectTouchListener.selectionFor(
            initial = emptySet(),
            range = listOf("e", "d", "c"),
            selecting = true
        )

        assertEquals(listOf("e", "d", "c"), result.toList())
    }
}
