package com.jondude.anzu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.widget.LinearLayout

/** One place for the holographic look, so the whole HUD can be re-tinted by editing three numbers. */
object Holo {
    const val CYAN = "#00E5FF"
    const val CYAN_DIM = "#9900E5FF"
    const val CYAN_FAINT = "#3300E5FF"
    const val FILL = "#E0031A24"
    const val ACCENT = "#FF5C00"

    /** Uppercase, spaced readout text: "listening" -> "LISTENING". */
    fun tag(s: String) = s.trim().uppercase()
}

/**
 * A Stark-style hologram frame: dark teal glass with cut corners, a faint grid, bright corner
 * brackets, and a scan line that sweeps down the panel. Children are laid out as a normal column.
 */
class HoloPanel(ctx: Context) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(Holo.FILL); style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(Holo.CYAN_DIM); style = Paint.Style.STROKE; strokeWidth = d }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(Holo.CYAN); style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.SQUARE
    }
    private val grid = Paint().apply { color = Color.parseColor("#0F00E5FF"); strokeWidth = d }
    private val scan = Paint().apply { color = Color.parseColor("#3300E5FF"); strokeWidth = 2f * d }
    private val frame = Path()
    private var running = false

    init {
        setWillNotDraw(false)
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

    override fun dispatchDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f) {
            val cut = 14f * d
            frame.reset()
            frame.moveTo(cut, 0f); frame.lineTo(w - cut, 0f); frame.lineTo(w, cut)
            frame.lineTo(w, h - cut); frame.lineTo(w - cut, h); frame.lineTo(cut, h)
            frame.lineTo(0f, h - cut); frame.lineTo(0f, cut); frame.close()
            c.drawPath(frame, fill)
            // faint grid
            var y = 12f * d
            while (y < h) { c.drawLine(0f, y, w, y, grid); y += 12f * d }
            c.drawPath(frame, edge)
            // scan line sweeps top to bottom every 3 s
            val t = (SystemClock.uptimeMillis() % 3000L) / 3000f
            c.drawLine(cut, t * h, w - cut, t * h, scan)
            // corner brackets
            val b = 18f * d
            val m = 1f * d
            c.drawLine(cut, m, m + cut + b, m, bracket); c.drawLine(m + 0f, cut, m, cut + b, bracket)
            c.drawLine(w - cut - b, m, w - cut, m, bracket); c.drawLine(w - m, cut, w - m, cut + b, bracket)
            c.drawLine(cut, h - m, cut + b, h - m, bracket); c.drawLine(m, h - cut - b, m, h - cut, bracket)
            c.drawLine(w - cut - b, h - m, w - cut, h - m, bracket); c.drawLine(w - m, h - cut - b, w - m, h - cut, bracket)
        }
        super.dispatchDraw(c)
        if (running) postInvalidateOnAnimation()
    }
}

/** Pure helpers for the speech bubble, kept apart from the views so they can be unit tested. */
object HoloBubble {
    /** Collapse whitespace and cap the length so a long answer never covers the screen. */
    fun shorten(text: String, max: Int = 220): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /** Roughly reading time: 2.5 s plus 55 ms a character, held between 3 and 9 seconds. */
    fun holdMs(text: String): Long = (2_500L + 55L * text.length).coerceIn(3_000L, 9_000L)
}
