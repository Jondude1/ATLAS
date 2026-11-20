package com.jondude.anzu

import com.jondude.anzu.assist.Holodeck
import com.jondude.anzu.assist.Hub
import com.jondude.anzu.overlay.BriefDeck
import com.jondude.anzu.overlay.DeckCard
import com.jondude.anzu.overlay.OverlayTile
import com.jondude.anzu.overlay.TileLayout
import com.jondude.anzu.overlay.TileMode
import com.jondude.anzu.overlay.TilePositions
import com.jondude.anzu.overlay.TileStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v1.2.19 interaction layer's pure semantics: pin, grow/restore, positions, deck, Holodeck tile. */
class TileInteractionTest {

    // ---- pin ----

    @Test
    fun clearAllKeepsPinnedTiles() {
        val s = TileStack()
        val a = OverlayTile.info("A", listOf("1"))
        val b = OverlayTile.info("B", listOf("2"))
        s.putUp(a); s.putUp(b)
        assertTrue(s.togglePin(a.id))
        val st = s.clearAll()
        assertEquals(listOf("A"), st.visible.map { it.title })
        assertTrue(st.isPinned(a.id))
        assertEquals(1, st.total)
    }

    @Test
    fun clearRefusesAPinnedTileUntilUnpinned() {
        val s = TileStack()
        val a = OverlayTile.info("A", listOf("1"))
        s.putUp(a)
        s.togglePin(a.id)
        assertEquals(1, s.clear(a.id).visible.size) // pinned: stays
        assertFalse(s.togglePin(a.id)) // unpin
        assertEquals(0, s.clear(a.id).visible.size)
    }

    @Test
    fun pinSurvivesReplacementAndRestore() {
        val s = TileStack()
        val a = OverlayTile.data("House", listOf("v1"))
        s.putUp(a)
        s.togglePin(a.id)
        s.putUp(OverlayTile.data("House", listOf("v2"))) // same identity, new contents
        assertTrue(s.state().isPinned(a.id))
        assertEquals(listOf("v2"), s.state().visible[0].lines)
        // Persistence round-trip: a fresh stack re-arms the pin before the tile returns.
        val s2 = TileStack()
        s2.restorePins(s.pinnedIds())
        s2.putUp(OverlayTile.data("House", listOf("v3")))
        assertTrue(s2.state().isPinned(a.id))
        assertEquals(1, s2.clearAll().visible.size)
    }

    @Test
    fun capCollapseSkipsPinnedTiles() {
        val s = TileStack()
        val first = OverlayTile.info("T1", listOf("x"))
        s.putUp(first)
        s.togglePin(first.id)
        for (i in 2..5) s.putUp(OverlayTile.info("T$i", listOf("x")))
        val st = s.state()
        assertEquals(TileLayout.MAX_VISIBLE, st.visible.size)
        assertEquals("T1", st.visible.first().title) // pinned T1 was NOT collapsed; T2 was
        assertEquals(1, st.collapsedCount)
    }

    // ---- grow / restore ----

    @Test
    fun growAndShrinkRestoreTheExactLayout() {
        val s = TileStack()
        s.putUp(OverlayTile.data("House", listOf("h")))
        s.putUp(OverlayTile.info("News", listOf("n")))
        s.putUp(OverlayTile.camera("camera.garage", "Garage"))
        val before = s.state()
        val placementsBefore = before.visible.mapIndexed { i, t ->
            TileLayout.placement(i, before.visible.size, before.mode, t.kind)
        }
        assertTrue(s.grow(before.visible[1].id))
        assertEquals(before.visible[1].id, s.state().grownId)
        assertEquals(before.visible[1], s.state().grown)
        assertTrue(s.shrink())
        val after = s.state()
        assertNull(after.grownId)
        assertEquals(before.visible.map { it.id }, after.visible.map { it.id })
        val placementsAfter = after.visible.mapIndexed { i, t ->
            TileLayout.placement(i, after.visible.size, after.mode, t.kind)
        }
        assertEquals(placementsBefore, placementsAfter) // restored exactly, by construction
        assertFalse(s.shrink()) // nothing grown now
        assertFalse(s.grow("info:not-up"))
    }

