package org.iurl.litegallery

/**
 * Which pictures of a folder are shown: only those with a star, and/or by their photo data
 * (camera, lens, focal length, aperture, ISO). Empty sets and null ranges let everything pass.
 */
data class PhotoFilter(
    val starredOnly: Boolean = false,
    val cameras: Set<String> = emptySet(),
    val lenses: Set<String> = emptySet(),
    val focalMm: ClosedFloatingPointRange<Double>? = null,
    val fNumber: ClosedFloatingPointRange<Double>? = null,
    val iso: IntRange? = null
) {
    val isEmpty: Boolean get() = !starredOnly && !needsPhotoData

    /** Whether the photo data of the pictures are needed to apply it. */
    val needsPhotoData: Boolean
        get() = cameras.isNotEmpty() || lenses.isNotEmpty() || focalMm != null || fNumber != null || iso != null

    /** How many conditions are set (shown on the filter chip). */
    val activeCount: Int
        get() = listOf(
            starredOnly,
            cameras.isNotEmpty(),
            lenses.isNotEmpty(),
            focalMm != null,
            fNumber != null,
            iso != null
        ).count { it }

    /**
     * Whether a file passes. [info] is its photo data (null: none known, e.g. a video): a
     * condition on photo data lets only pictures pass that have the value asked for.
     */
    fun matches(info: PhotoInfo?, starred: Boolean): Boolean {
        if (starredOnly && !starred) return false
        if (!needsPhotoData) return true
        if (info == null) return false
        if (cameras.isNotEmpty() && (info.camera == null || info.camera !in cameras)) return false
        if (lenses.isNotEmpty() && (info.lens == null || info.lens !in lenses)) return false
        focalMm?.let { range -> if (info.focalMm == null || !range.containsRounded(info.focalMm)) return false }
        fNumber?.let { range -> if (info.fNumber == null || !range.containsRounded(info.fNumber)) return false }
        iso?.let { range -> if (info.iso == null || info.iso !in range) return false }
        return true
    }

    /** Values from EXIF are fractions (e.g. 5.6 as 56/10): compared with a little room. */
    private fun ClosedFloatingPointRange<Double>.containsRounded(value: Double): Boolean =
        value >= start - 0.01 && value <= endInclusive + 0.01

    companion object {
        /** The values that occur in [infos], for the choices of the filter. */
        fun choices(infos: Collection<PhotoInfo>): Choices = Choices(
            cameras = infos.mapNotNull { it.camera }.groupingBy { it }.eachCount(),
            lenses = infos.mapNotNull { it.lens }.groupingBy { it }.eachCount(),
            focalMm = infos.mapNotNull { it.focalMm?.roundTo(1) }.distinct().sorted(),
            fNumber = infos.mapNotNull { it.fNumber?.roundTo(1) }.distinct().sorted(),
            iso = infos.mapNotNull { it.iso }.distinct().sorted()
        )

        private fun Double.roundTo(decimals: Int): Double {
            var factor = 1.0
            repeat(decimals) { factor *= 10.0 }
            return kotlin.math.round(this * factor) / factor
        }
    }

    /** The values found in a folder: names with how many pictures, and the sorted numbers. */
    data class Choices(
        val cameras: Map<String, Int>,
        val lenses: Map<String, Int>,
        val focalMm: List<Double>,
        val fNumber: List<Double>,
        val iso: List<Int>
    )
}
