package com.jondude.anzu.overlay

import android.content.Context
import com.jondude.anzu.Prefs
import com.jondude.anzu.Store
import com.jondude.anzu.assist.Holodeck
import com.jondude.anzu.assist.HomeAssistant

/**
 * The Android-facing half of the tile system: owns the one [TileStack], takes put-up /
 * clear calls from anywhere in the app, and tells OverlayService to re-render. State is
 * kept even when the overlay service isn't running — the tiles render when it next starts.
 *
 * Camera tiles hold refreshed STILLS from Home Assistant (see [HomeAssistant.cameraSnapshot]);
 * their headers carry the actual fetch time and the word SNAPSHOT, never LIVE.
 *
 * v1.2.19: the interaction layer lives here too — pins and dragged positions persist
 * in Prefs (pins re-arm on the stack, so they survive overlay restarts), and the
 * Holodeck tile's poll updates its job in place.
 */
object TileManager {
    private val stack = TileStack()
    private val positions = TilePositions()

    @Volatile
    private var persistedLoaded = false

    /** Load pins + positions from Prefs once, and re-arm the pins on the stack. */
    @Synchronized
    private fun loadPersisted() {
        if (persistedLoaded) return
        persistedLoaded = true
        try {
            val pins = Prefs.tilePinnedIds.split('').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (pins.isNotEmpty()) stack.restorePins(pins)
            positions.load(Prefs.tilePositions)
        } catch (_: Throwable) {
        }
    }

    private fun persistPins() {
        try { Prefs.tilePinnedIds = stack.pinnedIds().joinToString("") } catch (_: Throwable) {}
    }

    fun state(): TileStack.State {
        loadPersisted()
        return stack.state()
    }

    /** Put a tile up (or replace the one with the same kind + title). Starts the overlay if it should be running. */
    fun putUp(ctx: Context?, tile: OverlayTile) {
        stack.putUp(tile)
        if (ctx != null) OverlayService.ensureRunning(ctx)
        OverlayService.tilesChanged()
    }

    /** An INFO tile: something Atlas fetched or was handed, shown as a headline + lines. Any caller may use this. */
    fun putUpInfo(title: String, lines: List<String>) = putUp(null, OverlayTile.info(title, lines))

    fun putUpInfo(ctx: Context, title: String, lines: List<String>) = putUp(ctx, OverlayTile.info(title, lines))

    fun putUpData(ctx: Context?, title: String, lines: List<String>) = putUp(ctx, OverlayTile.data(title, lines))

    fun putUpList(ctx: Context?, title: String, lines: List<String>) = putUp(ctx, OverlayTile.list(title, lines))

    /** Put one camera up. The renderer fetches its first still immediately and refreshes it while it stays up. */
    fun putUpCamera(ctx: Context?, cam: HomeAssistant.HaCamera) =
        putUp(ctx, OverlayTile.camera(cam.entityId, cam.name))

    /**
     * The honest camera tile for when there is nothing to show: Home Assistant isn't
     * connected, or it reports no cameras. No image is ever fabricated.
     */
    fun putUpCamerasNotSetUp(ctx: Context?) = putUp(
        ctx,
        OverlayTile.camera("", "Cameras").copy(
            lines = listOf(
                "CAMERAS — NOT SET UP",
                "Connect Home Assistant in Atlas → Settings → Home Assistant."
            )
        )
    )

    fun clear(ctx: Context?, id: String) {
        loadPersisted()
        stack.clear(id)
        OverlayService.tilesChanged()
    }

    /** Take everything Atlas put up back down — except pinned tiles, which stay (v1.2.19). */
    fun clearAll() {
        loadPersisted()
        stack.clearAll()
        OverlayService.tilesChanged()
    }

    // ---- interaction layer (v1.2.19) ----

    /** The tile "that" refers to: the most recently put up, or the grown hero if there is one. */
    fun activeTileId(): String? {
        loadPersisted()
        val st = stack.state()
        return st.grownId ?: stack.lastPutUpId ?: st.visible.lastOrNull()?.id
    }

    /** Pin or unpin the active tile. Returns the tile's title when it is pinned after the call, null otherwise. */
    fun pinActive(): String? {
        loadPersisted()
        val id = activeTileId() ?: return null
        val nowPinned = stack.togglePin(id)
        persistPins()
        OverlayService.tilesChanged()
        return if (nowPinned) stack.state().visible.firstOrNull { it.id == id }?.title else null
    }

    /** Unpin the active tile if it is pinned. True when something was unpinned. */
    fun unpinActive(): Boolean {
        loadPersisted()
        val id = activeTileId() ?: return false
        if (!stack.isPinned(id)) return false
        stack.togglePin(id)
        persistPins()
        OverlayService.tilesChanged()
        return true
    }

