package org.iurl.litegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a picture with a crop frame that can be moved and resized by dragging its corners,
 * edges or inside. The frame can be locked to a width/height ratio.
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Drag {
        NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private val density = resources.displayMetrics.density
    private val viewPadding = 16f * density
    private val touchSlop = 28f * density
    private val minSize = 56f * density
    private val handleLength = 22f * density

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val cropRect = RectF()

    /** width / height of the frame, or null for a free frame. */
    private var aspect: Float? = null

    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xA0000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.SQUARE
    }

    /** Shows [newBitmap] and resets the frame to cover all of it. */
    fun setBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        layoutImage()
        resetCrop()
        invalidate()
    }

    /** Locks the frame to [ratio] (width / height), or frees it with null. */
    fun setAspect(ratio: Float?) {
        aspect = ratio
        resetCrop()
        invalidate()
    }

    /** Width / height of the shown picture. */
    fun imageAspect(): Float? {
        val bmp = bitmap ?: return null
        return bmp.width.toFloat() / bmp.height.toFloat()
    }

    /** The frame as fractions (0..1) of the picture. */
    fun cropFraction(): RectF {
        val w = imageRect.width()
        val h = imageRect.height()
        if (w <= 0f || h <= 0f) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            ((cropRect.left - imageRect.left) / w).coerceIn(0f, 1f),
            ((cropRect.top - imageRect.top) / h).coerceIn(0f, 1f),
            ((cropRect.right - imageRect.left) / w).coerceIn(0f, 1f),
            ((cropRect.bottom - imageRect.top) / h).coerceIn(0f, 1f)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap == null) return
        val fraction = cropFraction()
        layoutImage()
        cropRect.set(
            imageRect.left + fraction.left * imageRect.width(),
            imageRect.top + fraction.top * imageRect.height(),
            imageRect.left + fraction.right * imageRect.width(),
            imageRect.top + fraction.bottom * imageRect.height()
        )
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        val availableWidth = width - 2 * viewPadding
        val availableHeight = height - 2 * viewPadding
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val scale = min(availableWidth / bmp.width, availableHeight / bmp.height)
        val drawWidth = bmp.width * scale
        val drawHeight = bmp.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    /** Biggest frame of the current ratio, centered on the picture. */
    private fun resetCrop() {
        if (imageRect.isEmpty) return
        var frameWidth = imageRect.width()
        var frameHeight = imageRect.height()
        val ratio = aspect
        if (ratio != null) {
            if (frameWidth / frameHeight > ratio) {
                frameWidth = frameHeight * ratio
            } else {
                frameHeight = frameWidth / ratio
            }
        }
        val centerX = imageRect.centerX()
        val centerY = imageRect.centerY()
        cropRect.set(
            centerX - frameWidth / 2f,
            centerY - frameHeight / 2f,
            centerX + frameWidth / 2f,
            centerY + frameHeight / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)

        // Darken everything outside the frame.
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint)

        // Rule-of-thirds grid and border.
        val thirdWidth = cropRect.width() / 3f
        val thirdHeight = cropRect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(
                cropRect.left + thirdWidth * i, cropRect.top,
                cropRect.left + thirdWidth * i, cropRect.bottom, gridPaint
            )
            canvas.drawLine(
                cropRect.left, cropRect.top + thirdHeight * i,
                cropRect.right, cropRect.top + thirdHeight * i, gridPaint
            )
        }
        canvas.drawRect(cropRect, borderPaint)

        // Corner handles.
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val len = min(handleLength, min(cropRect.width(), cropRect.height()) / 2f)
        canvas.drawLine(l, t, l + len, t, handlePaint)
        canvas.drawLine(l, t, l, t + len, handlePaint)
        canvas.drawLine(r, t, r - len, t, handlePaint)
        canvas.drawLine(r, t, r, t + len, handlePaint)
        canvas.drawLine(l, b, l + len, b, handlePaint)
        canvas.drawLine(l, b, l, b - len, handlePaint)
        canvas.drawLine(r, b, r - len, b, handlePaint)
        canvas.drawLine(r, b, r, b - len, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                if (drag != Drag.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag != Drag.NONE) {
                    applyDrag(event.x, event.y)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Drag {
        val nearLeft = abs(x - cropRect.left) <= touchSlop
        val nearRight = abs(x - cropRect.right) <= touchSlop
        val nearTop = abs(y - cropRect.top) <= touchSlop
        val nearBottom = abs(y - cropRect.bottom) <= touchSlop
        val insideX = x >= cropRect.left - touchSlop && x <= cropRect.right + touchSlop
        val insideY = y >= cropRect.top - touchSlop && y <= cropRect.bottom + touchSlop

        return when {
            nearLeft && nearTop -> Drag.TOP_LEFT
            nearRight && nearTop -> Drag.TOP_RIGHT
            nearLeft && nearBottom -> Drag.BOTTOM_LEFT
            nearRight && nearBottom -> Drag.BOTTOM_RIGHT
            nearLeft && insideY -> Drag.LEFT
            nearRight && insideY -> Drag.RIGHT
            nearTop && insideX -> Drag.TOP
            nearBottom && insideX -> Drag.BOTTOM
            cropRect.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun applyDrag(x: Float, y: Float) {
        val px = x.coerceIn(imageRect.left, imageRect.right)
        val py = y.coerceIn(imageRect.top, imageRect.bottom)

        when (drag) {
            Drag.MOVE -> {
                val dx = (x - lastX).coerceIn(imageRect.left - cropRect.left, imageRect.right - cropRect.right)
                val dy = (y - lastY).coerceIn(imageRect.top - cropRect.top, imageRect.bottom - cropRect.bottom)
                cropRect.offset(dx, dy)
            }
            Drag.TOP_LEFT -> resizeFromCorner(cropRect.right, cropRect.bottom, px, py)
            Drag.TOP_RIGHT -> resizeFromCorner(cropRect.left, cropRect.bottom, px, py)
            Drag.BOTTOM_LEFT -> resizeFromCorner(cropRect.right, cropRect.top, px, py)
            Drag.BOTTOM_RIGHT -> resizeFromCorner(cropRect.left, cropRect.top, px, py)
            Drag.LEFT, Drag.RIGHT -> resizeFromSide(horizontal = true, px = px, py = py)
            Drag.TOP, Drag.BOTTOM -> resizeFromSide(horizontal = false, px = px, py = py)
            Drag.NONE -> Unit
        }
    }

    /** The corner opposite to the dragged one ([fixedX], [fixedY]) stays where it is. */
    private fun resizeFromCorner(fixedX: Float, fixedY: Float, px: Float, py: Float) {
        val signX = if (px >= fixedX) 1f else -1f
        val signY = if (py >= fixedY) 1f else -1f
        val maxWidth = if (signX > 0f) imageRect.right - fixedX else fixedX - imageRect.left
        val maxHeight = if (signY > 0f) imageRect.bottom - fixedY else fixedY - imageRect.top

        var newWidth = abs(px - fixedX)
        var newHeight = abs(py - fixedY)
        val ratio = aspect
        if (ratio == null) {
            newWidth = newWidth.coerceAtMost(maxWidth).coerceAtLeast(min(minSize, maxWidth))
            newHeight = newHeight.coerceAtMost(maxHeight).coerceAtLeast(min(minSize, maxHeight))
        } else {
            newWidth = max(newWidth, newHeight * ratio)
                .coerceAtMost(maxWidth)
                .coerceAtMost(maxHeight * ratio)
            newWidth = newWidth.coerceAtLeast(min(minSize, min(maxWidth, maxHeight * ratio)))
            newHeight = newWidth / ratio
        }

        val otherX = fixedX + signX * newWidth
        val otherY = fixedY + signY * newHeight
        cropRect.set(min(fixedX, otherX), min(fixedY, otherY), max(fixedX, otherX), max(fixedY, otherY))
    }

    /** Drags the left/right ([horizontal]) or top/bottom edge; the opposite edge stays put. */
    private fun resizeFromSide(horizontal: Boolean, px: Float, py: Float) {
        val ratio = aspect
        if (horizontal) {
            val isLeft = drag == Drag.LEFT
            if (ratio == null) {
                if (isLeft) {
                    cropRect.left = min(px, cropRect.right - minSize)
                } else {
                    cropRect.right = max(px, cropRect.left + minSize)
                }
                return
            }
            val maxWidth = if (isLeft) cropRect.right - imageRect.left else imageRect.right - cropRect.left
            var newWidth = (if (isLeft) cropRect.right - px else px - cropRect.left)
                .coerceAtMost(maxWidth)
                .coerceAtLeast(min(minSize, maxWidth))
            var newHeight = newWidth / ratio
            if (newHeight > imageRect.height()) {
                newHeight = imageRect.height()
                newWidth = newHeight * ratio
            }
            val top = (cropRect.centerY() - newHeight / 2f)
                .coerceAtMost(imageRect.bottom - newHeight)
                .coerceAtLeast(imageRect.top)
            if (isLeft) {
                cropRect.set(cropRect.right - newWidth, top, cropRect.right, top + newHeight)
            } else {
                cropRect.set(cropRect.left, top, cropRect.left + newWidth, top + newHeight)
            }
        } else {
            val isTop = drag == Drag.TOP
            if (ratio == null) {
                if (isTop) {
                    cropRect.top = min(py, cropRect.bottom - minSize)
                } else {
                    cropRect.bottom = max(py, cropRect.top + minSize)
                }
                return
            }
            val maxHeight = if (isTop) cropRect.bottom - imageRect.top else imageRect.bottom - cropRect.top
            var newHeight = (if (isTop) cropRect.bottom - py else py - cropRect.top)
                .coerceAtMost(maxHeight)
                .coerceAtLeast(min(minSize, maxHeight))
            var newWidth = newHeight * ratio
            if (newWidth > imageRect.width()) {
                newWidth = imageRect.width()
                newHeight = newWidth / ratio
            }
            val left = (cropRect.centerX() - newWidth / 2f)
                .coerceAtMost(imageRect.right - newWidth)
                .coerceAtLeast(imageRect.left)
            if (isTop) {
                cropRect.set(left, cropRect.bottom - newHeight, left + newWidth, cropRect.bottom)
            } else {
                cropRect.set(left, cropRect.top, left + newWidth, cropRect.top + newHeight)
            }
        }
    }
}