    @Test
    fun heroPlacementAndCollapsedChip() {
        val hero = TileLayout.heroPlacement()
        assertEquals(0.92f, hero.wFrac, 0.001f)
        assertTrue(hero.xFrac + hero.wFrac <= 1f)
        assertEquals("+2", TileLayout.collapsedChipText(2))
    }

    // ---- position memory ----

    @Test
    fun positionStoreRecallAndClamp() {
        val p = TilePositions()
        assertNull(p.recall("data:house"))
        p.remember("data:house", 0.25f, 0.5f)
        assertEquals(0.25f to 0.5f, p.recall("data:house"))
        p.remember("data:house", 1.4f, -0.2f) // clamped onto the screen
        assertEquals(1f to 0f, p.recall("data:house"))
        p.forget("data:house")
        assertNull(p.recall("data:house"))
    }

    @Test
    fun positionEvictionIsOldestFirst() {
        val p = TilePositions(cap = 3)
        p.remember("a", 0.1f, 0.1f)
        p.remember("b", 0.2f, 0.2f)
        p.remember("c", 0.3f, 0.3f)
        p.remember("a", 0.4f, 0.4f) // touching a makes it newest
        p.remember("d", 0.5f, 0.5f) // evicts b, the eldest untouched
        assertNull(p.recall("b"))
        assertEquals(3, p.size())
        assertEquals(0.4f to 0.4f, p.recall("a"))
    }

    @Test
    fun positionSerializeRoundTrip() {
        val p = TilePositions()
        p.remember("camera:front porch", 0.55f, 0.6f)
        p.remember("data:house", 0.1f, 0.2f)
        val q = TilePositions()
        q.load(p.serialize())
        assertEquals(0.55f to 0.6f, q.recall("camera:front porch"))
        assertEquals(0.1f to 0.2f, q.recall("data:house"))
        q.load("garbage line\n\t\t\nx\tnot-a-float\t0.5")
        assertEquals(0, q.size()) // malformed lines are skipped, never fatal
        q.load("")
        assertEquals(0, q.size())
    }

    // ---- the brief deck ----

    private fun deckTile(cards: List<DeckCard>): OverlayTile =
        OverlayTile.list("Brief", cards.firstOrNull()?.lines ?: emptyList()).copy(cards = cards)

    @Test
    fun briefDeckCardsComeOnlyFromTheBrief() {
        val cards = BriefDeck.cards(
            listOf("Messages" to "Dad: running late", "Mail" to "Invoice due"),
            listOf("Rain by noon.", "Tote views up 12%.")
        )
        assertEquals(4, cards.size)
        assertEquals("Messages", cards[0].title)
        assertEquals(listOf("Dad: running late"), cards[0].lines)
        assertEquals("Brief", cards[2].title)
        assertEquals(emptyList<DeckCard>(), BriefDeck.cards(emptyList(), emptyList()))
        assertEquals("BRIEF · 2 OF 5", BriefDeck.counter(1, 5))
    }

    @Test
    fun deckAdvanceDismissesCardsAndEndsTheDeck() {
        val s = TileStack()
        val cards = BriefDeck.cards(emptyList(), listOf("One.", "Two.", "Three."))
        val t = deckTile(cards)
        s.putUp(t)
        assertEquals(0, s.deckIndex(t.id))
        s.advanceDeck(t.id)
        assertEquals(1, s.deckIndex(t.id))
        assertEquals("BRIEF · 2 OF 3", BriefDeck.counter(s.deckIndex(t.id), cards.size))
        s.advanceDeck(t.id)
        s.advanceDeck(t.id) // past the last card: the deck comes down
        assertEquals(0, s.state().visible.size)
        // A fresh showing restarts at the top card.
        s.putUp(deckTile(cards))
        assertEquals(0, s.deckIndex(t.id))
    }

