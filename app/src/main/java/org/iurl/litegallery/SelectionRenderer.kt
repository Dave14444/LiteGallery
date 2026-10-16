package org.iurl.litegallery

import android.content.res.ColorStateList
import android.view.View
import com.google.android.material.imageview.ShapeableImageView
import org.iurl.litegallery.theme.ThemeColorResolver

/** Draws the "selected" look of a media item (highlight stroke around the thumbnail). */
object SelectionRenderer {

    private const val SELECTION_STROKE_DP = 2f

    fun apply(root: View, selected: Boolean) {
        val thumbnail = root.findViewById<ShapeableImageView>(R.id.thumbnailImageView)

        root.isSelected = selected
        root.alpha = 1f
        if (selected) {
            val color = ThemeColorResolver.resolveColor(
                root.context,
                com.google.android.material.R.attr.colorPrimary
            )
            thumbnail?.strokeColor = ColorStateList.valueOf(color)
            thumbnail?.strokeWidth = SELECTION_STROKE_DP * root.resources.displayMetrics.density
        } else {
            thumbnail?.strokeColor = null
            thumbnail?.strokeWidth = 0f
        }
    }
}
