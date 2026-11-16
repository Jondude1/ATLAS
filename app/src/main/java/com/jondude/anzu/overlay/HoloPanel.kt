package com.jondude.anzu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
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

/** Pure drifting-dust model: motes rise slowly through the light and wrap round. Kept apart so it can be tested. */
class Motes(private val n: Int = 14, seed: Long = 7L) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val speed = FloatArray(n)
    val phase = FloatArray(n)
    val size = FloatArray(n)

    init {
        val r = java.util.Random(seed)
        for (i in 0 until n) {
            x[i] = r.nextFloat(); y[i] = r.nextFloat()
            speed[i] = 0.02f + 0.05f * r.nextFloat()
            phase[i] = r.nextFloat() * 6.28f
            size[i] = 0.6f + 1.4f * r.nextFloat()
        }
    }

    /** Advance by [dt] seconds; positions stay in 0..1. */
    fun step(dt: Float, t: Float) {
        for (i in 0 until n) {
            y[i] -= speed[i] * dt
            if (y[i] < 0f) { y[i] += 1f }
            x[i] = (x[i] + 0.004f * kotlin.math.sin(t * 0.8f + phase[i]) * dt * 10f).let { if (it < 0f) it + 1f else if (it > 1f) it - 1f else it }
        }
    }

    fun alpha(i: Int, t: Float): Float = 0.25f + 0.55f * (0.5f + 0.5f * kotlin.math.sin(t * 1.6f + phase[i]))
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
    private var bootAt = 0L
    private val motes = Motes()
    private val moteP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var lastMote = 0L

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        bootAt = SystemClock.uptimeMillis()
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
            // Boot-in: the frame traces itself over ~0.7 s. p runs 0..1; brackets grow, grid and glass fade up.
            val p = ((SystemClock.uptimeMillis() - bootAt) / 700f).coerceIn(0f, 1f)
            val ease = 1f - (1f - p) * (1f - p)
            fill.alpha = (Color.alpha(Color.parseColor(Holo.FILL)) * ease).toInt()
            grid.alpha = (15 * ease).toInt()
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
            val t = if (p < 1f) p else (SystemClock.uptimeMillis() % 3000L) / 3000f
            c.drawLine(cut, t * h, w - cut, t * h, scan)
            // corner brackets
            val b = 18f * d * (0.3f + 0.7f * ease)
            val m = 1f * d
            c.drawLine(cut, m, m + cut + b, m, bracket); c.drawLine(m + 0f, cut, m, cut + b, bracket)
            c.drawLine(w - cut - b, m, w - cut, m, bracket); c.drawLine(w - m, cut, w - m, cut + b, bracket)
            c.drawLine(cut, h - m, cut + b, h - m, bracket); c.drawLine(m, h - cut - b, m, h - cut, bracket)
            c.drawLine(w - cut - b, h - m, w - cut, h - m, bracket); c.drawLine(w - m, h - cut - b, w - m, h - cut, bracket)
        }
        if (w > 0f && h > 0f) {
            val nowMs = SystemClock.uptimeMillis()
            val dt = if (lastMote == 0L) 0.016f else ((nowMs - lastMote) / 1000f).coerceAtMost(0.1f)
            lastMote = nowMs
            val tt = nowMs / 1000f
            motes.step(dt, tt)
            for (i in motes.x.indices) {
                moteP.color = Color.argb((motes.alpha(i, tt) * 150).toInt(), 160, 245, 255)
                c.drawCircle(motes.x[i] * w, motes.y[i] * h, motes.size[i] * d, moteP)
            }
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

/** Pure helper for segmented gauges: how many of [total] cells light up for [percent]. */
object HoloBar {
    fun cells(percent: Int, total: Int = 10): Int = ((percent.coerceIn(0, 100) * total) + 50) / 100
}

/** A row of small cyan cells, filled left to right, like a reactor power readout. */
class SegmentBar(ctx: Context, private val total: Int = 10) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(Holo.CYAN); style = Paint.Style.FILL }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2200E5FF"); style = Paint.Style.FILL }
    var percent = 0
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val gap = 2f * d
        val cw = (width - gap * (total - 1)) / total
        val lit = HoloBar.cells(percent, total)
        for (i in 0 until total) {
            val l = i * (cw + gap)
            c.drawRect(l, 0f, l + cw, height.toFloat(), if (i < lit) on else off)
        }
    }
}

/** Pure helper for floating info cards: turn a tool's spoken-style text into a few short readout lines. */
object HoloCards {
    fun lines(text: String, max: Int = 5, width: Int = 46): List<String> =
        text.split(Regex("\\n|(?<=[.;])\\s+"))
            .map { it.replace(Regex("\\s+"), " ").trim().trimEnd('.', ';') }
            .filter { it.isNotBlank() }
            .map { if (it.length <= width) it else it.take(width - 1).trimEnd() + "…" }
            .take(max)
}

/** Alert colouring: cyan normally, amber for warnings (door left open), red for emergencies. */
object AlertLevel {
    const val NORMAL = 0
    const val WARN = 1
    const val CRIT = 2

    private val crit = listOf("smoke", "fire", "carbon monoxide", "leak", "flood", "intruder", "break-in", "alarm triggered", "glass break")
    private val warn = listOf("left open", "still open", "unlocked", "open for", "garage", "door open", "window open")

    fun classify(source: String, text: String): Int {
        val t = text.lowercase()
        return when {
            crit.any { t.contains(it) } -> CRIT
            warn.any { t.contains(it) } -> WARN
            else -> NORMAL
        }
    }

    fun colorHex(level: Int) = when (level) {
        CRIT -> "#FF3B30"
        WARN -> "#FFB000"
        else -> Holo.CYAN
    }

    fun holdMs(level: Int) = if (level >= CRIT) 20_000L else 12_000L
}

/** Ambient colour of the screen-edge glow: follows what Atlas is doing and, softly, how Jon sounds. Pure. */
object Ambient {
    fun hex(state: String, mood: com.jondude.anzu.assist.Mood.Kind): String = when {
        mood == com.jondude.anzu.assist.Mood.Kind.STRESSED || mood == com.jondude.anzu.assist.Mood.Kind.LOW -> "#FFB86B"
        mood == com.jondude.anzu.assist.Mood.Kind.UP -> "#7DFFD9"
        state == "thinking" -> "#3D8BFF"
        state == "speaking" -> "#9BF3FF"
        else -> Holo.CYAN
    }
}
