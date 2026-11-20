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
    val updatedAtMs: Long = 0L
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
 */
class TileStack {
    private val tiles = ArrayList<OverlayTile>()
    private var collapsed = 0

    var mode: TileMode = TileMode.AUTO
        private set

    data class State(val visible: List<OverlayTile>, val collapsedCount: Int, val mode: TileMode) {
        /** Everything Atlas has put up and not yet been told to take down. */
        val total: Int get() = visible.size + collapsedCount
    }

    @Synchronized
    fun putUp(tile: OverlayTile): State {
        val i = tiles.indexOfFirst { it.id == tile.id }
        if (i >= 0) {
            tiles[i] = tile
            return state()
        }
        tiles.add(tile)
        while (tiles.size > TileLayout.MAX_VISIBLE) {
            tiles.removeAt(0)
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

    @Synchronized
    fun clear(id: String): State {
        tiles.removeAll { it.id == id }
        return state()
    }

    @Synchronized
    fun clearAll(): State {
        tiles.clear()
        collapsed = 0
        return state()
    }

    @Synchronized
    fun setMode(m: TileMode): State {
        mode = m
        return state()
    }

    @Synchronized
    fun state(): State = State(tiles.toList(), collapsed, mode)
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
