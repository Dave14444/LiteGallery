package org.iurl.litegallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Cropping and rotating pictures at full resolution. The editor shows a reduced preview, but
 * the result is cut out of the original file ([BitmapRegionDecoder]), so nothing is lost
 * except what the crop removes. JPEG is saved with quality 100, PNG and WebP losslessly.
 */
object PhotoEditing {

    private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** EXIF values that are carried over to an edited JPEG. Orientation and size are set anew. */
    private val COPIED_EXIF_TAGS = listOf(
        "DateTime", "DateTimeOriginal", "DateTimeDigitized",
        "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
        "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
        "Make", "Model", "LensMake", "LensModel",
        "ExposureTime", "FNumber", "ExposureProgram", "ExposureBiasValue", "MeteringMode",
        "ISOSpeedRatings", "PhotographicSensitivity",
        "FocalLength", "FocalLengthIn35mmFilm", "Flash", "WhiteBalance",
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod",
        "Artist", "Copyright", "ImageDescription"
    )

    /** A picture opened for editing. */
    class Loaded(
        val file: File,
        /** EXIF orientation of the file (how the stored pixels must be turned to look upright). */
        val orientation: Int,
        /** Stored (not yet turned) size of the file. */
        val rawWidth: Int,
        val rawHeight: Int,
        /** Reduced preview, already turned upright. */
        val preview: Bitmap
    ) {
        val uprightWidth: Int get() = if (swapsAxes(orientation)) rawHeight else rawWidth
        val uprightHeight: Int get() = if (swapsAxes(orientation)) rawWidth else rawHeight
    }

