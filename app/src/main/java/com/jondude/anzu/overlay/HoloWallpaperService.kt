package com.jondude.anzu.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.jondude.anzu.Glasses
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live wallpaper: a dark holographic scene with a projected 3D waveform, perspective floor grid and clock.
 * It follows Atlas live, so the lock screen shows him listening, thinking or speaking.
 * Pick it once in the system wallpaper picker.
 */
class HoloWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = HoloEngine()

    private inner class HoloEngine : Engine() {
        private val h = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var visible = false
        private val t0 = SystemClock.uptimeMillis()
        private val tick = object : Runnable { override fun run() { draw(); if (visible) h.postDelayed(this, 33) } }
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            h.removeCallbacks(tick)
            if (v) h.post(tick)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            h.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        private fun draw() {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas() ?: return
                scene(c)
            } catch (_: Throwable) {
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c) } catch (_: Throwable) {}
            }
        }

        private fun mode() = when (Glasses.atlasState.value) {
            "listening" -> OrbMode.LISTENING
            "thinking" -> OrbMode.THINKING
            "speaking" -> OrbMode.SPEAKING
            else -> OrbMode.IDLE
        }

        private fun scene(c: Canvas) {
            val w = c.width.toFloat(); val hh = c.height.toFloat()
            val t = (SystemClock.uptimeMillis() - t0) / 1000f
            val cy = hh * 0.52f
            val cyan = Color.parseColor(Holo.CYAN)
            paint.shader = LinearGradient(0f, 0f, 0f, hh, Color.parseColor("#02090F"), Color.parseColor("#03141C"), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            c.drawRect(0f, 0f, w, hh, paint)
            paint.shader = null

            // Perspective floor grid under the projector.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            val horizon = cy + hh * 0.04f
            for (i in 0..14) {
                val f = ((i + t * 0.4f) % 14f) / 14f
                val y = horizon + (hh - horizon) * f * f
                paint.color = Color.argb((60 * f).toInt(), 0, 229, 255)
                c.drawLine(0f, y, w, y, paint)
            }
            for (i in -8..8) {
                paint.color = Color.argb(40, 0, 229, 255)
                c.drawLine(w / 2f + i * w * 0.02f, horizon, w / 2f + i * w * 0.16f, hh, paint)
            }

            // Projector ring and light cone.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, cy, 0f, cy - hh * 0.30f, Color.argb(70, 0, 229, 255), Color.argb(0, 0, 229, 255), Shader.TileMode.CLAMP)
            val cone = Path().apply { moveTo(w * 0.28f, cy); lineTo(w * 0.72f, cy); lineTo(w * 0.86f, cy - hh * 0.30f); lineTo(w * 0.14f, cy - hh * 0.30f); close() }
            c.drawPath(cone, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.argb(200, 0, 229, 255)
            c.drawOval(w * 0.26f, cy - hh * 0.018f, w * 0.74f, cy + hh * 0.018f, paint)

            // 3D bars standing on the ring.
            val n = 15
            val m = mode()
            val level = if (m == OrbMode.LISTENING) Glasses.micLevel.value else 0f
            val span = w * 0.42f
            paint.style = Paint.Style.FILL
            for (i in 0 until n) {
                val v = WaveMath.target(m, i, n, t, level)
                val x = w / 2f - span / 2f + span * i / (n - 1)
                val bh = hh * 0.22f * (0.12f + v)
                val bw = span / n * 0.55f
                val depth = bw * 0.5f
                paint.color = Color.argb(230, 0, 229, 255)
                c.drawRect(x - bw / 2, cy - bh, x + bw / 2, cy, paint)
                paint.color = Color.argb(150, 120, 245, 255)
                c.drawRect(x - bw / 2 + depth * 0.6f, cy - bh - depth * 0.6f, x + bw / 2 + depth * 0.6f, cy - bh, paint)
                paint.color = Color.argb(35, 0, 229, 255)
                c.drawRect(x - bw / 2, cy, x + bw / 2, cy + bh * 0.4f, paint)   // reflection
            }

            // Clock readout.
            val now = Date()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(235, 220, 250, 255)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            paint.textSize = w * 0.16f
            c.drawText(timeFmt.format(now), w / 2f, hh * 0.18f, paint)
            paint.textSize = w * 0.04f
            paint.color = cyan
            c.drawText(dateFmt.format(now).uppercase(Locale.getDefault()) + "  //  A.T.L.A.S", w / 2f, hh * 0.18f + w * 0.07f, paint)
            // Slow scan line.
            val sy = ((t * 0.12f) % 1f) * hh
            paint.color = Color.argb(30, 0, 229, 255)
            c.drawRect(0f, sy, w, sy + 3f, paint)
        }
    }
}
