package com.jondude.anzu

import com.jondude.anzu.launcher.LockOrb
import com.jondude.anzu.launcher.SphereMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The dense sphere's math and the lock ring's math (v1.2.19): pure, so they're pinned down here. */
class OrbMathTest {

    // ---- the dense sphere ----

    @Test
    fun shippedDensityIsThousandsNotHundreds() {
        // Jon's requirement: the sparse 260-point sphere is gone; the count is declared.
        assertEquals(2600, SphereMath.POINT_COUNT)
        assertTrue(SphereMath.POINT_COUNT >= 2000)
    }

    @Test
    fun fibonacciPointsLieOnTheUnitSphere() {
        val pts = SphereMath.fibonacci(500)
        assertEquals(1500, pts.size)
        for (i in 0 until 500) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]; val z = pts[i * 3 + 2]
            val len = sqrt(x * x + y * y + z * z)
            assertEquals("point $i on the sphere", 1f, len, 0.01f)
        }
    }

    @Test
    fun rotationPreservesTheSphere() {
        val out = FloatArray(3)
        val cosR = kotlin.math.cos(0.5).toFloat()
        val sinR = kotlin.math.sin(0.5).toFloat()
        val cosT = kotlin.math.cos(0.42).toFloat()
        val sinT = kotlin.math.sin(0.42).toFloat()
        SphereMath.rotateInto(0.3f, -0.5f, 0.8f, cosR, sinR, cosT, sinT, out, 0)
        val len = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        val orig = sqrt(0.3f * 0.3f + 0.5f * 0.5f + 0.8f * 0.8f)
        assertEquals(orig, len, 0.001f)
    }

    @Test
    fun depthAndRimGlowRunBackToFrontAndCentreToRim() {
        assertEquals(0f, SphereMath.depth(-1f), 0.001f)
        assertEquals(1f, SphereMath.depth(1f), 0.001f)
        assertEquals(0f, SphereMath.rimGlow(0f), 0.001f)
        assertEquals(1f, SphereMath.rimGlow(1f), 0.001f)
        var prev = -1f
        for (i in 0..10) {
            val g = SphereMath.rimGlow(i / 10f)
            assertTrue("rim glow rises monotonically", g >= prev)
            prev = g
        }
    }

    @Test
    fun alphaAndRadiusStayInRange() {
        for (d in 0..10) for (r in 0..10) {
            val a = SphereMath.alpha(d / 10f, r / 10f)
            assertTrue("alpha in range ($d,$r)", a in 0f..1f)
            val rad = SphereMath.radiusDp(d / 10f, r / 10f)
            assertTrue("radius sane ($d,$r)", rad in 0.4f..2.0f)
        }
        // The front rim burns brightest: that is the L3 edge.
        assertTrue(SphereMath.alpha(1f, 1f) > SphereMath.alpha(0f, 0f))
        assertTrue(SphereMath.alpha(1f, 1f) > SphereMath.alpha(1f, 0f))
    }

    // ---- the lock ring ----

    @Test
    fun rotationIsCalmAndWraps() {
        assertEquals(0f, LockOrb.rotationDeg(0), 0.001f)
        assertEquals(180f, LockOrb.rotationDeg(10_000), 0.001f)
        assertEquals(0f, LockOrb.rotationDeg(20_000), 0.001f) // one full revolution per 20 s
        assertEquals(90f, LockOrb.rotationDeg(25_000), 0.001f)
        assertEquals(270f, LockOrb.rotationDeg(-5_000), 0.001f)
    }

    @Test
    fun ticksAreEvenlySpacedWithFourCardinals() {
        assertEquals(60, LockOrb.TICK_COUNT)
        for (i in 0 until LockOrb.TICK_COUNT) {
            assertEquals(i * 6f, LockOrb.tickAngleDeg(i, 0f), 0.001f)
        }
        assertEquals(30f, LockOrb.tickAngleDeg(0, 30f), 0.001f)
        assertEquals(0f, LockOrb.tickAngleDeg(0, 360f), 0.001f)
        val majors = (0 until LockOrb.TICK_COUNT).filter { LockOrb.isMajorTick(it) }
        assertEquals(listOf(0, 15, 30, 45), majors)
        assertTrue(LockOrb.tickLengthFrac(0) > LockOrb.tickLengthFrac(5))
        assertTrue(LockOrb.tickLengthFrac(5) > LockOrb.tickLengthFrac(1))
    }

    @Test
    fun litBlocksAreSevenOnFiveOff() {
        for (i in 0..6) assertTrue("tick $i lit", LockOrb.tickLit(i))
        for (i in 7..11) assertFalse("tick $i dark", LockOrb.tickLit(i))
        assertTrue(LockOrb.tickLit(12)) // the next block
        val litCount = (0 until LockOrb.TICK_COUNT).count { LockOrb.tickLit(it) }
        assertEquals(35, litCount) // five blocks of seven
    }

    @Test
    fun lockOrbGating() {
        assertTrue(LockOrb.shouldShow(enabled = true, keyguardLocked = true))
        assertFalse(LockOrb.shouldShow(enabled = true, keyguardLocked = false))
        assertFalse(LockOrb.shouldShow(enabled = false, keyguardLocked = true))
        assertTrue(LockOrb.shouldAnimate(enabled = true, screenOn = true, batteryPct = 50))
        assertTrue(LockOrb.shouldAnimate(enabled = true, screenOn = true, batteryPct = -1)) // unknown animates
        assertFalse(LockOrb.shouldAnimate(enabled = true, screenOn = true, batteryPct = 19)) // battery rule
        assertTrue(LockOrb.shouldAnimate(enabled = true, screenOn = true, batteryPct = 20))
        assertFalse(LockOrb.shouldAnimate(enabled = true, screenOn = false, batteryPct = 50))
        assertFalse(LockOrb.shouldAnimate(enabled = false, screenOn = true, batteryPct = 50))
    }
}
