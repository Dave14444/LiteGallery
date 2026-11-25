package org.iurl.litegallery

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.RangeSlider
import org.iurl.litegallery.databinding.DialogPhotoFilterBinding
import java.util.Locale

/**
 * The filter of the folder screen: only pictures with a star, and by camera, lens, focal length,
 * aperture and ISO. Offers only what occurs in the folder.
 */
object PhotoFilterDialog {

    fun show(
        activity: AppCompatActivity,
        current: PhotoFilter,
        choices: PhotoFilter.Choices,
        starredCount: Int,
        onApply: (PhotoFilter) -> Unit
    ) {
        val binding = DialogPhotoFilterBinding.inflate(LayoutInflater.from(activity))
        binding.starredSwitch.text = activity.getString(R.string.filter_starred_only_count, starredCount)
        binding.starredSwitch.isChecked = current.starredOnly

        val hasData = choices.cameras.isNotEmpty() || choices.lenses.isNotEmpty() ||
            choices.focalMm.isNotEmpty() || choices.fNumber.isNotEmpty() || choices.iso.isNotEmpty()
        binding.noDataText.visibility = if (hasData) View.GONE else View.VISIBLE

        fillChips(activity, binding.cameraTitle, binding.cameraChips, choices.cameras, current.cameras)
        fillChips(activity, binding.lensTitle, binding.lensChips, choices.lenses, current.lenses)

        val focal = RangeChoice(binding.focalTitle, binding.focalSlider, choices.focalMm, activity.getString(R.string.exif_focal_length)) {
            String.format(Locale.getDefault(), "%.0f mm", it)
        }
        focal.select(current.focalMm?.let { it.start to it.endInclusive })
        val aperture = RangeChoice(binding.apertureTitle, binding.apertureSlider, choices.fNumber, activity.getString(R.string.exif_aperture)) {
            String.format(Locale.getDefault(), "f/%.1f", it)
        }
        aperture.select(current.fNumber?.let { it.start to it.endInclusive })
        val iso = RangeChoice(binding.isoTitle, binding.isoSlider, choices.iso.map { it.toDouble() }, activity.getString(R.string.exif_iso)) {
            "ISO ${it.toInt()}"
        }
        iso.select(current.iso?.let { it.first.toDouble() to it.last.toDouble() })

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.filter_title)
            .setView(binding.root)
            .setPositiveButton(R.string.filter_apply) { _, _ ->
                onApply(
                    PhotoFilter(
                        starredOnly = binding.starredSwitch.isChecked,
                        cameras = checked(binding.cameraChips),
                        lenses = checked(binding.lensChips),
                        focalMm = focal.selected()?.let { it.first..it.second },
                        fNumber = aperture.selected()?.let { it.first..it.second },
                        iso = iso.selected()?.let { it.first.toInt()..it.second.toInt() }
                    )
                )
            }
            .setNeutralButton(R.string.filter_reset) { _, _ -> onApply(PhotoFilter()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
    }

    private fun fillChips(
        activity: AppCompatActivity,
        title: TextView,
        group: ChipGroup,
        values: Map<String, Int>,
        selected: Set<String>
    ) {
        if (values.isEmpty()) {
            title.visibility = View.GONE
            group.visibility = View.GONE
            return
        }
        val inflater = LayoutInflater.from(activity)
        // Most pictures first.
        values.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (name, count) ->
                val chip = inflater.inflate(R.layout.item_filter_chip, group, false) as Chip
                chip.text = activity.getString(R.string.filter_choice_count, name, count)
                chip.tag = name
                chip.isChecked = name in selected
                group.addView(chip)
            }
    }

    private fun checked(group: ChipGroup): Set<String> =
        (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filterIsInstance<Chip>()
            .filter { it.isChecked }
            .mapNotNull { it.tag as? String }
            .toSet()

    /**
     * A range of the values that occur (e.g. the focal lengths): the slider moves from one
     * occurring value to the next. The whole range means "no condition".
     */
    private class RangeChoice(
        private val title: TextView,
        private val slider: RangeSlider,
        private val values: List<Double>,
        private val label: String,
        private val format: (Double) -> String
    ) {
        init {
            if (values.size < 2) {
                title.visibility = View.GONE
                slider.visibility = View.GONE
            } else {
                slider.valueFrom = 0f
                slider.valueTo = (values.size - 1).toFloat()
                slider.stepSize = 1f
                slider.values = listOf(0f, (values.size - 1).toFloat())
                slider.addOnChangeListener { _, _, _ -> showTitle() }
                showTitle()
            }
        }

        fun select(range: Pair<Double, Double>?) {
            if (values.size < 2 || range == null) return
            val from = values.indexOfFirst { it >= range.first - 0.01 }.takeIf { it >= 0 } ?: 0
            val to = values.indexOfLast { it <= range.second + 0.01 }.takeIf { it >= from } ?: (values.size - 1)
            slider.values = listOf(from.toFloat(), to.toFloat())
            showTitle()
        }

        /** The chosen values, or null if all are chosen. */
        fun selected(): Pair<Double, Double>? {
            if (values.size < 2) return null
            val from = slider.values[0].toInt().coerceIn(values.indices)
            val to = slider.values[1].toInt().coerceIn(values.indices)
            if (from == 0 && to == values.size - 1) return null
            return values[from] to values[to]
        }

        private fun showTitle() {
            val from = values[slider.values[0].toInt().coerceIn(values.indices)]
            val to = values[slider.values[1].toInt().coerceIn(values.indices)]
            title.text = if (from == to) "$label: ${format(from)}" else "$label: ${format(from)} – ${format(to)}"
        }
    }
}