    fun supports(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    /** Opens [file] and decodes a preview whose longer side is at most [maxPreviewSide] pixels. */
    fun load(file: File, maxPreviewSide: Int): Loaded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Cannot read the picture ${file.name}")
        }

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxPreviewSide) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Cannot decode the picture ${file.name}")

        val upright = ImageOrientation.apply(decoded, orientation)
        if (upright !== decoded) decoded.recycle()

        return Loaded(file, orientation, bounds.outWidth, bounds.outHeight, upright)
    }

    /**
     * Cuts [crop] (fractions 0..1 of the picture after turning it by [rotation] degrees clockwise)
     * out of the original and writes it to [output] (the extension decides the format).
     */
    fun render(loaded: Loaded, rotation: Int, crop: RectF, output: File, lastModified: Long) {
        val storedRect = storedRectFor(
            loaded.rawWidth, loaded.rawHeight, loaded.orientation, rotation, crop
        )

        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(loaded.file.absolutePath, false)
            ?: throw IOException("Cannot open the picture ${loaded.file.name}")
        try {
            var sampleSize = sampleSizeFor(storedRect.width().toLong() * storedRect.height())
            var decoded: Bitmap? = null
            var attempts = 0
            while (decoded == null) {
                try {
                    decoded = decodeAndTurn(decoder, storedRect, sampleSize, loaded.orientation, rotation)
                } catch (e: OutOfMemoryError) {
                    attempts++
                    if (attempts > 3) throw IOException("Not enough memory for this picture")
                    sampleSize *= 2
                }
            }
            val result: Bitmap = decoded ?: throw IOException("Cannot decode the picture")

            try {
                encode(result, output)
                if (isJpeg(output)) {
                    copyExif(loaded.file, output, result.width, result.height)
                }
            } finally {
                result.recycle()
            }
        } finally {
            decoder.recycle()
        }

        if (lastModified > 0L) output.setLastModified(lastModified)
    }

    /**
     * The part of the stored pixels (a [rawWidth] x [rawHeight] picture with the EXIF
     * [orientation]) that belongs to [crop], given as fractions 0..1 of the picture after it
     * was turned upright and then by [rotation] degrees clockwise.
     */
    internal fun storedRectFor(
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
        rotation: Int,
        crop: RectF
    ): Rect {
        val uprightWidth = if (swapsAxes(orientation)) rawHeight else rawWidth
        val uprightHeight = if (swapsAxes(orientation)) rawWidth else rawHeight
        val rotatedWidth = if (rotation % 180 == 0) uprightWidth else uprightHeight
        val rotatedHeight = if (rotation % 180 == 0) uprightHeight else uprightWidth
        val rotatedRect = RectF(
            crop.left * rotatedWidth,
            crop.top * rotatedHeight,
            crop.right * rotatedWidth,
            crop.bottom * rotatedHeight
        )

        // Map the crop area back from the rotated picture to the stored pixels of the file.
        val toRotated = Matrix()
        toRotated.setConcat(
            orientationMatrix(rotationOrientation(rotation), uprightWidth, uprightHeight),
            orientationMatrix(orientation, rawWidth, rawHeight)
        )
        val toStored = Matrix()
        if (!toRotated.invert(toStored)) throw IOException("Cannot map the crop area")
        val storedRectF = RectF()
        toStored.mapRect(storedRectF, rotatedRect)
        val storedRect = Rect(
            floor(storedRectF.left).toInt().coerceIn(0, rawWidth - 1),
            floor(storedRectF.top).toInt().coerceIn(0, rawHeight - 1),
            ceil(storedRectF.right).toInt().coerceIn(1, rawWidth),
            ceil(storedRectF.bottom).toInt().coerceIn(1, rawHeight)
        )
        if (storedRect.width() < 1 || storedRect.height() < 1) {
            throw IOException("Empty crop area")
        }
        return storedRect
    }

    /** Cuts out [rect] and turns it upright and by [rotation] degrees. */
    private fun decodeAndTurn(
        decoder: BitmapRegionDecoder,
        rect: Rect,
        sampleSize: Int,
        orientation: Int,
        rotation: Int
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val region = decoder.decodeRegion(rect, options)
            ?: throw IOException("Cannot decode the crop area")

        val uprightWidth = if (swapsAxes(orientation)) region.height else region.width
        val uprightHeight = if (swapsAxes(orientation)) region.width else region.height
        val matrix = Matrix()
        matrix.setConcat(
            orientationMatrix(rotationOrientation(rotation), uprightWidth, uprightHeight),
            orientationMatrix(orientation, region.width, region.height)
        )
        if (matrix.isIdentity) return region

        val turned = Bitmap.createBitmap(region, 0, 0, region.width, region.height, matrix, true)
        if (turned !== region) region.recycle()
        return turned
    }

    /** Largest reduction (1, 2, 4, ...) that keeps the work inside the free memory. */
    private fun sampleSizeFor(pixels: Long): Int {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        // 4 bytes per pixel, twice (cut-out plus turned copy), with some room to spare.
        val maxPixels = (free / 9).coerceAtLeast(4_000_000L)
        var sampleSize = 1
        while (pixels / (sampleSize.toLong() * sampleSize) > maxPixels) sampleSize *= 2
        return sampleSize
    }

    private fun encode(bitmap: Bitmap, output: File) {
        val format: Bitmap.CompressFormat = when (output.extension.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.JPEG
        }
        output.outputStream().use { stream ->
            if (!bitmap.compress(format, 100, stream)) {
                throw IOException("Could not encode the picture")
            }
        }
    }

    private fun isJpeg(file: File): Boolean = file.extension.lowercase().let { it == "jpg" || it == "jpeg" }

    /** Carries camera, date and location data over to the edited JPEG. Never fails the save. */
    private fun copyExif(source: File, target: File, width: Int, height: Int) {
        try {
            val from = ExifInterface(source.absolutePath)
            val to = ExifInterface(target.absolutePath)
            for (tag in COPIED_EXIF_TAGS) {
                from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            }
            // The pixels are already turned upright now.
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.setAttribute("PixelXDimension", width.toString())
            to.setAttribute("PixelYDimension", height.toString())
            to.saveAttributes()
        } catch (e: Exception) {
            android.util.Log.w("PhotoEditing", "Could not copy the EXIF data", e)
        }
    }

    private fun swapsAxes(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270

    private fun rotationOrientation(rotation: Int): Int = when (((rotation % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Matrix that maps the pixels of a [width] x [height] picture stored with the given EXIF
     * [orientation] to their place in the upright picture.
     */
    private fun orientationMatrix(orientation: Int, width: Int, height: Int): Matrix {
        val w = width.toFloat()
        val h = height.toFloat()
        val values: FloatArray? = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE ->
                floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 ->
                floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE ->
                floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 ->
                floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> null
        }
        val matrix = Matrix()
        if (values != null) matrix.setValues(values)
        return matrix
    }
}
