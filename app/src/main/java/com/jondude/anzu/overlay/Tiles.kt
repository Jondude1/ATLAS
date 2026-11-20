package com.jondude.anzu.overlay

/**
 * The overlay tile system (v1.2.18, "Put It Up"): Atlas's general display surface.
 * When Jon asks Atlas to show something — a camera, the brief, the house, what's next —
 * it appears as a floating cyan-glass window over whatever app is open (mockups D3/D5/D2).
 *
 * This file is the PURE layer: the tile model, the stack semantics and the layout math,
 * kept free of Android calls so it can be unit-tested directly (same pattern as
 * LauncherLogic). The Android-facing manager is [TileManager]; OverlayService renders.
 */

/** What a tile shows. CAMERA carries an image (via [OverlayTile.imageKey]); the rest carry text lines. */
enum class TileKind { CAMERA, INFO, DATA, LIST }

/** One card of a fanned deck (v1.2.19): a header plus its lines. The brief renders as a deck of these. */
data class DeckCard(val title: String, val lines: List<String>)

/** How several tiles arrange: AUTO = cascade for 2+, STACK = the D2 right-side column for 2+. */
enum class TileMode { AUTO, STACK }

/**
 * One thing Atlas has put up. The bitmap itself never lives here: a CAMERA tile's
 * [imageKey] is its Home Assistant entity id, which is the key the renderer uses for
 * its bitmap cache — the model stays pure and testable. [sourceLabel] is the honesty
 * suffix rendered after the title (a camera's "SNAPSHOT 4:21 PM" — refreshed stills,
 * never labelled LIVE).
 */
data class OverlayTile(
    val id: String,
    val kind: TileKind,
    val title: String,
    val lines: List<String> = emptyList(),
    val imageKey: String? = null,
    val sourceLabel: String = "",
    val updatedAtMs: Long = 0L,
    /** A fanned deck's cards (v1.2.19, the brief). Empty for ordinary tiles; [lines] mirrors the top card. */
    val cards: List<DeckCard> = emptyList()
) {
    /** The header as rendered: the title, plus the honesty label when there is one. */
    fun header(): String =
        title.trim().uppercase() + if (sourceLabel.isNotBlank()) " · " + sourceLabel.trim().uppercase() else ""

    companion object {
        /** Stable identity: same kind + same title replaces the tile already up. */
        fun idFor(kind: TileKind, title: String): String =
            kind.name.lowercase() + ":" + title.trim().lowercase().replace(Regex("\\s+"), " ")

        fun camera(entityId: String, name: String, sourceLabel: String = "", updatedAtMs: Long = 0L): OverlayTile =
            OverlayTile(idFor(TileKind.CAMERA, name), TileKind.CAMERA, name, imageKey = entityId, sourceLabel = sourceLabel, updatedAtMs = updatedAtMs)

        fun info(title: String, lines: List<String>): OverlayTile =
            OverlayTile(idFor(TileKind.INFO, title), TileKind.INFO, title, lines)

        fun data(title: String, lines: List<String>): OverlayTile =
            OverlayTile(idFor(TileKind.DATA, title), TileKind.DATA, title, lines)

        fun list(title: String, lines: List<String>): OverlayTile =
            OverlayTile(idFor(TileKind.LIST, title), TileKind.LIST, title, lines)
    }
}

/**
 * The current set of put-ups, oldest first. Pure semantics:
 * - putUp with an id already up REPLACES that tile in place (its slot does not move).
 * - At most [TileLayout.MAX_VISIBLE] tiles are visible; a new tile beyond that collapses
 *   the OLDEST into the count (it leaves the screen but still counts on the chip).
 * - clearAll also resets the collapsed count.
 *
 * v1.2.19 adds the interaction layer, all of it pure so it is unit-testable:
 * - PIN: a pinned tile survives clear, clearAll and the cap's collapse (the collapse
 *   takes the oldest UNPINNED tile; when every visible tile is pinned, the newcomer
 *   itself collapses into the count). Pin membership is keyed by tile id, so replacing
 *   a tile's contents never drops its pin.
 * - GROW: [State.grownId] names the one hero tile; the layout is derived from state,
 *   so ungrowing restores the previous arrangement exactly, by construction.
 * - DECK: a tile with cards carries a deck index; advancing dismisses the top card for
 *   that showing, and advancing past the last card takes the deck down.
 */
class TileStack {
    private val tiles = ArrayList<OverlayTile>()
    private var collapsed = 0
    private val pinned = LinkedHashSet<String>()
    private var grownId: String? = null
    private val deckIndex = HashMap<String, Int>()

    /** The most recently put-up tile's id: what "that" means in "pin that" / "make it bigger". */
    var lastPutUpId: String? = null
        private set

