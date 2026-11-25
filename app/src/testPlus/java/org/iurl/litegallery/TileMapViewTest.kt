package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TileMapViewTest {

    @Test
    fun theWorld_spansZeroToOne() {
        assertEquals(0.0, TileMapView.lonToWorldX(-180.0), 1e-12)
        assertEquals(0.5, TileMapView.lonToWorldX(0.0), 1e-12)
        assertEquals(1.0, TileMapView.lonToWorldX(180.0), 1e-12)
        assertEquals(0.5, TileMapView.latToWorldY(0.0), 1e-12)
        // The edges of the map (about 85° north and south).
        assertEquals(0.0, TileMapView.latToWorldY(85.05112878), 1e-6)
        assertEquals(1.0, TileMapView.latToWorldY(-85.05112878), 1e-6)
    }

    @Test
    fun aPlace_landsOnTheRightTile() {
        // Darmstadt at zoom 10 is tile 536/347 (tile numbering of OpenStreetMap).
        val x = TileMapView.lonToWorldX(8.6512)
        val y = TileMapView.latToWorldY(49.8728)
        assertEquals(536, (x * 1024).toInt())
        assertEquals(347, (y * 1024).toInt())
    }

    @Test
    fun northIsUp() {
        assertTrue(TileMapView.latToWorldY(55.0) < TileMapView.latToWorldY(47.0))
    }
}
