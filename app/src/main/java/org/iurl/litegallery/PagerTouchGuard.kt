package org.iurl.litegallery

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Keeps the page swiping of the viewer (ViewPager2) from taking over a touch that becomes a
 * two-finger zoom.
 *
 * The pager takes a touch as soon as the first finger has moved a little sideways, which often
 * happens before the second finger lands: the zoom then turns into a swipe to the next picture.
 * So the pager is held off at the start of every touch. It only gets the touch when one finger
 * clearly moves sideways (a swipe); once a second finger is down, it stays out until all fingers
 * are lifted.
 */
internal class PagerTouchGuard(private val view: View) {

    // A clear sideways move of one finger: a few times the distance that starts a scroll.
    private val swipeDistance = ViewConfiguration.get(view.context).scaledTouchSlop * SWIPE_SLOP_FACTOR

    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private var multiTouch = false

    /**
     * Call first with every touch event of the view. With [keepHolding] (e.g. while zoomed in,
     * where one finger moves the picture) the pager is not let in for a sideways move either.
     */
    fun onTouchEvent(event: MotionEvent, keepHolding: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                multiTouch = false
                hold(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                hold(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (holding && !multiTouch && !keepHolding && event.pointerCount == 1) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // A swipe to the next or previous picture: the pager may have it now.
                    if (abs(dx) > swipeDistance && abs(dx) > abs(dy)) hold(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // The parent forgets the request with the end of the touch by itself.
                holding = false
                multiTouch = false
            }
        }
    }

    private fun hold(value: Boolean) {
        holding = value
        view.parent?.requestDisallowInterceptTouchEvent(value)
    }

    private companion object {
        const val SWIPE_SLOP_FACTOR = 3
    }
}