    var mode: TileMode = TileMode.AUTO
        private set

    data class State(
        val visible: List<OverlayTile>, val collapsedCount: Int, val mode: TileMode,
        val pinned: Set<String> = emptySet(), val grownId: String? = null
    ) {
        /** Everything Atlas has put up and not yet been told to take down. */
        val total: Int get() = visible.size + collapsedCount
        fun isPinned(id: String): Boolean = id in pinned
        /** The hero tile, when one is grown and still up. */
        val grown: OverlayTile? get() = visible.firstOrNull { it.id == grownId }
    }

    @Synchronized
    fun putUp(tile: OverlayTile): State {
        lastPutUpId = tile.id
        val i = tiles.indexOfFirst { it.id == tile.id }
        if (i >= 0) {
            tiles[i] = tile
            if (tile.cards.isNotEmpty()) deckIndex[tile.id] = 0 // a fresh showing starts at the top card
            return state()
        }
        tiles.add(tile)
        if (tile.cards.isNotEmpty()) deckIndex[tile.id] = 0
        while (tiles.size > TileLayout.MAX_VISIBLE) {
            val victim = tiles.indexOfFirst { it.id !in pinned }
            if (victim < 0) {
                // Everything up is pinned: the newcomer collapses into the count instead.
                tiles.removeAt(tiles.lastIndex)
                collapsed++
                break
            }
            val gone = tiles.removeAt(victim)
            deckIndex.remove(gone.id)
            if (grownId == gone.id) grownId = null
            collapsed++
        }
        return state()
    }

    /** Replace a tile's contents without moving it and without touching the counts (snapshot refresh uses this). */
    @Synchronized
    fun update(tile: OverlayTile): State {
        val i = tiles.indexOfFirst { it.id == tile.id }
        if (i >= 0) tiles[i] = tile
        return state()
    }

    /** Take one tile down. A pinned tile refuses: unpin it first. */
    @Synchronized
    fun clear(id: String): State {
        if (id in pinned) return state()
        tiles.removeAll { it.id == id }
        deckIndex.remove(id)
        if (grownId == id) grownId = null
        if (lastPutUpId == id) lastPutUpId = tiles.lastOrNull()?.id
        return state()
    }

    /** Take everything down EXCEPT the pinned tiles, which stay up. */
    @Synchronized
    fun clearAll(): State {
        tiles.removeAll { it.id !in pinned }
        deckIndex.keys.retainAll(tiles.map { it.id }.toSet())
        if (grownId != null && tiles.none { it.id == grownId }) grownId = null
        collapsed = 0
        lastPutUpId = tiles.lastOrNull()?.id
        return state()
    }

    @Synchronized
    fun setMode(m: TileMode): State {
        mode = m
        return state()
    }

    // ---- pin ----

    /** Toggle a visible tile's pin. Returns true when the tile is pinned after the call. */
    @Synchronized
    fun togglePin(id: String): Boolean {
        if (tiles.none { it.id == id }) return id in pinned
        return if (id in pinned) { pinned.remove(id); false } else { pinned.add(id); true }
    }

    @Synchronized
    fun setPinned(id: String, on: Boolean) {
        if (on) pinned.add(id) else pinned.remove(id)
    }

    @Synchronized
    fun isPinned(id: String): Boolean = id in pinned

    @Synchronized
    fun pinnedIds(): Set<String> = pinned.toSet()

    /** Re-apply persisted pins (overlay restart): ids whose tiles aren't up yet stay armed for their next put-up. */
    @Synchronized
    fun restorePins(ids: Set<String>) {
        pinned.addAll(ids)
    }

    // ---- grow ----

    /** Grow a visible tile to the hero. False when it isn't up. */
    @Synchronized
    fun grow(id: String): Boolean {
        if (tiles.none { it.id == id }) return false
        grownId = id
        return true
    }

    /** Restore the pre-grow layout (derived from state, so this is exact). False when nothing was grown. */
    @Synchronized
    fun shrink(): Boolean {
        val was = grownId != null
        grownId = null
        return was
    }

    // ---- deck ----

    @Synchronized
    fun deckIndex(id: String): Int = deckIndex[id] ?: 0

    /**
     * Dismiss the deck's top card for this showing. Advancing past the last card takes
     * the whole deck down (unless it is pinned — a pinned deck just stays on its last card).
     */
    @Synchronized
    fun advanceDeck(id: String): State {
        val t = tiles.firstOrNull { it.id == id } ?: return state()
        if (t.cards.isEmpty()) return state()
        val next = (deckIndex[id] ?: 0) + 1
        if (next >= t.cards.size) {
            if (id !in pinned) {
                tiles.removeAll { it.id == id }
                deckIndex.remove(id)
                if (grownId == id) grownId = null
            } else {
                deckIndex[id] = t.cards.size - 1
            }
        } else {
            deckIndex[id] = next
        }
        return state()
    }

