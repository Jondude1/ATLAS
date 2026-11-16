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
        spin += speed * dt
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

        val seg = 40
        // meridians: great circles through the poles
        for (m in 0 until 8) {
            val lon = m * PI.toFloat() / 8f
            var prev: Triple<Float, Float, Float>? = null
            for (i in 0..seg) {
                val phi = i * 2f * PI.toFloat() / seg
                val p = GlobeMath.project(cos(phi) * cos(lon), sin(phi), cos(phi) * sin(lon), spin, tilt)
                prev?.let { q -> line(c, cx, cy, r, q, p) }
                prev = p
            }
        }
        // parallels
        for (lat in -2..2) {
            val la = lat * PI.toFloat() / 6f
            var prev: Triple<Float, Float, Float>? = null
            for (i in 0..seg) {
                val th = i * 2f * PI.toFloat() / seg
                val p = GlobeMath.project(cos(la) * cos(th), sin(la), cos(la) * sin(th), spin, tilt)
                prev?.let { q -> line(c, cx, cy, r, q, p, if (lat == 0) 1.6f else 1f) }
                prev = p
            }
        }
    }

    private fun line(c: Canvas, cx: Float, cy: Float, r: Float, p: Triple<Float, Float, Float>, q: Triple<Float, Float, Float>, boost: Float = 1f) {
        val depth = (p.third + q.third) / 2f
        paint.color = a((0.10f + 0.55f * (depth + 1f) / 2f) * boost)
        c.drawLine(cx + p.first * r, cy - p.second * r, cx + q.first * r, cy - q.second * r, paint)
    }
}
