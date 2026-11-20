package com.jondude.anzu.overlay

import android.content.Context
import com.jondude.anzu.assist.HomeAssistant

/**
 * The Android-facing half of the tile system: owns the one [TileStack], takes put-up /
 * clear calls from anywhere in the app, and tells OverlayService to re-render. State is
 * kept even when the overlay service isn't running — the tiles render when it next starts.
 *
 * Camera tiles hold refreshed STILLS from Home Assistant (see [HomeAssistant.cameraSnapshot]);
 * their headers carry the actual fetch time and the word SNAPSHOT, never LIVE.
 */
object TileManager {
    private val stack = TileStack()

    fun state(): TileStack.State = stack.state()

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
        stack.clear(id)
        OverlayService.tilesChanged()
    }

    /** Take everything Atlas put up back down. */
    fun clearAll() {
        stack.clearAll()
        OverlayService.tilesChanged()
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