    @Synchronized
    fun state(): State = State(tiles.toList(), collapsed, mode, pinned.toSet(), grownId)
}

/** The brief as a fanned deck (v1.2.19): one card per item, built ONLY from the brief's real contents. */
object BriefDeck {
    const val MAX_CARDS = 10

    /**
     * Urgent items first (one card each, headed by their app), then the brief's own
     * lines, one card apiece. Empty in, empty out — the caller renders the honest
     * "NO BRIEF YET" card; nothing is ever fabricated here.
     */
    fun cards(urgent: List<Pair<String, String>>, lines: List<String>): List<DeckCard> {
        val out = ArrayList<DeckCard>()
        for ((app, title) in urgent) out += DeckCard(app.ifBlank { "Urgent" }, listOf(title))
        for (line in lines) if (line.isNotBlank()) out += DeckCard("Brief", listOf(line))
        return out.take(MAX_CARDS)
    }

    /** The counter chip's text for the card showing at [index] (0-based) of [total]. */
    fun counter(index: Int, total: Int): String = "BRIEF · ${index + 1} OF $total"
}

/** Pure layout: where each tile goes, as screen fractions, plus the caption chip's text. */
object TileLayout {
    /** At most four tiles on screen at once. */
    const val MAX_VISIBLE = 4

    /** Tiles plus the one caption/close chip window: never more than five overlay windows for put-ups. */
    const val MAX_WINDOWS = MAX_VISIBLE + 1

    enum class Arrangement { PIP, CASCADE, STACK }

    /**
     * A tile's window placement as fractions of the screen. [yFrac] is the top edge,
     * unless [fromBottom] is set, when it is the margin above the bottom edge (the
     * renderer measures the tile's height and subtracts). Height is content-driven,
     * except CAMERA tiles, which the renderer holds at 16:9 under their header.
     */
    data class Placement(val xFrac: Float, val yFrac: Float, val wFrac: Float, val fromBottom: Boolean = false)

    fun arrangement(count: Int, mode: TileMode): Arrangement = when {
        count <= 1 -> Arrangement.PIP
        mode == TileMode.STACK -> Arrangement.STACK
        else -> Arrangement.CASCADE
    }

    /** Width fraction for a lone (PiP) tile: cameras sit small in the corner (D3); text tiles get more room. */
    fun pipWidthFrac(kind: TileKind): Float = if (kind == TileKind.CAMERA) 0.42f else 0.62f

    fun placement(index: Int, count: Int, mode: TileMode, kind: TileKind): Placement =
        when (arrangement(count, mode)) {
            Arrangement.PIP -> {
                val w = pipWidthFrac(kind)
                Placement(xFrac = 1f - w - 0.03f, yFrac = 0.035f, wFrac = w, fromBottom = true)
            }
            Arrangement.CASCADE -> Placement(
                xFrac = (0.33f - 0.07f * index).coerceAtLeast(0.02f),
                yFrac = 0.14f + 0.185f * index,
                wFrac = 0.64f
            )
            Arrangement.STACK -> Placement(
                xFrac = 1f - 0.46f - 0.025f,
                yFrac = 0.13f + 0.185f * index,
                wFrac = 0.46f
            )
        }

    /** The expanded tile: one large centered card; the rest dim behind it. */
    fun expandedPlacement(): Placement = Placement(xFrac = 0.06f, yFrac = 0.28f, wFrac = 0.88f)

    /** The grown tile (v1.2.19, D10 hero): larger and higher than the tap-expand card; the rest collapse to a chip. */
    fun heroPlacement(): Placement = Placement(xFrac = 0.04f, yFrac = 0.15f, wFrac = 0.92f)

    /** The edge chip that stands in for the tiles hidden behind a grown hero: "+2". */
    fun collapsedChipText(hiddenCount: Int): String = "+$hiddenCount"

    /**
     * The caption chip (D3/D2): one tile names itself ("ATLAS · SHOWING FRONT PORCH");
     * several become a count ("ATLAS · SHOWING 3 ITEMS") — the count includes any
     * tiles collapsed by the cap. Nothing up, no chip.
     */
    fun chipText(state: TileStack.State): String? = when {
        state.total == 0 -> null
        state.total == 1 -> "ATLAS · SHOWING " + state.visible.first().title.trim().uppercase()
        else -> "ATLAS · SHOWING ${state.total} ITEMS"
    }
}
