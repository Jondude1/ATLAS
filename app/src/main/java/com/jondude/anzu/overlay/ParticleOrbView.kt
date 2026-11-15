package com.jondude.anzu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING, ATTENTION }

/** Atlas's companion orb: soft points drifting inside a sphere, wrapped by thin rings. Plain Canvas, no Compose. */
class ParticleOrbView(ctx: Context, private val pointCount: Int = 110) : View(ctx) {
    var mode: OrbMode = OrbMode.IDLE
        set(v) {
            if (field == v) return
            field = v
            modeSince = SystemClock.uptimeMillis()
            kick()
        }

    private class P(var x: Float, var y: Float, val orange: Boolean, val size: Float, val seed: Float) {
        var vx = 0f
        var vy = 0f
    }

    private val rnd = Random(7)
    private val pts = Array(pointCount) {
        val r = sqrt(rnd.nextFloat()) // uniform over the disc area
        val a = rnd.nextFloat() * 2 * PI.toFloat()
        P(r * cos(a), r * sin(a), rnd.nextFloat() < 0.15f, 0.032f + rnd.nextFloat() * 0.042f, rnd.nextFloat() * 6.28f)
    }
    private val ringAngle = floatArrayOf(0f, 1.7f, 3.4f)
    private val ringSpeed = floatArrayOf(0.35f, -0.25f, 0.18f) // rad/s at full speed
    private val orange = Color.parseColor("#FF5C00")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    private var modeSince = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private var running = false
    private var time = 0f
    private var ripplePhase = 0f
    private var attentionStart = -1L
    private var bounceStart = -1L
    private var shakeStart = -1L
    private var swirl = 0.2f // smoothed state intensity values
    private var gather = 0f
    private var ringGlow = 0f // 0 white .. 1 orange

    fun playAttention() {
        attentionStart = SystemClock.uptimeMillis()
        kick()
    }

    fun playBounce() {
        bounceStart = SystemClock.uptimeMillis()
        kick()
    }

    fun playShake() {
        shakeStart = SystemClock.uptimeMillis()
        kick()
    }

    private fun kick() {
        if (isAttachedToWindow && !running) {
            running = true
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
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
            lastFrame = 0L
            kick()
        } else running = false
    }

