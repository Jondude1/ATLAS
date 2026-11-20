package com.jondude.anzu

import com.jondude.anzu.overlay.OverlayTile
import com.jondude.anzu.overlay.TileKind
import com.jondude.anzu.overlay.TileLayout
import com.jondude.anzu.overlay.TileMode
import com.jondude.anzu.overlay.TileStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tile decisions: arrangement by count and mode, placement bounds, the cap, the chip text, stack semantics. */
class TileLayoutTest {

    @Test
    fun arrangementByCountAndMode() {
        // One tile is always the D3 picture-in-picture, whatever the mode.
        assertEquals(TileLayout.Arrangement.PIP, TileLayout.arrangement(0, TileMode.AUTO))
        assertEquals(TileLayout.Arrangement.PIP, TileLayout.arrangement(1, TileMode.AUTO))
        assertEquals(TileLayout.Arrangement.PIP, TileLayout.arrangement(1, TileMode.STACK))
        // Several: cascade by default (D5), the right-side stack only in STACK mode (D2).
        for (n in 2..5) {
            assertEquals(TileLayout.Arrangement.CASCADE, TileLayout.arrangement(n, TileMode.AUTO))
            assertEquals(TileLayout.Arrangement.STACK, TileLayout.arrangement(n, TileMode.STACK))
        }
    }

    @Test
    fun pipIsBottomEndAndCamerasAreSmaller() {
        val cam = TileLayout.placement(0, 1, TileMode.AUTO, TileKind.CAMERA)
        assertTrue(cam.fromBottom)
        assertEquals(0.42f, cam.wFrac, 0.001f)
        assertTrue("PiP hugs the end edge", cam.xFrac + cam.wFrac >= 0.95f)
        val text = TileLayout.placement(0, 1, TileMode.AUTO, TileKind.INFO)
        assertTrue(text.fromBottom)
        assertTrue("text PiP gets more width than a camera", text.wFrac > cam.wFrac)
    }

    @Test
    fun cascadeOffsetsDescendAndDriftStartward() {
        val ps = (0..3).map { TileLayout.placement(it, 4, TileMode.AUTO, TileKind.DATA) }
        for (i in 1..3) {
            assertTrue("each cascade tile sits lower", ps[i].yFrac > ps[i - 1].yFrac)
            assertTrue("each cascade tile drifts startward", ps[i].xFrac < ps[i - 1].xFrac)
        }
        assertTrue(ps.all { it.wFrac == 0.64f })
    }

    @Test
    fun stackIsARightHandColumn() {
        val ps = (0..3).map { TileLayout.placement(it, 4, TileMode.STACK, TileKind.DATA) }
        assertTrue("one shared x", ps.all { it.xFrac == ps[0].xFrac })
        assertTrue("column hugs the end edge", ps[0].xFrac + ps[0].wFrac >= 0.95f)
        for (i in 1..3) assertTrue("rows descend", ps[i].yFrac > ps[i - 1].yFrac)
    }

    @Test
    fun placementsStayOnScreenForEveryCountAndMode() {
        for (mode in TileMode.values()) for (n in 0..5) for (i in 0 until maxOf(n, 1)) {
            val p = TileLayout.placement(i, n, mode, TileKind.CAMERA)
            assertTrue("x on screen ($mode/$n/$i)", p.xFrac >= 0f && p.xFrac + p.wFrac <= 1.001f)
            assertTrue("y on screen ($mode/$n/$i)", p.yFrac >= 0f && p.yFrac <= 1f)
            assertTrue("width sane ($mode/$n/$i)", p.wFrac in 0.2f..0.9f)
        }
        val big = TileLayout.expandedPlacement()
        assertEquals(0.88f, big.wFrac, 0.001f)
        assertTrue(big.xFrac + big.wFrac <= 1f)
    }

    @Test
    fun chipTextNamesOneAndCountsSeveral() {
        val s = TileStack()
        assertNull(TileLayout.chipText(s.state()))
        s.putUp(OverlayTile.camera("camera.front_porch", "Front Porch"))
        assertEquals("ATLAS · SHOWING FRONT PORCH", TileLayout.chipText(s.state()))
        s.putUp(OverlayTile.data("House", listOf("All locked.")))
        assertEquals("ATLAS · SHOWING 2 ITEMS", TileLayout.chipText(s.state()))
    }

    @Test
    fun stackReplacesSameKindAndTitleInPlace() {
        val s = TileStack()
        s.putUp(OverlayTile.data("House", listOf("v1")))
        s.putUp(OverlayTile.info("News", listOf("n")))
        val st = s.putUp(OverlayTile.data("House", listOf("v2")))
        assertEquals(2, st.visible.size)
        assertEquals("House", st.visible[0].title) // slot did not move
        assertEquals(listOf("v2"), st.visible[0].lines)
        assertEquals(0, st.collapsedCount)
    }

    @Test
    fun stackCapCollapsesTheOldestIntoTheCount() {
        val s = TileStack()
        for (i in 1..5) s.putUp(OverlayTile.info("T$i", listOf("x")))
        var st = s.state()
        assertEquals(TileLayout.MAX_VISIBLE, st.visible.size)
        assertEquals(1, st.collapsedCount)
        assertEquals(5, st.total)
        assertEquals("T2", st.visible.first().title) // T1 collapsed, not T5
        assertEquals("ATLAS · SHOWING 5 ITEMS", TileLayout.chipText(st))
        s.putUp(OverlayTile.info("T6", listOf("x")))
        st = s.state()
        assertEquals(TileLayout.MAX_VISIBLE, st.visible.size)
        assertEquals(2, st.collapsedCount)
        // Replacing a visible tile while full changes nothing about the counts.
        s.putUp(OverlayTile.info("T6", listOf("y")))
        st = s.state()
        assertEquals(2, st.collapsedCount)
        assertEquals(listOf("y"), st.visible.last().lines)
        // Never more windows than the cap allows: tiles + the one chip.
        assertTrue(st.visible.size + 1 <= TileLayout.MAX_WINDOWS)
    }

    @Test
    fun stackClearAndClearAll() {
        val s = TileStack()
        val a = OverlayTile.info("A", listOf("1"))
        s.putUp(a)
        s.putUp(OverlayTile.info("B", listOf("2")))
        assertEquals(1, s.clear(a.id).visible.size)
        for (i in 1..5) s.putUp(OverlayTile.info("C$i", listOf("x")))
        assertTrue(s.state().collapsedCount > 0)
        val st = s.clearAll()
        assertEquals(0, st.visible.size)
        assertEquals(0, st.collapsedCount)
        assertEquals(0, st.total)
    }

    @Test
    fun cameraHeaderCarriesTheSnapshotLabelNeverLive() {
        val t = OverlayTile.camera("camera.front_porch", "Front Porch", sourceLabel = "SNAPSHOT 4:21 PM")
        assertEquals("FRONT PORCH · SNAPSHOT 4:21 PM", t.header())
        assertTrue(!t.header().contains("LIVE"))
    }
}
