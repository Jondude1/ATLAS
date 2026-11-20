package com.jondude.anzu.launcher

/**
 * The lock-screen orb's pure logic (v1.2.19, visual target L3 "Instrument Ring"):
 * rotation, tick layout and the gating rules — unit-tested here; LockOrbActivity
 * only renders what this decides.
 *
 * The instrument ring is a dial of [TICK_COUNT] ticks in a rotating frame. Five lit
 * blocks of seven ticks travel with the frame (the L3 look: bright cyan segments
 * separated by dark gaps), four cardinal ticks are longer, and the whole frame makes
 * one calm revolution per [ROTATION_PERIOD_MS] — a presence, not a spinner.
 */
object LockOrb {

    /** One revolution per 20 s (Jon's spec: calm, ~20s/rev). */
    const val ROTATION_PERIOD_MS = 20_000L

    const val TICK_COUNT = 60

    /** The lit blocks: 7 lit ticks, then 5 dark, repeating around the dial. */
    const val LIT_RUN = 7
    const val BLOCK = 12

    /** The frame's rotation at [elapsedMs] into the animation, in degrees, always in [0, 360). */
    fun rotationDeg(elapsedMs: Long, periodMs: Long = ROTATION_PERIOD_MS): Float {
        if (periodMs <= 0) return 0f
        val m = ((elapsedMs % periodMs) + periodMs) % periodMs
        return m.toFloat() / periodMs.toFloat() * 360f
    }

    /** Where tick [index] sits once the frame has rotated: its angle in degrees, in [0, 360). */
    fun tickAngleDeg(index: Int, rotationDeg: Float): Float {
        val base = index * (360f / TICK_COUNT)
        val a = (base + rotationDeg) % 360f
        return if (a < 0f) a + 360f else a
    }

    /** The four cardinal ticks (top/right/bottom/left of the rotating frame) are the long ones. */
    fun isMajorTick(index: Int): Boolean = index % (TICK_COUNT / 4) == 0

    /** Every fifth tick is a medium tick. */
    fun isMediumTick(index: Int): Boolean = index % 5 == 0

    /** Whether tick [index] belongs to a lit segment in the rotating frame. */
    fun tickLit(index: Int): Boolean = ((index % BLOCK) + BLOCK) % BLOCK < LIT_RUN

    /** Draw length of a tick as a fraction of the ring radius: cardinal > medium > minor. */
    fun tickLengthFrac(index: Int): Float = when {
        isMajorTick(index) -> 0.16f
        isMediumTick(index) -> 0.10f
        else -> 0.06f
    }

    /**
     * The animation runs only while the feature is on, the screen is on, and the
     * battery is at or above 20% (an unknown reading animates — same rule as home).
     * Below 20% the orb holds a static frame.
     */
    fun shouldAnimate(enabled: Boolean, screenOn: Boolean, batteryPct: Int): Boolean =
        enabled && screenOn && (batteryPct < 0 || batteryPct >= 20)

    /** The orb shows only when the feature is on AND the keyguard is actually locked. */
    fun shouldShow(enabled: Boolean, keyguardLocked: Boolean): Boolean = enabled && keyguardLocked
}
