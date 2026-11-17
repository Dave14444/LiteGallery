package org.iurl.litegallery

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.iurl.litegallery.MediaAdapter.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FolderAdapterTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_LiteGallery
    )

    private val folder = MediaFolder(
        name = "Urlaub",
        path = "/storage/emulated/0/DCIM/Urlaub",
        itemCount = 109,
        totalSizeBytes = 412L * 1024 * 1024,
        latestDateModifiedMs = 1_700_000_000_000L,
        isSubFolder = true
    )

    /** Creates and binds the row or card for [mode], as the list would. */
    private fun shown(mode: ViewMode): FolderAdapter.Holder {
        val adapter = FolderAdapter(onFolderClick = {}, onFolderLongClick = {})
        adapter.viewMode = mode
        adapter.submitList(listOf(folder))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        return holder
    }

    private fun texts(view: View): List<String> {
        if (view is TextView) return if (view.visibility == View.VISIBLE) listOf(view.text.toString()) else emptyList()
        if (view !is android.view.ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }

    @Test
    fun theTileViews_showCards() {
        assertTrue(shown(ViewMode.GRID) is FolderAdapter.CardHolder)
        assertTrue(shown(ViewMode.LARGE_GRID) is FolderAdapter.CardHolder)
    }

    @Test
    fun theListView_showsARowWithNameAndCount() {
        val holder = shown(ViewMode.LIST)
        assertTrue(holder is FolderAdapter.ListHolder)
        assertEquals(listOf("Urlaub", "109"), texts(holder.itemView))
    }

    @Test
    fun theDetailView_showsCountSizeAndDate() {
        val holder = shown(ViewMode.DETAILED)
        assertTrue(holder is FolderAdapter.DetailedHolder)
        val shownTexts = texts(holder.itemView)
        assertEquals("Urlaub", shownTexts[0])
        assertEquals(context.getString(R.string.items_count, 109), shownTexts[1])
        assertEquals(4, shownTexts.size)
    }

    @Test
    fun anUnknownCount_isNotShown() {
        val adapter = FolderAdapter(onFolderClick = {}, onFolderLongClick = {})
        adapter.viewMode = ViewMode.DETAILED
        adapter.submitList(listOf(folder.copy(itemCount = -1, totalSizeBytes = 0L, latestDateModifiedMs = 0L)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(listOf("Urlaub"), texts(holder.itemView))
    }

    @Test
    fun switchingTheView_redrawsTheFolders() {
        val adapter = FolderAdapter(onFolderClick = {}, onFolderLongClick = {})
        adapter.submitList(listOf(folder, folder.copy(name = "B", path = "/b")))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        var changed = 0
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                changed += itemCount
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changed += itemCount
            }
        })
        val before = adapter.getItemViewType(0)
        adapter.viewMode = ViewMode.LIST
        assertEquals(2, changed)
        assertTrue(before != adapter.getItemViewType(0))
    }
}
