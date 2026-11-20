package com.jondude.anzu.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jondude.anzu.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * The launcher's visual core, shared by the home screen (C1) and the lock screen
 * (L3): the HUD palette, the Rajdhani family, and the ONE living-sphere
 * implementation both surfaces draw. Extracted from LauncherActivity in v1.2.19 so
 * the lock orb is the same sphere, not a copy.
 */
internal object Hud {
    val Ground = Color(0xFF000000)           // matte black (C1)
    val TitaniumDark = Color(0xFF14181D)     // brushed titanium, low end (C4)
    val TitaniumLight = Color(0xFF2B333B)    // brushed titanium, high end (C4)
    val Glass = Color(0x2EFFFFFF)            // frosted row fill (C4: white glass on titanium)
    val Pill = Color(0xFF1C1C1E)              // the Ask Atlas pill (C1)
    val Ring = Color(0xFF3FE3E0)             // the one ring: teal-cyan
    val Cyan = Color(0xFF46D8FF)
    val Blue = Color(0xFF2E7CF6)
    val Pale = Color(0xFF9ADFE8)              // quiet status lines (C1)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8A97A0)
    val Hairline = Color(0xFF2A7F8C)
}

internal val HudFont = FontFamily(
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold)
)

/**
 * The living sphere itself: [SphereMath.POINT_COUNT] fine cyan points on a sphere,
 * drifting slowly around the Y axis, depth-shaded blue→cyan with the rim burning
 * brighter (the dense L3-centre field, v1.2.19 — the old 260-point sphere read as
 * sparse). While listening the sphere swells slightly with the live mic level.
 * Frozen (rotation held at 0) when the battery rule pauses animation.
 *
 * Cost: the point set and the rotation scratch are remembered once; each frame is a
 * single O(n) rotate and two unsorted passes (back half, then front half) — no
 * allocation and no sort in the draw loop.
 */
@Composable
internal fun PointSphere(state: String, animate: Boolean, micLevel: Float, modifier: Modifier = Modifier) {
    val points = remember { SphereMath.fibonacci(SphereMath.POINT_COUNT) }
    val scratch = remember { FloatArray(SphereMath.POINT_COUNT * 3) }
    val transition = rememberInfiniteTransition(label = "sphere-drift")
    val rotLive by transition.animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(46_000, easing = LinearEasing), RepeatMode.Restart), label = "drift"
    )
    val rot = if (animate) rotLive else 0f
    val cosR = cos(rot.toDouble()).toFloat()
    val sinR = sin(rot.toDouble()).toFloat()
    val tilt = 0.42f
    val cosT = cos(tilt.toDouble()).toFloat()
    val sinT = sin(tilt.toDouble()).toFloat()
    Canvas(modifier) {
        val c = center
        val base = size.minDimension / 2 * 0.97f
        val swell = if (state == "listening") 1f + micLevel * 0.05f else 1f
        val r = base * swell
        // The soft glowing edge: a faint blue halo just inside the rim.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent, Hud.Blue.copy(alpha = 0.16f), Hud.Cyan.copy(alpha = 0.10f)),
                center = c, radius = r
            ),
            radius = r, center = c
        )
        // Rotate every point into the scratch (Y drift, then the fixed X tilt).
        val n = SphereMath.POINT_COUNT
        for (i in 0 until n) {
            SphereMath.rotateInto(
                points[i * 3], points[i * 3 + 1], points[i * 3 + 2],
                cosR, sinR, cosT, sinT, scratch, i * 3
            )
        }
        // Paint back half, then front half: correct layering without a per-frame sort.
        for (pass in 0..1) {
            for (i in 0 until n) {
                val x = scratch[i * 3]
                val y = scratch[i * 3 + 1]
                val z = scratch[i * 3 + 2]
                if ((z < 0f) != (pass == 0)) continue
                val depth = SphereMath.depth(z)
                val radial = SphereMath.radial(x, y)
                val col = lerp(Hud.Blue, Hud.Ring, 0.35f + 0.65f * depth)
                drawCircle(
                    col.copy(alpha = SphereMath.alpha(depth, radial)),
                    SphereMath.radiusDp(depth, radial).dp.toPx(),
                    Offset(c.x + x * r, c.y + y * r)
                )
            }
        }
    }
}