    private fun attentionActive(now: Long) = attentionStart >= 0 && now - attentionStart < ATTENTION_MS

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val idleCap = mode == OrbMode.IDLE && !attentionActive(now) && bounceStart < 0 && shakeStart < 0
        if (idleCap && lastFrame != 0L && now - lastFrame < 50) { // ~20 fps when idle
            postInvalidateOnAnimation()
            return
        }
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        time += dt
        val attn = attentionActive(now)
        if (attentionStart >= 0 && !attn) attentionStart = -1L
        step(dt, attn, now)
        render(canvas, now, attn)
        postInvalidateOnAnimation()
    }

    private fun step(dt: Float, attn: Boolean, now: Long) {
        val m = mode
        val targetSwirl = when {
            attn -> 0.6f
            m == OrbMode.THINKING -> 1.4f
            m == OrbMode.LISTENING -> 0.5f
            m == OrbMode.SPEAKING -> 0.7f
            else -> 0.2f
        }
        val targetGather = when {
            attn -> 1f
            m == OrbMode.THINKING -> 0.55f
            else -> 0f
        }
        val k = min(1f, dt * 4f)
        swirl += (targetSwirl - swirl) * k
        gather += (targetGather - gather) * k
        ringGlow += ((if (m == OrbMode.LISTENING) 1f else if (attn) 0.8f else 0f) - ringGlow) * k
        val rs = when {
            m == OrbMode.IDLE && !attn -> 0.08f
            m == OrbMode.THINKING -> 2.2f
            else -> 1f
        }
        for (i in ringAngle.indices) {
            // thinking: rings counter-rotate (every other ring reverses)
            val flip = if (m == OrbMode.THINKING && i % 2 == 0) -1f else 1f
            ringAngle[i] += ringSpeed[i] * flip * rs * dt
        }
        ripplePhase += dt * 0.9f
        for (p in pts) {
            val r = sqrt(p.x * p.x + p.y * p.y)
            // Brownian jitter + tangential swirl + weak pull to core (stronger when gathering).
            p.vx += (rnd.nextFloat() - 0.5f) * 1.2f * dt
            p.vy += (rnd.nextFloat() - 0.5f) * 1.2f * dt
            val sw = swirl * 0.35f
            p.vx += -p.y * sw * dt
            p.vy += p.x * sw * dt
            val pull = 0.15f + gather * 1.8f
            p.vx -= p.x * pull * dt
            p.vy -= p.y * pull * dt
            val damp = 1f - min(0.95f, dt * 2.2f)
            p.vx *= damp
            p.vy *= damp
            p.x += p.vx * dt
            p.y += p.vy * dt
            val nr = sqrt(p.x * p.x + p.y * p.y)
            if (nr > 0.92f) { // keep inside the sphere
                p.x *= 0.92f / nr
                p.y *= 0.92f / nr
                p.vx *= -0.3f
                p.vy *= -0.3f
            }
            if (r.isNaN()) { p.x = 0f; p.y = 0f }
        }
    }

    private fun pulse(now: Long, attn: Boolean): Float {
        val t = time
        return when {
            attn -> {
                val e = (now - attentionStart).toFloat()
                // two quick bright pulses ~250 ms apart
                fun bump(c: Float) = max(0f, 1f - abs(e - c) / 110f)
                max(bump(120f), bump(370f))
            }
            mode == OrbMode.LISTENING -> 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 1.2f)
            mode == OrbMode.SPEAKING -> {
                // voice-like envelope: ~3.5 Hz syllables modulated by a slower phrase wobble
                val syl = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 3.5f)
                val phrase = 0.65f + 0.35f * sin(t * 2f * PI.toFloat() * 0.8f + 1f)
                syl * phrase
            }
            mode == OrbMode.THINKING -> 0.5f + 0.25f * sin(t * 9f) * sin(t * 3.1f)
            else -> 0f
        }
    }

    private fun render(c: Canvas, now: Long, attn: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val base = min(w, h) / 2f
        val pl = pulse(now, attn)
        val idle = mode == OrbMode.IDLE && !attn
        val breath = if (idle) 1f + 0.03f * sin(time * 1.3f) else 1f
        val alphaMul = if (idle) 0.8f else 1f

        c.save()
        // reaction transforms
        var sx = 1f
        var sy = 1f
        var ty = 0f
        var tx = 0f
        if (bounceStart >= 0) {
            val e = (now - bounceStart) / BOUNCE_MS
            if (e >= 1f) bounceStart = -1L else {
                val d = (1f - e)
                val s = sin(e * PI.toFloat() * 3f) * d
                ty = -abs(s) * base * 0.35f
                sy = 1f + 0.15f * s
                sx = 1f - 0.12f * s
            }
        }
        if (shakeStart >= 0) {
            val e = (now - shakeStart) / SHAKE_MS
            if (e >= 1f) shakeStart = -1L else tx = sin(e * PI.toFloat() * 8f) * (1f - e) * base * 0.12f
        }
        c.translate(cx + tx, cy + ty)
        c.scale(sx * breath * (1f + 0.04f * pl), sy * breath * (1f + 0.04f * pl))
        val R = base * 0.74f // sphere radius leaves room for rings and ripples

        // core glow / flicker
        val flick = if (mode == OrbMode.THINKING) 0.5f + 0.5f * sin(time * 17f) * sin(time * 5.3f) else 0f
        val coreA = ((0.24f + 0.22f * pl + 0.12f * flick + gather * 0.15f) * alphaMul).coerceIn(0f, 1f)
        val coreCol = if (mode == OrbMode.LISTENING || attn) orange else Color.WHITE
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(0f, 0f, R, withAlpha(coreCol, coreA), withAlpha(coreCol, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, R, paint)
        paint.shader = null

        // points
        for (p in pts) {
            val r = sqrt(p.x * p.x + p.y * p.y)
            val tw = 0.75f + 0.25f * sin(time * 2f + p.seed)
            val a = ((0.55f + 0.35f * (1f - r)) * tw * alphaMul).coerceIn(0f, 1f)
            val col = if (p.orange) orange else Color.WHITE
            val rad = R * p.size * (1f + 0.5f * pl) * (if (p.orange && attn) 1.6f else 1f)
            paint.color = withAlpha(col, a * 0.35f)
            c.drawCircle(p.x * R, p.y * R, rad * 2.6f, paint) // soft halo
            paint.color = withAlpha(col, a)
            c.drawCircle(p.x * R, p.y * R, rad, paint)
        }

        // rings
        paint.style = Paint.Style.STROKE
        val radii = floatArrayOf(R * 1.08f, R * 1.20f, R * 1.32f)
        for (i in radii.indices) {
            val rr = radii[i] * (1f + 0.02f * pl)
            val ra = (if (i == 0) 0.38f else 0.24f) * alphaMul
            val col = lerpColor(Color.WHITE, orange, if (i == 0) ringGlow else ringGlow * 0.6f)
            paint.strokeWidth = max(1f, base * 0.025f)
            paint.color = withAlpha(col, (ra + 0.3f * ringGlow + 0.2f * pl).coerceIn(0f, 1f))
            arc.set(-rr, -rr, rr, rr)
            val rot = Math.toDegrees(ringAngle[i].toDouble()).toFloat()
            // broken ring: three long arcs with gaps, rotating
            for (s in 0 until 3) c.drawArc(arc, rot + s * 120f, 95f - i * 12f, false, paint)
            // subtle tick arcs
            paint.strokeWidth = max(1f, base * 0.012f)
            paint.color = withAlpha(Color.WHITE, 0.18f * alphaMul)
            for (t in 0 until 6) c.drawArc(arc, -rot * 0.7f + t * 60f + 20f, 3f, false, paint)
        }

        // speaking ripples
        if (mode == OrbMode.SPEAKING) {
            paint.strokeWidth = max(1f, base * 0.02f)
            for (i in 0 until 3) {
                val f = (ripplePhase * 1.4f + i / 3f) % 1f
                val rr = R * (1f + f * 0.75f)
                paint.color = withAlpha(orange, (1f - f) * 0.55f)
                c.drawCircle(0f, 0f, rr, paint)
            }
        }
        // attention flash ring
        if (attn) {
            paint.strokeWidth = max(1.5f, base * 0.035f)
            paint.color = withAlpha(orange, pl * 0.9f)
            c.drawCircle(0f, 0f, R * (1.0f + 0.1f * pl), paint)
        }
        paint.style = Paint.Style.FILL
        c.restore()
    }

    private fun withAlpha(col: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(col), Color.green(col), Color.blue(col))

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    private companion object {
        const val ATTENTION_MS = 900L
        const val BOUNCE_MS = 700f
        const val SHAKE_MS = 450f
    }
}
