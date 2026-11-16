package com.jondude.anzu.overlay

/** The companion's on-screen size, the Android take on Roamies' default / small / cursor sizes. */
object OrbSize {
    private val order = listOf("default", "small", "mini")

    fun normalize(key: String?): String = if (key in order) key!! else "default"
    fun next(key: String?): String = order[(order.indexOf(normalize(key)) + 1) % order.size]
    fun dp(key: String?): Int = when (normalize(key)) {
        "small" -> 56
        "mini" -> 36
        else -> 84
    }
    fun label(key: String?): String = normalize(key).replaceFirstChar { it.uppercase() }
}
