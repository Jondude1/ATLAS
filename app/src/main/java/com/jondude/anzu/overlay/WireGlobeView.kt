package com.jondude.anzu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pure sphere maths so the projection can be tested without a device. */
object GlobeMath {
    /** Rotate (x,y,z) about Y by [spin] then about X by [tilt], then apply a mild perspective. Returns (px, py, depth). */
    fun project(x: Float, y: Float, z: Float, spin: Float, tilt: Float): Triple<Float, Float, Float> {
        val x1 = x * cos(spin) + z * sin(spin)
        val z1 = -x * sin(spin) + z * cos(spin)
        val y2 = y * cos(tilt) - z1 * sin(tilt)
        val z2 = y * sin(tilt) + z1 * cos(tilt)
        val persp = 1f / (1f - z2 * 0.22f)
        return Triple(x1 * persp, y2 * persp, z2)
    }
}

/** Wireframe shapes as lists of 3D polylines, so they can be spun and projected the same way. Pure. */
enum class HoloShape { SPHERE, CUBE, TORUS }

object WireShapes {
    fun lines(shape: HoloShape, seg: Int = 40): List<List<FloatArray>> = when (shape) {
        HoloShape.SPHERE -> sphere(seg)
        HoloShape.CUBE -> cube()
        HoloShape.TORUS -> torus(seg)
    }

    fun next(s: HoloShape) = HoloShape.values()[(s.ordinal + 1) % HoloShape.values().size]

    private fun sphere(seg: Int): List<List<FloatArray>> {
        val out = ArrayList<List<FloatArray>>()
        val pi = PI.toFloat()
        for (m in 0 until 8) {
            val lon = m * pi / 8f
            out += (0..seg).map { i -> val phi = i * 2f * pi / seg; floatArrayOf(cos(phi) * cos(lon), sin(phi), cos(phi) * sin(lon)) }
        }
        for (lat in -2..2) {
            val la = lat * pi / 6f
            out += (0..seg).map { i -> val th = i * 2f * pi / seg; floatArrayOf(cos(la) * cos(th), sin(la), cos(la) * sin(th)) }
        }
        return out
    }

    private fun cube(): List<List<FloatArray>> {
        val k = 0.72f
        val v = { x: Int, y: Int, z: Int -> floatArrayOf(x * k, y * k, z * k) }
        val out = ArrayList<List<FloatArray>>()
        for (a in listOf(-1, 1)) for (b in listOf(-1, 1)) {
            out += listOf(v(-1, a, b), v(1, a, b))
            out += listOf(v(a, -1, b), v(a, 1, b))
            out += listOf(v(a, b, -1), v(a, b, 1))
        }
        return out
    }

    private fun torus(seg: Int): List<List<FloatArray>> {
        val out = ArrayList<List<FloatArray>>()
        val pi = PI.toFloat()
        val big = 0.62f; val small = 0.28f
        for (m in 0 until 12) {
            val u = m * 2f * pi / 12f
            out += (0..seg).map { i -> val v = i * 2f * pi / seg; floatArrayOf((big + small * cos(v)) * cos(u), small * sin(v), (big + small * cos(v)) * sin(u)) }
        }
        for (n in 0 until 5) {
            val v = n * 2f * pi / 5f
            out += (0..seg).map { i -> val u = i * 2f * pi / seg; floatArrayOf((big + small * cos(v)) * cos(u), small * sin(v), (big + small * cos(v)) * sin(u)) }
        }
        return out
    }
}

/**
 * A rotating wireframe globe with real 3D perspective: meridians and parallels rotate around a tilted
 * axis, front lines bright and back lines faint. Spins faster while thinking and swells with your voice.
 */
class WireGlobeView(ctx: Context) : View(ctx) {
    var mode: OrbMode = OrbMode.IDLE
    @Volatile var level = 0f
    private var tint = Color.parseColor(Holo.CYAN)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var running = false
    private var lastFrame = 0L
    private var spin = 0f
    private var speed = 0.3f
    private var swell = 0f
    private var time = 0f
    var shape = HoloShape.SPHERE
    private var userTilt = 0f
    private var spinVel = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var polys: List<List<FloatArray>> = WireShapes.lines(HoloShape.SPHERE)

    fun setAlert(level: Int) { tint = Color.parseColor(AlertLevel.colorHex(level)) }

    fun playAttention() { swell = 1f }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun a(alpha: Float) = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint))

    override fun onDraw(c: Canvas) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        time += dt
        val target = when (mode) {
            OrbMode.THINKING -> 1.7f
            OrbMode.SPEAKING -> 0.8f
            OrbMode.LISTENING -> 0.55f
            else -> 0.3f
        }
        speed += (target - speed) * min(1f, dt * 3f)
        spin += (speed + spinVel) * dt
        spinVel *= (1f - min(1f, dt * 2.5f))
        swell += (((if (mode == OrbMode.LISTENING) level else 0f)) - swell) * min(1f, dt * 10f)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.36f * (1f + 0.10f * swell)
        val tilt = 0.42f + 0.05f * sin(time * 0.7f)
        paint.strokeWidth = maxOf(1f, r * 0.02f)

        fill.color = a(0.10f)
        c.drawCircle(cx, cy, r * 1.05f, fill)
        paint.color = a(0.35f)
        c.drawCircle(cx, cy, r * 1.05f, paint)

        for ((idx, poly) in polys.withIndex()) {
            var prev: Triple<Float, Float, Float>? = null
            for (pt in poly) {
                val p = GlobeMath.project(pt[0], pt[1], pt[2], spin, tilt + userTilt)
                prev?.let { q -> line(c, cx, cy, r, q, p, if (shape == HoloShape.SPHERE && idx == 8 + 2) 1.6f else 1f) }
                prev = p
            }
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (Math.abs(e.x - downX) > 12f || Math.abs(e.y - downY) > 12f) dragged = true
                spin += dx * 0.012f
                userTilt = (userTilt + dy * 0.01f).coerceIn(-1.2f, 1.2f)
                spinVel = dx * 0.012f * 30f
                lastX = e.x; lastY = e.y
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    shape = WireShapes.next(shape)
                    polys = WireShapes.lines(shape)
                    swell = 1f
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun line(c: Canvas, cx: Float, cy: Float, r: Float, p: Triple<Float, Float, Float>, q: Triple<Float, Float, Float>, boost: Float = 1f) {
        val depth = (p.third + q.third) / 2f
        paint.color = a((0.10f + 0.55f * (depth + 1f) / 2f) * boost)
        c.drawLine(cx + p.first * r, cy - p.second * r, cx + q.first * r, cy - q.second * r, paint)
    }
}