    @Test
    fun pinnedDeckStaysOnItsLastCard() {
        val s = TileStack()
        val cards = BriefDeck.cards(emptyList(), listOf("One.", "Two."))
        val t = deckTile(cards)
        s.putUp(t)
        s.togglePin(t.id)
        s.advanceDeck(t.id)
        s.advanceDeck(t.id) // pinned: clamps to the last card instead of coming down
        assertEquals(1, s.state().visible.size)
        assertEquals(1, s.deckIndex(t.id))
        // And the deck is ONE tile for clear: pinned, it survives clear-all whole.
        assertEquals(1, s.clearAll().visible.size)
    }

    // ---- the Holodeck tile ----

    private fun job(status: String, stage: String, name: String = "", prompt: String = "make me a shelf bracket with two holes") =
        Hub.CadJob("abc123", status, stage, prompt, name, "", 1)

    @Test
    fun holodeckSummaryAndStatusWords() {
        assertEquals("SHELF BRACKET", Holodeck.summary("make me a shelf bracket with two holes"))
        assertEquals("", Holodeck.summary(""))
        assertEquals("BUILDING", Holodeck.statusWord(job("working", "building")))
        assertEquals("QUEUED", Holodeck.statusWord(job("queued", "queued")))
        assertEquals("DONE", Holodeck.statusWord(job("done", "done")))
        assertEquals("FAILED", Holodeck.statusWord(job("failed", "failed")))
        assertEquals("BRACKET — BUILDING", Holodeck.displayTitle(job("working", "building", prompt = "make a bracket")))
        assertEquals("SHELF BRACKET — DONE", Holodeck.displayTitle(job("done", "done", name = "Shelf Bracket")))
    }

    @Test
    fun holodeckTileIdentityIsStableAndNeverInventsAPercentage() {
        val building = Holodeck.tile(job("working", "building"))
        val done = Holodeck.tile(job("done", "done"))
        assertEquals(Holodeck.TILE_ID, building.id)
        assertEquals(building.id, done.id) // the poll replaces in place, never a second tile
        assertTrue(done.lines.any { it.contains("DONE — READY IN HOLODECK") })
        for (tile in listOf(building, done, Holodeck.tile(job("failed", "failed")))) {
            assertFalse("no invented %: ${tile.title} ${tile.lines}", (tile.title + tile.lines.joinToString()).contains("%"))
        }
        assertEquals(listOf("NO HOLODECK JOB RUNNING"), Holodeck.noJobTile("").lines)
        assertEquals("STALE", Holodeck.noJobTile("BRACKET — BUILDING").sourceLabel)
    }

    @Test
    fun hubParsesCadJobsAndLibrary() {
        val j = Hub.parseCadJob(
            """{"job_id":"abc123","status":"working","stage":"building","prompt":"make a bracket","name":"","attempts":2}"""
        )
        assertEquals("abc123", j?.jobId)
        assertEquals("building", j?.stage)
        assertEquals(2, j?.attempts)
        assertTrue(j?.active == true)
        assertNull(Hub.parseCadJob("{}"))
        assertNull(Hub.parseCadJob("not json"))
        val entries = Hub.parseLibrary(
            """{"entries":[{"id":"f-abc123","kind":"holodeck","name":"Bracket","created":1730000000.5,"job_id":"abc123"},{"id":"x.stl","kind":"stl","name":"X","created":1720000000.0}]}"""
        )
        assertEquals(2, entries.size)
        assertEquals("abc123", entries[0].jobId)
        assertNull(entries[1].jobId)
        assertEquals(emptyList<Hub.LibEntry>(), Hub.parseLibrary("nope"))
    }
}
