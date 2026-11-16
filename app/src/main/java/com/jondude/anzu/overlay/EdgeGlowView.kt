package com.jondude.anzu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * A thin band of light running around the whole screen edge, like a helmet display. It pulses while
 * Atlas listens or thinks and changes colour with alerts. Touch-transparent; only on screen while active.
 */
class EdgeGlowView(ctx: Context) : View(ctx) {
    var colorHex: String = Holo.CYAN
        set(v) { if (field != v) { field = v; shaders = null } }
    @Volatile var drive = 0f          // 0..1 extra brightness, e.g. mic loudness
    var pulseHz = 1.2f

    private val thick = 16f * ctx.resources.displayMetrics.density
    private val paint = Paint()
    private var shaders: Array<Shader>? = null
    private var sizeKey = 0
    private var running = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (!running) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val key = (w * 7 + h).toInt()
        if (shaders == null || key != sizeKey) {
            sizeKey = key
            val on = Color.parseColor(colorHex)
            val off = Color.argb(0, Color.red(on), Color.green(on), Color.blue(on))
            shaders = arrayOf(
                LinearGradient(0f, 0f, 0f, thick, on, off, Shader.TileMode.CLAMP),          // top
                LinearGradient(0f, h, 0f, h - thick, on, off, Shader.TileMode.CLAMP),       // bottom
                LinearGradient(0f, 0f, thick, 0f, on, off, Shader.TileMode.CLAMP),          // left
                LinearGradient(w, 0f, w - thick, 0f, on, off, Shader.TileMode.CLAMP)        // right
            )
        }
        val t = SystemClock.uptimeMillis() / 1000f
        val pulse = 0.55f + 0.45f * sin(2f * PI.toFloat() * pulseHz * t)
        paint.alpha = ((0.22f + 0.5f * pulse + 0.4f * drive).coerceIn(0f, 1f) * 255).toInt()
        val sh = shaders ?: return
        paint.shader = sh[0]; c.drawRect(0f, 0f, w, thick, paint)
        paint.shader = sh[1]; c.drawRect(0f, h - thick, w, h, paint)
        paint.shader = sh[2]; c.drawRect(0f, 0f, thick, h, paint)
        paint.shader = sh[3]; c.drawRect(w - thick, 0f, w, h, paint)
        postInvalidateOnAnimation()
    }
}
