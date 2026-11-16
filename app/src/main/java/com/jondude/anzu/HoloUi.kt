package com.jondude.anzu

import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jondude.anzu.assist.Discretion
import com.jondude.anzu.overlay.Holo
import com.jondude.anzu.overlay.OrbMode
import com.jondude.anzu.overlay.WaveformView

/**
 * The app's side of the hologram: one palette, read from the overlay's [Holo] values so the two stay a
 * single look. Every colour passes through [gray], so grayscale mode (after bedtime) collapses the cyan
 * to neutral exactly as it already flattens the orange.
 */
object Stark {
    /** Set from the activity each composition, like the app's other light/dark switch. */
    var dark by mutableStateOf(true)

    private fun hex(s: String) = Color(AColor.parseColor(s))

    /** Rec. 709 luminance, same alpha. */
    fun gray(c: Color): Color {
        if (!Discretion.grayscale()) return c
        val l = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        return Color(l, l, l, c.alpha)
    }

    val cyan: Color get() = gray(if (dark) hex(Holo.CYAN) else Color(0xFF00788A))
    val cyanDim: Color get() = gray(if (dark) hex(Holo.CYAN_DIM) else Color(0x9900788A))
    val cyanFaint: Color get() = gray(if (dark) hex(Holo.CYAN_FAINT) else Color(0x3300788A))
    val ground: Color get() = gray(if (dark) Color(0xFF02070A) else Color(0xFFF1F9FB))
    val panel: Color get() = gray(if (dark) Color(0xFF0A1F29) else Color(0xFFE2F1F5))
    val fill: Color get() = gray(if (dark) Color(0xFF0C2733) else Color(0xFFD6EBF1))
    val hairline: Color get() = gray(if (dark) Color(0xFF123846) else Color(0xFFB5D9E3))
    val text: Color get() = gray(if (dark) Color(0xFFE8FBFF) else Color(0xFF021218))
    val muted: Color get() = gray(if (dark) Color(0xFF7C9CA6) else Color(0xFF55747E))
    /** The card glass: the overlay's FILL, translucent. */
    val glass: Color get() = gray(if (dark) hex(Holo.FILL) else Color(0xE6EAF6F9))
}

/** Square corner brackets in [color], [len] long, drawn inside the bounds. */
private fun DrawScope.brackets(color: Color, len: Dp, width: Dp) {
    val l = len.toPx(); val w = width.toPx(); val h = w / 2
    val s = Stroke(width = w, cap = StrokeCap.Square)
    val x0 = h; val y0 = h; val x1 = size.width - h; val y1 = size.height - h
    fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(cx, cy), Offset(cx + dx * l, cy), s.width, StrokeCap.Square)
        drawLine(color, Offset(cx, cy), Offset(cx, cy + dy * l), s.width, StrokeCap.Square)
    }
    corner(x0, y0, 1f, 1f); corner(x1, y0, -1f, 1f); corner(x0, y1, 1f, -1f); corner(x1, y1, -1f, -1f)
}

/** The one card: translucent dark glass, a 1dp dim-cyan hairline and full-cyan square corner brackets. */
@Composable
fun HoloCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass = Stark.glass; val edge = Stark.cyanDim; val br = Stark.cyan
    Box(
        modifier.background(glass).border(1.dp, edge)
            .drawBehind { brackets(br, 10.dp, 2.dp) }
    ) { content() }
}

/** A section header: a cyan tick, then the label in spaced uppercase mono. */
@Composable
fun HoloHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 2.dp).background(Stark.cyan))
        Text(
            text.uppercase(), color = Stark.cyan, fontFamily = HoloMono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 2.4.sp, modifier = Modifier.padding(start = 8.dp)
        )
    }
}

internal val HoloMono = FontFamily(
    androidx.compose.ui.text.font.Font(R.font.space_mono_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(R.font.space_mono_bold, FontWeight.Bold)
)

/** Atlas's presence at the top of Home: the overlay's waveform, driven by the same state string. */
@Composable
fun PresenceHeader(state: String, modifier: Modifier = Modifier) {
    val mode = when (state) {
        "listening" -> OrbMode.LISTENING
        "thinking" -> OrbMode.THINKING
        "speaking" -> OrbMode.SPEAKING
        else -> OrbMode.IDLE
    }
    HoloCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoloHeader("Atlas", Modifier.weight(1f))
                Text(state.uppercase().ifBlank { "IDLE" }, color = Stark.muted, fontFamily = HoloMono, fontSize = 11.sp, letterSpacing = 2.sp)
            }
            val gray = Discretion.grayscale()
            AndroidView(
                factory = { c -> WaveformView(c) },
                update = { v ->
                    v.mode = mode
                    // Grayscale: desaturate the whole view without touching the overlay's drawing code.
                    if (gray) {
                        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                        v.setLayerType(View.LAYER_TYPE_HARDWARE, p)
                    } else v.setLayerType(View.LAYER_TYPE_NONE, null)
                },
                modifier = Modifier.fillMaxWidth().height(84.dp).padding(top = 8.dp)
            )
        }
    }
}

/** The bottom bar: translucent dark, a cyan hairline on top, target-lock brackets around the selected tab. */
@Composable
fun HoloNavBar(items: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>, selected: Int, onSelect: (Int) -> Unit) {
    val hair = Stark.cyanDim; val cyan = Stark.cyan
    Row(
        Modifier.fillMaxWidth().background(Stark.ground.copy(alpha = 0.94f))
            .drawBehind { drawLine(hair, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
            .navigationBarsPadding().height(56.dp)
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(40.dp).drawBehind {
                        if (on) {
                            brackets(cyan, 7.dp, 1.5.dp)
                            drawLine(cyan, Offset(size.width / 2 - 8.dp.toPx(), 0f), Offset(size.width / 2 + 8.dp.toPx(), 0f), 2.dp.toPx())
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        icon, contentDescription = label, tint = if (on) cyan else Stark.muted, modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** Power-on, once per cold start: an arc sweeps a ring while the dark scrim fades off the content. Tap skips. */
@Composable
fun BootIn(enabled: Boolean) {
    var done by remember { mutableStateOf(!enabled) }
    if (done) return
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(850, easing = LinearEasing))
        done = true
    }
    val p = t.value
    val ground = Stark.ground; val cyan = Stark.cyan; val dim = Stark.cyanDim
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { done = true } }
            .alpha((1f - ((p - 0.35f) / 0.65f)).coerceIn(0f, 1f))
    ) {
        drawRect(ground)
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.18f
        drawCircle(dim, r, c, style = Stroke(1.dp.toPx()))
        drawArc(
            cyan, -90f, 360f * (p / 0.7f).coerceAtMost(1f), false,
            Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Square)
        )
        drawCircle(cyan.copy(alpha = 0.5f), r * 0.28f * (0.6f + 0.4f * p), c, style = Stroke(2.dp.toPx()))
    }
}
