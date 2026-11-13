package org.iurl.litegallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Sharp details when zooming in. The picture in the viewer is only loaded as big as the screen;
 * zoomed in, its pixels would just get bigger and the picture blurry. So the part that is on
 * screen is read from the original file (the camera picture) in the resolution the zoom needs
 * and drawn over it. Moving or zooming further reads the new part once the picture holds still.
 *
 * Only the visible part is kept in memory, so this works for very big pictures as well. The
 * original is only opened once the picture is zoomed in.
 */
internal class PictureDetails(private val view: ImageView) {

    /** The original, opened for reading parts of it. Only used on the [worker] thread. */
    private class Original(
        val decoder: BitmapRegionDecoder,
        val orientation: Int,
        /** From the stored picture to the picture as it is shown (EXIF orientation). */
        val toShown: Matrix,
        val toStored: Matrix,
        val width: Float,
        val height: Float
    )

    private class Part(val bitmap: Bitmap, val where: RectF, val sampleSize: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestRunnable = Runnable { requestPart() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawRect = RectF()

    // On the main thread.
    private var generation = 0
    private var requestId = 0
    private var part: Part? = null
    private var source: (() -> File?)? = null
    private var opening = false

    /** The biggest picture the screen can draw at once (known after the first drawing). */
    private var maxBitmapSide = DEFAULT_MAX_BITMAP_SIDE

    /** Size of the original as shown (after turning it), 0 until it is opened. */
    var originalWidth = 0f
        private set
    var originalHeight = 0f
        private set

    // Only on the worker thread.
    private var original: Original? = null

    @Volatile
    private var latestRequest = 0

    /**
     * Where the original can be read: a local file ([file] blocks and may load it, e.g. from the
     * image cache). Null: no sharp details (only the loaded picture is zoomed).
     */
    fun setSource(file: (() -> File?)?) {
        generation++
        mainHandler.removeCallbacks(requestRunnable)
        source = file
        opening = false
        part = null
        originalWidth = 0f
        originalHeight = 0f
        view.invalidate()
        worker.execute { close() }
    }

    /** The view's zoom or position changed: once it holds still, read the part that is on screen. */
    fun onViewChanged() {
        mainHandler.removeCallbacks(requestRunnable)
        if (screenPixelsPerPicturePixel() <= SHARP_ENOUGH) {
            // Not zoomed in (any more): the loaded picture is sharp.
            if (part != null) {
                part = null
                requestId++
                view.invalidate()
            }
            return
        }
        if (originalWidth <= 0f) {
            openOriginal()
            return
        }
        mainHandler.postDelayed(requestRunnable, SETTLE_MS)
    }

    /** Opens the original the first time the picture is zoomed in; then reads the visible part. */
    private fun openOriginal() {
        val file = source ?: return
        val drawable = view.drawable ?: return
        val loadedWidth = drawable.intrinsicWidth
        val loadedHeight = drawable.intrinsicHeight
        if (opening) return
        opening = true
        val myGeneration = generation
        worker.execute {
            close()
            val readable = try {
                file()?.let { open(it) }
            } catch (e: Exception) {
                android.util.Log.w("PictureDetails", "Original cannot be read in parts", e)
                null
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("PictureDetails", "Original too big for the memory", e)
                null
            }
            val opened = if (readable != null &&
                !addsDetails(readable.width, readable.height, loadedWidth, loadedHeight)
            ) {
                // Not the picture that is shown (e.g. turned differently), or not bigger than it.
                readable.decoder.recycle()
                null
            } else {
                readable
            }
            original = opened
            mainHandler.post {
                if (myGeneration != generation) return@post
                if (opened == null) {
                    // Not readable in parts: no sharp details for this picture.
                    source = null
                    return@post
                }
                originalWidth = opened.width
                originalHeight = opened.height
                onViewChanged()
            }
        }
    }

    fun draw(canvas: Canvas) {
        maxBitmapSide = minOf(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
            .takeIf { it > 0 } ?: DEFAULT_MAX_BITMAP_SIDE
        val shown = part ?: return
        val drawable = view.drawable ?: return
        if (originalWidth <= 0f || originalHeight <= 0f) return
        val sx = drawable.intrinsicWidth / originalWidth
        val sy = drawable.intrinsicHeight / originalHeight
        drawRect.set(shown.where.left * sx, shown.where.top * sy, shown.where.right * sx, shown.where.bottom * sy)
        val saved = canvas.save()
        canvas.translate(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
        canvas.concat(view.imageMatrix)
        canvas.drawBitmap(shown.bitmap, null, drawRect, paint)
        canvas.restoreToCount(saved)
    }

    /** How many screen pixels one pixel of the loaded picture covers now. */
    private fun screenPixelsPerPicturePixel(): Float {
        if (view.drawable == null) return 0f
        val values = FloatArray(9)
        view.imageMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun requestPart() {
        if (originalWidth <= 0f) return
        val drawable = view.drawable ?: return
        val loadedWidth = drawable.intrinsicWidth.toFloat()
        val loadedHeight = drawable.intrinsicHeight.toFloat()
        if (loadedWidth <= 0f || loadedHeight <= 0f || view.width <= 0 || view.height <= 0) return

        // The part of the picture that is on screen, in pixels of the original.
        val inverse = Matrix()
        if (!view.imageMatrix.invert(inverse)) return
        val visible = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        inverse.mapRect(visible)
        if (!visible.intersect(0f, 0f, loadedWidth, loadedHeight)) return
        val scaleX = originalWidth / loadedWidth
        val scaleY = originalHeight / loadedHeight
        val wanted = RectF(visible.left * scaleX, visible.top * scaleY, visible.right * scaleX, visible.bottom * scaleY)
        // A little around it, so moving the picture a bit stays sharp.
        wanted.inset(-wanted.width() * MARGIN, -wanted.height() * MARGIN)
        wanted.intersect(0f, 0f, originalWidth, originalHeight)

        val originalPerScreenPixel = scaleX / screenPixelsPerPicturePixel()
        val sampleSize = sampleSizeFor(originalPerScreenPixel, wanted.width(), wanted.height(), maxBitmapSide)

        // Already shown with this sharpness.
        val current = part
        if (current != null && current.sampleSize == sampleSize && current.where.contains(wanted)) return

        val myGeneration = generation
        val myRequest = ++requestId
        latestRequest = myRequest
        worker.execute {
            // Only the newest request is worth the work.
            if (myRequest != latestRequest) return@execute
            val decoded = decode(wanted, sampleSize) ?: return@execute
            mainHandler.post {
                if (myGeneration != generation || myRequest != requestId) return@post
                part = decoded
                view.invalidate()
            }
        }
    }

    /** On the worker thread. */
    private fun decode(wanted: RectF, sampleSize: Int): Part? {
        val opened = original ?: return null
        val stored = RectF(wanted)
        opened.toStored.mapRect(stored)
        val rect = Rect(
            floor(stored.left).toInt().coerceAtLeast(0),
            floor(stored.top).toInt().coerceAtLeast(0),
            ceil(stored.right).toInt().coerceAtMost(opened.decoder.width),
            ceil(stored.bottom).toInt().coerceAtMost(opened.decoder.height)
        )
        if (rect.isEmpty) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            opened.decoder.decodeRegion(rect, options)
        } catch (e: Exception) {
            android.util.Log.w("PictureDetails", "Part could not be read", e)
            null
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("PictureDetails", "Part too big for the memory", e)
            null
        } ?: return null

        val shown = try {
            ImageOrientation.apply(bitmap, opened.orientation)
        } catch (e: OutOfMemoryError) {
            return null
        }
        if (shown !== bitmap) bitmap.recycle()
        val where = RectF(rect)
        opened.toShown.mapRect(where)
        return Part(shown, where, sampleSize)
    }

    /** On the worker thread. */
    private fun open(file: File): Original? {
        if (!file.isFile) return null
        val decoder = openDecoder(file) ?: return null
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val geometry = shownGeometry(orientation, decoder.width.toFloat(), decoder.height.toFloat())
        return Original(
            decoder,
            orientation,
            geometry.toShown,
            geometry.toStored,
            geometry.width,
            geometry.height
        )
    }

    /** JPEG, PNG, WebP and HEIF can be read in parts; null for anything else (e.g. GIF, BMP). */
    @Suppress("DEPRECATION") // The replacement needs Android 12.
    private fun openDecoder(file: File): BitmapRegionDecoder? = try {
        BitmapRegionDecoder.newInstance(file.absolutePath, false)
    } catch (e: IOException) {
        null
    }

    /** On the worker thread. */
    private fun close() {
        original?.decoder?.recycle()
        original = null
    }

    /** How a stored picture maps to the picture as shown. */
    internal class ShownGeometry(val toShown: Matrix, val toStored: Matrix, val width: Float, val height: Float)

    companion object {
        /** Up to this the loaded picture is sharp enough (one of its pixels on about one screen pixel). */
        private const val SHARP_ENOUGH = 1.05f

        /** The picture must hold still this long before the part is read. */
        private const val SETTLE_MS = 120L

        /** Read this much more around the visible part (of its width and height, on each side). */
        private const val MARGIN = 0.05f

        /** At most this many pixels in memory for the part (about 64 MB). */
        private const val MAX_PART_PIXELS = 16_000_000f

        /** Shapes this close count as the same picture (sizes are rounded when loading). */
        private const val SAME_SHAPE_TOLERANCE = 0.02f

        /** Until the screen tells: what every device can draw at once. */
        private const val DEFAULT_MAX_BITMAP_SIDE = 4096

        /** One thread for all pictures: parts are read one after the other. */
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "picture-details").apply { isDaemon = true }
        }

        /**
         * Every how many pixels of the original one is read: a power of two, so that the part
         * is at least as sharp as the screen, but not bigger than needed (and than memory and
         * the screen's biggest drawable picture, [maxSide] pixels a side, allow).
         */
        internal fun sampleSizeFor(originalPerScreenPixel: Float, width: Float, height: Float, maxSide: Int): Int {
            var sampleSize = 1
            while (sampleSize * 2 <= originalPerScreenPixel) sampleSize *= 2
            while ((width / sampleSize) * (height / sampleSize) > MAX_PART_PIXELS ||
                width / sampleSize > maxSide || height / sampleSize > maxSide
            ) {
                sampleSize *= 2
            }
            return sampleSize
        }

        /**
         * Whether an original of [originalWidth] x [originalHeight] (as shown) gives more details
         * than the loaded picture of [loadedWidth] x [loadedHeight]: the same picture (same shape),
         * just bigger.
         */
        internal fun addsDetails(originalWidth: Float, originalHeight: Float, loadedWidth: Int, loadedHeight: Int): Boolean {
            if (originalWidth <= 0f || originalHeight <= 0f || loadedWidth <= 0 || loadedHeight <= 0) return false
            val originalShape = originalWidth / originalHeight
            val loadedShape = loadedWidth.toFloat() / loadedHeight
            if (kotlin.math.abs(originalShape / loadedShape - 1f) > SAME_SHAPE_TOLERANCE) return false
            return originalWidth >= loadedWidth * SHARP_ENOUGH
        }

        /** The stored picture of [width] x [height] turned and mirrored by its EXIF [orientation]. */
        internal fun shownGeometry(orientation: Int, width: Float, height: Float): ShownGeometry {
            val toShown = ImageOrientation.matrixFor(orientation) ?: Matrix()
            // Turning moves the picture away from (0, 0): move it back.
            val bounds = RectF(0f, 0f, width, height)
            toShown.mapRect(bounds)
            toShown.postTranslate(-bounds.left, -bounds.top)
            val toStored = Matrix()
            toShown.invert(toStored)
            return ShownGeometry(toShown, toStored, bounds.width(), bounds.height())
        }
    }
}
