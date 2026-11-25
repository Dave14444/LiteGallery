package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.iurl.litegallery.theme.ThemeColorResolver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * A plain map of OpenStreetMap tiles with markers: drag, fling, pinch and double tap to zoom.
 * Markers close to each other become one circle with their number.
 *
 * Positions are kept in "world" coordinates (Web Mercator, 0..1 across and down the whole
 * world), so the same numbers hold at every zoom. Tiles are loaded with Glide (kept in its cache
 * on the device, as the OpenStreetMap tile policy asks), named with this app as User-Agent.
 */
class TileMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** A marker: where, and which picture ([index]). */
    class Point(val latitude: Double, val longitude: Double, val index: Int) {
        internal val worldX = lonToWorldX(longitude)
        internal val worldY = latToWorldY(latitude)
    }

    /** Called with the pictures of a marker that was tapped (one, or all of a group). */
    var onMarkersTapped: ((List<Point>) -> Unit)? = null

    private val density = resources.displayMetrics.density

    /** A tile is drawn at 256 dp: the labels stay readable on any screen. */
    private val tileSizePx = TILE_PIXELS * density

    private var centerX = 0.5
    private var centerY = 0.5
    private var zoom = MIN_ZOOM

    private var points: List<Point> = emptyList()
    private var clusters: List<Cluster> = emptyList()
    private var clusteredAtZoom = Double.NaN

    private class Cluster(val worldX: Double, val worldY: Double, val points: List<Point>)

    // ---- tiles

    /** Tiles decoded for drawing (the files stay in Glide's cache on the device). */
    private val tiles = LruCache<String, Bitmap>(MAX_CACHED_TILES)
    private val loading = HashSet<String>()

    /** Tiles that could not be loaded (e.g. offline), and when: not asked for again right away. */
    private val failed = HashMap<String, Long>()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyTilePaint = Paint().apply { color = Color.rgb(0xE6, 0xE4, 0xE0) }
    private val srcRect = Rect()
    private val dstRect = RectF()

    // ---- markers and the attribution

    private val markerColor = ThemeColorResolver.resolveColor(
        context, androidx.appcompat.R.attr.colorPrimary, Color.rgb(0x3F, 0x51, 0xB5)
    )
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor }
    private val markerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        isFakeBoldText = true
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x33, 0x33, 0x33)
        textSize = 11f * density
    }
    private val attributionBackground = Paint().apply { color = Color.argb(0xCC, 0xFF, 0xFF, 0xFF) }
    private val attributionRect = RectF()
    private val attribution = context.getString(R.string.map_attribution)

    // ---- gestures

    private val scroller = OverScroller(context)
    private var flingLastX = 0
    private var flingLastY = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            moveBy(distanceX.toDouble(), distanceY.toDouble())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            flingLastX = 0
            flingLastY = 0
            scroller.fling(0, 0, -velocityX.toInt(), -velocityY.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAround(zoom + 1.0, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (attributionRect.contains(e.x, e.y)) {
                openCopyrightPage()
                return true
            }
            hitCluster(e.x, e.y)?.let { cluster ->
                onMarkersTapped?.invoke(cluster.points)
                return true
            }
            return false
        }
    })

    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAround(zoom + log2(detector.scaleFactor.toDouble()), detector.focusX, detector.focusY)
            return true
        }
    })

    // ---------------------------------------------------------------- what is shown

    fun setPoints(newPoints: List<Point>) {
        points = newPoints
        clusteredAtZoom = Double.NaN
        invalidate()
    }

    /** Shows all [points] (or the world, if there are none). */
    fun showAllPoints() {
        if (width == 0 || height == 0) {
            post { showAllPoints() }
            return
        }
        if (points.isEmpty()) {
            setView(0.5, 0.5, MIN_ZOOM)
            return
        }
        val left = points.minOf { it.worldX }
        val right = points.maxOf { it.worldX }
        val top = points.minOf { it.worldY }
        val bottom = points.maxOf { it.worldY }
        val margin = 48 * density
        val spanX = (right - left).coerceAtLeast(1e-9)
        val spanY = (bottom - top).coerceAtLeast(1e-9)
        val fit = min((width - 2 * margin) / (spanX * tileSizePx), (height - 2 * margin) / (spanY * tileSizePx))
        val targetZoom = log2(fit).coerceIn(MIN_ZOOM, SINGLE_POINT_ZOOM)
        setView((left + right) / 2, (top + bottom) / 2, targetZoom)
    }

    /** Zooms in on [group] (e.g. a tapped group of markers). Returns false if that is not possible any more. */
    fun zoomInOn(group: List<Point>): Boolean {
        if (zoom >= MAX_ZOOM - 0.01) return false
        val left = group.minOf { it.worldX }
        val right = group.maxOf { it.worldX }
        val top = group.minOf { it.worldY }
        val bottom = group.maxOf { it.worldY }
        // All at the same spot: no zooming separates them.
        if ((right - left) * worldSize(MAX_ZOOM) < 2 && (bottom - top) * worldSize(MAX_ZOOM) < 2) return false
        val margin = 64 * density
        val fit = min(
            (width - 2 * margin) / ((right - left).coerceAtLeast(1e-12) * tileSizePx),
            (height - 2 * margin) / ((bottom - top).coerceAtLeast(1e-12) * tileSizePx)
        )
        val targetZoom = log2(fit).coerceIn(zoom + 1.0, MAX_ZOOM)
        setView((left + right) / 2, (top + bottom) / 2, targetZoom)
        return true
    }

    /** Center and zoom, e.g. to keep them when the screen is turned. */
    val state: DoubleArray get() = doubleArrayOf(centerX, centerY, zoom)

    fun restore(state: DoubleArray) {
        if (state.size == 3) setView(state[0], state[1], state[2])
    }

    private fun setView(x: Double, y: Double, z: Double) {
        centerX = x.coerceIn(0.0, 1.0)
        centerY = y.coerceIn(0.0, 1.0)
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    // ---------------------------------------------------------------- moving

    private fun worldSize(z: Double = zoom): Double = tileSizePx * 2.0.pow(z)

    private fun moveBy(dx: Double, dy: Double) {
        val size = worldSize()
        centerX = (centerX + dx / size).coerceIn(0.0, 1.0)
        centerY = (centerY + dy / size).coerceIn(0.0, 1.0)
        invalidate()
    }

    /** Zooms to [newZoom] keeping the spot under the finger ([fx], [fy]) where it is. */
    private fun zoomAround(newZoom: Double, fx: Float, fy: Float) {
        val target = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val before = worldSize()
        val wx = centerX + (fx - width / 2.0) / before
        val wy = centerY + (fy - height / 2.0) / before
        zoom = target
        val after = worldSize()
        centerX = (wx - (fx - width / 2.0) / after).coerceIn(0.0, 1.0)
        centerY = (wy - (fy - height / 2.0) / after).coerceIn(0.0, 1.0)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaling.onTouchEvent(event)
        if (!scaling.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveBy((scroller.currX - flingLastX).toDouble(), (scroller.currY - flingLastY).toDouble())
            flingLastX = scroller.currX
            flingLastY = scroller.currY
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTiles(canvas)
        drawMarkers(canvas)
        drawAttribution(canvas)
    }

    private fun drawTiles(canvas: Canvas) {
        val tileZoom = floor(zoom).toInt().coerceIn(0, MAX_TILE_ZOOM)
        val count = 1 shl tileZoom
        val size = worldSize()
        val tileSide = size / count
        val originX = width / 2.0 - centerX * size
        val originY = height / 2.0 - centerY * size
        val firstX = floor(-originX / tileSide).toInt().coerceAtLeast(0)
        val firstY = floor(-originY / tileSide).toInt().coerceAtLeast(0)
        val lastX = floor((width - originX) / tileSide).toInt().coerceAtMost(count - 1)
        val lastY = floor((height - originY) / tileSide).toInt().coerceAtMost(count - 1)

        canvas.drawColor(emptyTilePaint.color)
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                dstRect.set(
                    (originX + tx * tileSide).toFloat(),
                    (originY + ty * tileSide).toFloat(),
                    (originX + (tx + 1) * tileSide).toFloat(),
                    (originY + (ty + 1) * tileSide).toFloat()
                )
                val bitmap = tiles.get(key(tileZoom, tx, ty))
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, dstRect, tilePaint)
                } else {
                    request(tileZoom, tx, ty)
                    drawFromCoarserTile(canvas, tileZoom, tx, ty)
                }
            }
        }
    }

    /** While a tile loads: the matching part of a tile of a lower zoom, if there is one. */
    private fun drawFromCoarserTile(canvas: Canvas, tileZoom: Int, tx: Int, ty: Int) {
        for (up in 1..3) {
            val z = tileZoom - up
            if (z < 0) return
            val parent = tiles.get(key(z, tx shr up, ty shr up)) ?: continue
            val part = parent.width shr up
            if (part <= 0) return
            val px = (tx - ((tx shr up) shl up)) * part
            val py = (ty - ((ty shr up) shl up)) * part
            srcRect.set(px, py, px + part, py + part)
            canvas.drawBitmap(parent, srcRect, dstRect, tilePaint)
            return
        }
    }

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private fun request(z: Int, x: Int, y: Int) {
        val key = key(z, x, y)
        if (key in loading || loading.size >= MAX_LOADING) return
        failed[key]?.let { at -> if (SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS) return }
        loading.add(key)
        val appContext = context.applicationContext
        val url = GlideUrl(
            "https://tile.openstreetmap.org/$z/$x/$y.png",
            LazyHeaders.Builder().setHeader("User-Agent", userAgent()).build()
        )
        tileLoader.execute {
            // Glide keeps the file in its cache on the device; drawn from a copy of our own.
            val bitmap = try {
                val request = Glide.with(appContext).downloadOnly().load(url).submit()
                try {
                    BitmapFactory.decodeFile(request.get(TILE_TIMEOUT_SECONDS, TimeUnit.SECONDS).path)
                } finally {
                    Glide.with(appContext).clear(request)
                }
            } catch (e: Exception) {
                null
            }
            post {
                loading.remove(key)
                if (bitmap != null) {
                    failed.remove(key)
                    tiles.put(key, bitmap)
                    invalidate()
                } else {
                    failed[key] = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun userAgent(): String =
        "LiteGallery/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"

    private fun drawMarkers(canvas: Canvas) {
        if (points.isEmpty()) return
        if (clusteredAtZoom.isNaN() || kotlin.math.abs(clusteredAtZoom - zoom) > 0.2) {
            clusters = cluster(points, worldSize(), CLUSTER_RADIUS_DP * density)
            clusteredAtZoom = zoom
        }
        val size = worldSize()
        for (cluster in clusters) {
            val x = (width / 2.0 + (cluster.worldX - centerX) * size).toFloat()
            val y = (height / 2.0 + (cluster.worldY - centerY) * size).toFloat()
            if (x < -50 * density || y < -50 * density || x > width + 50 * density || y > height + 50 * density) continue
            if (cluster.points.size == 1) {
                canvas.drawCircle(x, y, 8f * density, markerPaint)
                canvas.drawCircle(x, y, 8f * density, markerBorderPaint)
            } else {
                val radius = (13f + min(cluster.points.size, 999).toString().length * 3f) * density
                canvas.drawCircle(x, y, radius, markerPaint)
                canvas.drawCircle(x, y, radius, markerBorderPaint)
                canvas.drawText(
                    cluster.points.size.toString(),
                    x,
                    y - (countPaint.descent() + countPaint.ascent()) / 2,
                    countPaint
                )
            }
        }
    }

    private fun hitCluster(x: Float, y: Float): Cluster? {
        val size = worldSize()
        val reach = 24 * density
        return clusters
            .map { cluster ->
                val cx = width / 2.0 + (cluster.worldX - centerX) * size
                val cy = height / 2.0 + (cluster.worldY - centerY) * size
                cluster to ((cx - x) * (cx - x) + (cy - y) * (cy - y))
            }
            .filter { it.second <= reach * reach * 2 }
            .minByOrNull { it.second }
            ?.first
    }

    /** OpenStreetMap asks for this note on every map made from its data. */
    private fun drawAttribution(canvas: Canvas) {
        val padding = 4 * density
        val textWidth = attributionPaint.measureText(attribution)
        val bottom = height.toFloat()
        val right = width.toFloat()
        attributionRect.set(
            right - textWidth - 2 * padding,
            bottom - attributionPaint.textSize - 2 * padding,
            right,
            bottom
        )
        canvas.drawRect(attributionRect, attributionBackground)
        canvas.drawText(attribution, attributionRect.left + padding, bottom - padding - attributionPaint.descent(), attributionPaint)
    }

    private fun openCopyrightPage() {
        try {
            AppLock.leavingForOtherApp()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TILE_PIXELS = 256
        private const val MIN_ZOOM = 2.0
        private const val MAX_ZOOM = 19.0
        private const val MAX_TILE_ZOOM = 19

        /** Zoom for a single place (streets and houses visible). */
        private const val SINGLE_POINT_ZOOM = 16.0

        private const val MAX_CACHED_TILES = 80
        private const val MAX_LOADING = 12
        private const val TILE_TIMEOUT_SECONDS = 20L
        private const val RETRY_AFTER_MS = 30_000L

        /** Loads tiles for all maps (a few at a time, as the tile servers ask). */
        private val tileLoader: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "map-tiles").apply { isDaemon = true }
        }
        private const val CLUSTER_RADIUS_DP = 36f

        internal fun lonToWorldX(longitude: Double): Double = (longitude + 180.0) / 360.0

        internal fun latToWorldY(latitude: Double): Double {
            val lat = latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
            return (1.0 - ln(tan(lat) + 1.0 / kotlin.math.cos(lat)) / PI) / 2.0
        }

        /**
         * Groups points that would be closer than [radiusPx] on screen (in a grid of that size,
         * at a map [worldSizePx] wide): one circle per cell, at the middle of its points.
         */
        private fun cluster(points: List<Point>, worldSizePx: Double, radiusPx: Float): List<Cluster> {
            val cells = LinkedHashMap<Long, MutableList<Point>>()
            for (point in points) {
                val cx = floor(point.worldX * worldSizePx / radiusPx).toLong()
                val cy = floor(point.worldY * worldSizePx / radiusPx).toLong()
                cells.getOrPut((cx shl 32) xor (cy and 0xFFFFFFFFL)) { mutableListOf() }.add(point)
            }
            return cells.values.map { group ->
                Cluster(group.sumOf { it.worldX } / group.size, group.sumOf { it.worldY } / group.size, group)
            }
        }
    }
}
