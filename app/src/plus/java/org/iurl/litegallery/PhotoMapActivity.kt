package org.iurl.litegallery

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.iurl.litegallery.databinding.ActivityPhotoMapBinding
import org.iurl.litegallery.theme.ThemeVariant

/**
 * The pictures the folder screen shows (with its filter) on an OpenStreetMap map, by where they
 * were taken. A tap on a picture's dot opens it; a tap on a group zooms in (or, if they were all
 * taken at the same spot, lists them).
 *
 * The map tiles come from openstreetmap.org: it learns which part of the world is looked at, but
 * nothing about the pictures.
 */
class PhotoMapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_PATH = "folder_path"
        const val EXTRA_TITLE = "title"
        private const val STATE_VIEW = "map_view"
    }

    private lateinit var binding: ActivityPhotoMapBinding
    private var folderPath = ""
    private var items: List<MediaItemSkeleton> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyPackTheme(this, ThemeVariant.NoActionBar)
        super.onCreate(savedInstanceState)
        ThemeHelper.captureCustomThemeGeneration(this)
        binding = ActivityPhotoMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH).orEmpty()
        binding.toolbar.title = getString(R.string.map_title_folder, intent.getStringExtra(EXTRA_TITLE).orEmpty())
        binding.toolbar.setNavigationOnClickListener { finish() }
        ThemeHelper.applyRuntimeCustomColors(this)

        // The pictures as the folder screen shows them now (sorted, filtered).
        items = FolderMediaRepository.getSkeleton(folderPath)?.items.orEmpty()
        if (items.isEmpty()) {
            finish()
            return
        }
        val savedView = savedInstanceState?.getDoubleArray(STATE_VIEW)
        binding.mapView.onMarkersTapped = { points -> onMarkersTapped(points) }

        PhotoDataLoader.load(this, folderPath, items) { infos ->
            val points = items.mapIndexedNotNull { index, item ->
                val info = infos[item.path] ?: return@mapIndexedNotNull null
                val latitude = info.latitude ?: return@mapIndexedNotNull null
                val longitude = info.longitude ?: return@mapIndexedNotNull null
                TileMapView.Point(latitude, longitude, index)
            }
            binding.noPlacesText.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
            binding.mapView.setPoints(points)
            if (savedView != null) binding.mapView.restore(savedView) else binding.mapView.showAllPoints()
            binding.toolbar.subtitle = resources.getQuantityString(R.plurals.map_places_count, points.size, points.size)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) outState.putDoubleArray(STATE_VIEW, binding.mapView.state)
    }

    private fun onMarkersTapped(points: List<TileMapView.Point>) {
        if (points.size == 1) {
            openPicture(points.first().index)
            return
        }
        // A group: zoom in; if they were all taken at the same spot, choose from a list.
        if (binding.mapView.zoomInOn(points)) return
        val sorted = points.sortedBy { it.index }
        val names = sorted.map { items[it.index].name }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.map_places_count, sorted.size, sorted.size))
            .setItems(names) { _, which -> openPicture(sorted[which].index) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)
    }

    /** Opens the picture in the viewer, which then pages through the folder as shown. */
    private fun openPicture(index: Int) {
        val item = items.getOrNull(index) ?: return
        startActivity(
            Intent(this, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, item.path)
                .putExtra(MediaViewerActivity.EXTRA_FOLDER_PATH, folderPath)
                .putExtra(MediaViewerActivity.EXTRA_CURRENT_POSITION, index)
        )
    }
}
