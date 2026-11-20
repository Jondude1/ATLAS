package com.jondude.anzu.launcher

import kotlin.math.sqrt

/**
 * The living sphere's math (v1.2.19 orb density upgrade): pure and unit-tested, shared
 * by the home orb and the lock-screen orb — ONE sphere implementation, not two copies.
 *
 * Jon's call (2026-11-20): the old 260-point sphere "feels too sparse." This is the
 * dense L3-centre field instead: [POINT_COUNT] fine Fibonacci points, depth-shaded
 * blue→cyan, brighter and denser toward the projected rim with a soft glowing edge.
 *
 * Render cost is solved honestly: the point set is precomputed ONCE into a flat
 * FloatArray; a frame is one O(n) rotate into a reusable scratch array and two
 * unsorted draw passes (back half, then front half) — no per-frame allocation, no
 * per-frame sort. [POINT_COUNT] is the cap chosen to hold frame rate on the Moto G
 * Power while reading as thousands of points at orb size.
 */
object SphereMath {

    /** The shipped density: 2,600 points (10× the old sphere), precomputed once. */
    const val POINT_COUNT = 2600

    /** Fibonacci sphere: [count] evenly spread points on the unit sphere, flat [x0,y0,z0, x1,y1,z1, …]. */
    fun fibonacci(count: Int): FloatArray {
        val out = FloatArray(count * 3)
        val golden = Math.PI * (3.0 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1.0 - (i.toDouble() / (count - 1)) * 2.0
            val rad = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = golden * i
            out[i * 3] = (kotlin.math.cos(theta) * rad).toFloat()
            out[i * 3 + 1] = y.toFloat()
            out[i * 3 + 2] = (kotlin.math.sin(theta) * rad).toFloat()
        }
        return out
    }

    /**
     * Rotate one point: around Y by the drift angle (cosR/sinR), then a fixed tilt
     * around X (cosT/sinT). Writes [x, y, z] into [out] at [offset]. Rotation is an
     * isometry — the point stays on the unit sphere.
     */
    fun rotateInto(
        x: Float, y: Float, z: Float,
        cosR: Float, sinR: Float, cosT: Float, sinT: Float,
        out: FloatArray, offset: Int
    ) {
        val x1 = x * cosR + z * sinR
        val z1 = -x * sinR + z * cosR
        out[offset] = x1
        out[offset + 1] = y * cosT - z1 * sinT
        out[offset + 2] = y * sinT + z1 * cosT
    }

    /** Depth of a rotated point: 0 at the back of the sphere, 1 at the front. */
    fun depth(z: Float): Float = ((z + 1f) / 2f).coerceIn(0f, 1f)

    /** Projected distance from the disc centre: 0 centre, 1 at the rim. */
    fun radial(x: Float, y: Float): Float = sqrt(x * x + y * y).coerceIn(0f, 1f)

    /** Rim glow: smoothstep of the radial distance — the L3 edge burn, 0 at centre, 1 at the rim. */
    fun rimGlow(radial: Float): Float {
        val r = radial.coerceIn(0f, 1f)
        return r * r * (3f - 2f * r)
    }

    /** A point's alpha: depth shading (front brighter) lifted toward the rim, never above 1. */
    fun alpha(depth: Float, radial: Float): Float =
        ((0.16f + 0.84f * depth) * (0.72f + 0.5f * rimGlow(radial))).coerceIn(0f, 1f)

    /** A point's radius in dp (before dp→px): fine points, front and rim slightly larger. */
    fun radiusDp(depth: Float, radial: Float): Float =
        0.55f + 0.85f * depth + 0.35f * rimGlow(radial)
}
