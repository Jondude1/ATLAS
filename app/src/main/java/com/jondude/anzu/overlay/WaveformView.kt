package com.jondude.anzu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pure waveform shaping so it can be unit-tested: target height 0..1 for bar [i] of [n]. */
object WaveMath {
    fun target(mode: OrbMode, i: Int, n: Int, t: Float, level: Float, attn: Float = 0f): Float {
        val c = (n - 1) / 2f
        val centre = 1f - 0.45f * abs(i - c) / max(c, 1f)
        val v = when (mode) {
            // Follows the real voice, with a faint resting breath so it never looks dead.
            OrbMode.LISTENING -> 0.14f + 0.86f * level.coerceIn(0f, 1f) * centre * (0.6f + 0.4f * sin(t * 9f + i * 1.3f))
            // A bright pulse scans left to right while it works.
            OrbMode.THINKING -> {
                val p = (t * 1.7f % 1f) * (n + 2) - 1f
                0.14f + 0.75f * exp(-((i - p) * (i - p)) / 1.6f)
            }
            OrbMode.SPEAKING -> 0.2f + 0.65f * abs(sin(t * 6.2f + i * 0.9f)) * (0.6f + 0.4f * sin(t * 1.7f)) * centre
            else -> 0.12f + 0.07f * sin(t * 1.4f + i * 0.7f) + 0.03f * sin(t * 0.5f - i)
        }
        return max(v, attn * (0.9f - 0.3f * abs(i - c) / max(c, 1f))).coerceIn(0.04f, 1f)
    }
}

/**
 * Atlas's presence: a small holographic waveform. Each bar is an extruded prism (front, side and top
 * faces) standing on a glowing projector ring with a faint light cone and a mirrored reflection, and the
 * whole thing slowly yaws so it reads as a 3D projection. Plain Canvas, no Compose.
 */
class WaveformView(ctx: Context, private val bars: Int = 9) : View(ctx) {
    var mode: OrbMode = OrbMode.IDLE
        set(v) {
            if (field == v) return
            field = v
            kick()
        }

    /** Live mic loudness 0..1; only drives the bars while LISTENING. */
    @Volatile var level = 0f

    private var tint = Color.parseColor(Holo.CYAN)
    private val heights = FloatArray(bars) { 0.12f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private var running = false
    private var lastFrame = 0L
    private var time = 0f
    private var attnStart = -1L
    private var beam: Shader? = null
    private var beamKey = 0

    fun setAlert(level: Int) {
        tint = Color.parseColor(AlertLevel.colorHex(level))
        beam = null
    }

    fun playAttention() {
        attnStart = SystemClock.uptimeMillis()
        kick()
    }

    fun playBounce() = playAttention()

    fun playShake() = playAttention()

    private fun kick() {
        if (isAttachedToWindow && !running) {
            running = true
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            running = true
            postInvalidateOnAnimation()
        } else running = false
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val idle = mode == OrbMode.IDLE && attnStart < 0
        if (idle && lastFrame != 0L && now - lastFrame < 33) { // ~30 fps at rest
            postInvalidateOnAnimation()
            return
        }
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        time += dt
        var attn = 0f
        if (attnStart >= 0) {
            val e = (now - attnStart) / 900f
            if (e >= 1f) attnStart = -1L else attn = 1f - e
        }
        val k = min(1f, dt * 14f)
        for (i in 0 until bars) heights[i] += (WaveMath.target(mode, i, bars, time, level, attn) - heights[i]) * k
        render(canvas)
        postInvalidateOnAnimation()
    }

    private fun a(col: Int, alpha: Float) = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(col), Color.green(col), Color.blue(col))

    private fun mixWhite(col: Int, t: Float) = Color.rgb(
        (Color.red(col) + (255 - Color.red(col)) * t).toInt(),
        (Color.green(col) + (255 - Color.green(col)) * t).toInt(),
        (Color.blue(col) + (255 - Color.blue(col)) * t).toInt()
    )

