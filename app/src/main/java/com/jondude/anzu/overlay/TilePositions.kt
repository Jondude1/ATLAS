package com.jondude.anzu.overlay

/**
 * Remembered tile positions (v1.2.19): where Jon dragged each tile, keyed by tile
 * identity (the tile id IS kind + normalized title). Pure logic — the store/recall/
 * evict/serialize rules are unit-tested here; TileManager persists [serialize] into
 * Prefs and the renderer consults [recall] before falling back to the layout engine.
 *
 * Positions are the tile window's top-left corner as screen fractions, so they survive
 * rotation/resolution changes approximately and never land off-screen by construction.
 */
class TilePositions(private val cap: Int = MAX_ENTRIES) {
    // Insertion-ordered: the eldest entry is evicted first when the cap is hit.
    private val map = LinkedHashMap<String, Pair<Float, Float>>()

    @Synchronized
    fun remember(id: String, xFrac: Float, yFrac: Float) {
        if (id.isBlank()) return
        map.remove(id) // re-insert at the tail: most-recently-moved is evicted last
        map[id] = clamp(xFrac) to clamp(yFrac)
        while (map.size > cap) map.remove(map.keys.first())
    }

    @Synchronized
    fun recall(id: String): Pair<Float, Float>? = map[id]

    @Synchronized
    fun forget(id: String) {
        map.remove(id)
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size

    /** One line per entry: id, x, y — ids never contain tabs or newlines (they are kind + normalized title). */
    @Synchronized
    fun serialize(): String = map.entries.joinToString("\n") { (id, p) -> "$id\t${p.first}\t${p.second}" }

    /** Load a [serialize]d blob, replacing current contents. Malformed lines are skipped, never fatal. */
    @Synchronized
    fun load(blob: String) {
        map.clear()
        if (blob.isBlank()) return
        for (line in blob.split('\n')) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val x = parts[1].toFloatOrNull() ?: continue
            val y = parts[2].toFloatOrNull() ?: continue
            if (parts[0].isBlank()) continue
            map[parts[0]] = clamp(x) to clamp(y)
        }
        while (map.size > cap) map.remove(map.keys.first())
    }

    private fun clamp(v: Float): Float = v.coerceIn(0f, 1f)

    companion object {
        const val MAX_ENTRIES = 24
    }
}
