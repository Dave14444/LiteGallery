package org.iurl.litegallery

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PagerTouchGuardTest {

    /** Stands in for the pager: remembers whether it was asked to keep out. */
    private class Pager(context: Context) : FrameLayout(context) {
        var keepOut: Boolean? = null

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            keepOut = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    private lateinit var pager: Pager
    private lateinit var guard: PagerTouchGuard
    private var slop = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        pager = Pager(context)
        val picture = View(context)
        pager.addView(picture)
        guard = PagerTouchGuard(picture)
        slop = ViewConfiguration.get(context).scaledTouchSlop
    }

    private fun event(action: Int, x: Float, y: Float = 100f): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    private fun pointerDown(): MotionEvent =
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120f)

    @Test
    fun aTouch_startsWithThePagerKeptOut() {
        guard.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f), keepHolding = false)
        assertEquals(true, pager.keepOut)

        // A small sideways move is not a swipe yet: the second finger may still come.
        guard.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + slop * 2), keepHolding = false)
        assertEquals(true, pager.keepOut)
    }

    @Test
    fun oneFingerMovingClearlySideways_isASwipe() {
        guard.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f), keepHolding = false)
        guard.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + slop * 4), keepHolding = false)
        assertEquals(false, pager.keepOut)
    }

    @Test
    fun withASecondFinger_thePagerStaysOut() {
        guard.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f), keepHolding = false)
        guard.onTouchEvent(pointerDown(), keepHolding = false)
        guard.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + slop * 10), keepHolding = false)
        assertEquals(true, pager.keepOut)
    }

    @Test
    fun whileZoomedIn_oneFingerMovesThePictureNotThePage() {
        guard.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f), keepHolding = true)
        guard.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + slop * 10), keepHolding = true)
        assertEquals(true, pager.keepOut)
    }

    @Test
    fun movingUpOrDown_isNoSwipeToTheNextPicture() {
        guard.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f), keepHolding = false)
        guard.onTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + slop * 4, 100f + slop * 8), keepHolding = false)
        assertEquals(true, pager.keepOut)
    }
}
