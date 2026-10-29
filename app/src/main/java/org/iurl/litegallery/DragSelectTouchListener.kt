package org.iurl.litegallery

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * Selecting several pictures in one go: after a long press, moving the finger (without lifting
 * it) selects everything between the long-pressed picture and the one under the finger. Near the
 * top or bottom edge the list scrolls along by itself.
 *
 * The long-press handler calls [start]; the listener then reports the adapter position under the
 * finger to [onDragTo] and the end of the gesture to [onDragEnd].
 */
class DragSelectTouchListener(
    private val recyclerView: RecyclerView,
    private val onDragTo: (adapterPosition: Int) -> Unit,
    private val onDragEnd: () -> Unit
) : RecyclerView.OnItemTouchListener {

    private var active = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastReported = RecyclerView.NO_POSITION
    private var scrollStep = 0

    private val density = recyclerView.resources.displayMetrics.density
    /** Height of the zones at the top and bottom edge that scroll the list. */
    private val edgePx = EDGE_DP * density
    /** Fastest scrolling, right at the edge, in pixels per frame. */
    private val maxStepPx = MAX_STEP_DP * density

    private val autoScroll = object : Runnable {
        override fun run() {
            if (!active || scrollStep == 0) return
            recyclerView.scrollBy(0, scrollStep)
            reportPositionUnderFinger()
            recyclerView.postOnAnimation(this)
        }
    }

    /** Call from the long-press handler: from now on, moving the finger selects. */
    fun start() {
        active = true
        lastReported = RecyclerView.NO_POSITION
        // The list (not the pull-to-refresh or the toolbar around it) gets the finger's moves.
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                active = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Long press without moving: nothing more to do.
                if (active) stop()
                return false
            }
        }
        // While selecting, this listener takes over the gesture (the list does not scroll).
        return active
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                updateAutoScroll()
                reportPositionUnderFinger()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun stop() {
        active = false
        scrollStep = 0
        recyclerView.removeCallbacks(autoScroll)
        onDragEnd()
    }

    private fun reportPositionUnderFinger() {
        val child = recyclerView.findChildViewUnder(lastX, lastY) ?: return
        val position = recyclerView.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION || position == lastReported) return
        lastReported = position
        onDragTo(position)
    }

    private fun updateAutoScroll() {
        val height = recyclerView.height.toFloat()
        val step = when {
            lastY < edgePx -> -stepFor(edgePx - lastY)
            lastY > height - edgePx -> stepFor(lastY - (height - edgePx))
            else -> 0
        }
        val wasScrolling = scrollStep != 0
        scrollStep = step
        if (step != 0 && !wasScrolling) recyclerView.postOnAnimation(autoScroll)
    }

    /** Faster the deeper the finger is in the edge zone ([depth] 0..edge). */
    private fun stepFor(depth: Float): Int =
        (maxStepPx * (depth / edgePx).coerceIn(0f, 1f)).toInt().coerceAtLeast(1)

    companion object {
        private const val EDGE_DP = 72f
        private const val MAX_STEP_DP = 24f

        /**
         * The selection while dragging: what was selected before the long press ([initial]),
         * plus ([selecting]) or minus the items between the long-pressed one and the finger
         * ([range], in the order the finger passed them).
         */
        internal fun selectionFor(
            initial: Set<String>,
            range: List<String>,
            selecting: Boolean
        ): LinkedHashSet<String> {
            val result = LinkedHashSet(initial)
            if (selecting) result.addAll(range) else result.removeAll(range.toSet())
            return result
        }
    }
}