    /** Long-press on a tile toggles its pin. True when pinned after the call. */
    fun togglePin(id: String): Boolean {
        loadPersisted()
        val now = stack.togglePin(id)
        persistPins()
        OverlayService.tilesChanged()
        return now
    }

    /** Grow the active tile to the D10 hero. False when nothing is up to grow. */
    fun growActive(): Boolean {
        loadPersisted()
        val id = activeTileId() ?: return false
        val ok = stack.grow(id)
        if (ok) OverlayService.tilesChanged()
        return ok
    }

    fun grow(id: String): Boolean {
        loadPersisted()
        val ok = stack.grow(id)
        if (ok) OverlayService.tilesChanged()
        return ok
    }

    /** Restore the pre-grow layout. False when nothing was grown. */
    fun shrink(): Boolean {
        loadPersisted()
        val ok = stack.shrink()
        if (ok) OverlayService.tilesChanged()
        return ok
    }

    /** Advance a deck tile (dismiss its top card for this showing). */
    fun advanceDeck(id: String) {
        loadPersisted()
        stack.advanceDeck(id)
        OverlayService.tilesChanged()
    }

    fun deckIndex(id: String): Int {
        loadPersisted()
        return stack.deckIndex(id)
    }

    // ---- dragged positions ----

    /** Where Jon last dragged this tile (top-left, screen fractions), or null for layout-engine placement. */
    fun rememberedPosition(id: String): Pair<Float, Float>? {
        loadPersisted()
        return positions.recall(id)
    }

    fun rememberPosition(id: String, xFrac: Float, yFrac: Float) {
        loadPersisted()
        positions.remember(id, xFrac, yFrac)
        try { Prefs.tilePositions = positions.serialize() } catch (_: Throwable) {}
    }

    /** The Settings "reset tile positions" row: forget every dragged position. */
    fun resetPositions() {
        loadPersisted()
        positions.clear()
        try { Prefs.tilePositions = "" } catch (_: Throwable) {}
        OverlayService.tilesChanged()
    }

    // ---- the brief deck + the Holodeck tile ----

    /** Put the brief up as a fanned deck (one tile; cards advance inside it). */
    fun putUpBriefDeck(ctx: Context?, cards: List<DeckCard>) {
        val top = cards.firstOrNull()
        val tile = OverlayTile.list("Brief", top?.lines ?: listOf("NO BRIEF YET")).copy(cards = cards)
        putUp(ctx, tile)
    }

    @Volatile
    private var holodeckWasActive = false

    /**
     * The renderer's poll (only while the Holodeck tile is up): re-read its job and
     * update the tile IN PLACE when the state moves. A finish while the tile is up is
     * written to the action log (the proactive audit trail) exactly once per job.
     */
    fun refreshHolodeckTile() {
        val st = state()
        if (st.visible.none { it.id == Holodeck.TILE_ID }) return
        val jobId = Prefs.lastCadJobId
        if (jobId.isBlank()) return
        val job = Holodeck.poll(jobId) ?: return
        val current = st.visible.first { it.id == Holodeck.TILE_ID }
        val fresh = Holodeck.tile(job)
        if (fresh.title != current.title || fresh.lines != current.lines) {
            stack.update(fresh)
            OverlayService.tilesChanged()
        }
        if (job.active) {
            holodeckWasActive = true
        } else if (holodeckWasActive) {
            holodeckWasActive = false
            try { Store.logAction("Holodeck job ${job.jobId} ${job.status}: ${Holodeck.displayTitle(job)}") } catch (_: Throwable) {}
        }
    }

    fun setMode(mode: TileMode) {
        stack.setMode(mode)
        OverlayService.tilesChanged()
    }

    /**
     * Stamp a camera tile with its latest snapshot's fetch label ("SNAPSHOT 4:21 PM").
     * Deliberately silent: the renderer's refresh loop updates the visible views itself,
     * so a stamp every ten seconds doesn't trigger a full re-render.
     */
    fun noteSnapshot(tileId: String, label: String, atMs: Long) {
        val t = stack.state().visible.firstOrNull { it.id == tileId } ?: return
        if (t.sourceLabel == label) return
        stack.update(t.copy(sourceLabel = label, updatedAtMs = atMs))
    }

    /**
     * Resolve a Frigate room key ("living_room", "family_room") to the HA camera that
     * watches it, by entity id or friendly name. Null when no camera matches — callers
     * skip the tile silently. Blocking (one states fetch).
     */
    fun cameraForRoom(roomKey: String): HomeAssistant.HaCamera? {
        val roomName = roomKey.replace('_', ' ')
        return HomeAssistant.cameras().firstOrNull { cam ->
            cam.entityId.contains(roomKey) ||
                cam.name.lowercase().replace(' ', '_').contains(roomKey) ||
                cam.name.lowercase().contains(roomName)
        }
    }
}