    private fun render(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val rowW = w * 0.8f
        val pitch = rowW / bars
        val bw = pitch * 0.52f
        val maxH = h * 0.36f
        val baseY = h * 0.58f
        val depth = bw * 0.75f
        val sway = 0.5f + 0.5f * sin(time * 0.55f)       // slow yaw 0..1
        val dx = depth * (0.25f + 0.7f * sway)
        val dy = -depth * 0.55f
        val flick = 0.94f + 0.06f * sin(time * 31f)      // faint projector flicker
        val fy = baseY + h * 0.05f
        val rx = rowW * 0.6f
        val ry = h * 0.07f

        // projector floor: glow disc, two rings
        paint.style = Paint.Style.FILL
        paint.color = a(tint, 0.10f * flick)
        oval.set(cx - rx, fy - ry, cx + rx, fy + ry)
        c.drawOval(oval, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, h * 0.012f)
        paint.color = a(tint, 0.5f * flick)
        c.drawOval(oval, paint)
        paint.color = a(tint, 0.28f * flick)
        oval.set(cx - rx * 0.62f, fy - ry * 0.62f, cx + rx * 0.62f, fy + ry * 0.62f)
        c.drawOval(oval, paint)

        // light cone rising from the emitter
        val key = (h * 100).toInt() * 31 + tint
        if (beam == null || beamKey != key) {
            beam = LinearGradient(0f, baseY - maxH * 1.25f, 0f, fy, a(tint, 0f), a(tint, 0.16f), Shader.TileMode.CLAMP)
            beamKey = key
        }
        paint.style = Paint.Style.FILL
        paint.shader = beam
        path.reset()
        path.moveTo(cx - rx * 0.9f, fy)
        path.lineTo(cx - rx * 0.55f, baseY - maxH * 1.25f)
        path.lineTo(cx + rx * 0.55f, baseY - maxH * 1.25f)
        path.lineTo(cx + rx * 0.9f, fy)
        path.close()
        c.drawPath(path, paint)
        paint.shader = null

        // bars, left to right
        for (i in 0 until bars) {
            val x = cx - rowW / 2f + i * pitch + (pitch - bw) / 2f
            val bh = max(h * 0.02f, heights[i] * maxH)
            val top = baseY - bh
            val glow = 0.5f + 0.4f * heights[i]

            // mirrored reflection in three fading slices
            val rh = bh * 0.45f
            for (s in 0 until 3) {
                paint.color = a(tint, (0.22f - 0.08f * s) * flick)
                c.drawRect(x, baseY + rh * s / 3f, x + bw, baseY + rh * (s + 1) / 3f, paint)
            }
            // halo
            paint.color = a(tint, 0.10f * glow * flick)
            c.drawRect(x - bw * 0.6f, top - bw * 0.3f, x + bw * 1.6f, baseY, paint)
            // right face
            paint.color = a(tint, 0.30f * flick)
            path.reset()
            path.moveTo(x + bw, top)
            path.lineTo(x + bw + dx, top + dy)
            path.lineTo(x + bw + dx, baseY + dy)
            path.lineTo(x + bw, baseY)
            path.close()
            c.drawPath(path, paint)
            // front face
            paint.color = a(tint, glow * flick)
            c.drawRect(x, top, x + bw, baseY, paint)
            // top face, brightest
            paint.color = a(mixWhite(tint, 0.55f), 0.9f * flick)
            path.reset()
            path.moveTo(x, top)
            path.lineTo(x + bw, top)
            path.lineTo(x + bw + dx, top + dy)
            path.lineTo(x + dx, top + dy)
            path.close()
            c.drawPath(path, paint)
        }

        // scanlines across the projection
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = a(tint, 0.07f)
        var y = baseY - maxH * 1.3f
        while (y < fy + ry) {
            c.drawLine(cx - rx, y, cx + rx, y, paint)
            y += max(3f, h * 0.035f)
        }
        paint.style = Paint.Style.FILL
    }
}
