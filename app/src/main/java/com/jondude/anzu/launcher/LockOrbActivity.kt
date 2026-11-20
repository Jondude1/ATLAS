package com.jondude.anzu.launcher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.jondude.anzu.Prefs
import kotlinx.coroutines.delay

/**
 * The lock-screen orb (v1.2.19, visual target L3 "Instrument Ring"): Atlas's face
 * over the system keyguard — the dense living sphere inside the segmented instrument
 * ring, slowly rotating, on full-bleed black. NOTHING else: no text, no clock, no
 * status, no personal data of any kind — the lock screen is a public surface.
 *
 * Honest platform form: Android does not let an app replace the lock screen, so this
 * is a showWhenLocked activity rendered OVER the keyguard. The system unlock still
 * gates everything behind it. It never turns the screen on, holds no wake lock, and
 * yields instantly — any tap/swipe/keypress hands off to the system unlock prompt,
 * and incoming calls/alarms (system UI) always take precedence.
 */
class LockOrbActivity : ComponentActivity() {

    private val unlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Unlocked: get out of the way — Jon lands on the launcher home beneath.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the keyguard, but deliberately NO setTurnScreenOn: the orb never wakes
        // the screen itself; it is only ever shown because the screen already came on.
        setShowWhenLocked(true)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (!LockOrb.shouldShow(Prefs.lockOrb, km?.isKeyguardLocked == true)) {
            finish()
            return
        }
        try {
            registerReceiver(unlockedReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (_: Throwable) {}
        setContent { LockOrbScreen { handOffToUnlock() } }
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockedReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // If the keyguard is gone (unlocked, or a call/alarm flow took over and the
        // device was unlocked through it), don't linger over the home screen.
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked != true) finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        handOffToUnlock()
        return true
    }

    /** Any interaction hands off to the system unlock prompt; on success we finish (USER_PRESENT also finishes). */
    private fun handOffToUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (!km.isKeyguardLocked) {
            finish()
            return
        }
        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
            })
        } catch (_: Throwable) {}
    }

    companion object {
        fun show(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, LockOrbActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (_: Throwable) {}
        }
    }
}

/** Battery percent from the sticky intent; -1 when unreadable (which animates, per the home rule). */
private fun readBatteryPct(ctx: Context): Int = try {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    if (level >= 0 && scale > 0) level * 100 / scale else -1
} catch (_: Exception) {
    -1
}

@Composable
private fun LockOrbScreen(onTouch: () -> Unit) {
    val ctx = LocalContext.current
    var batteryPct by remember { mutableIntStateOf(readBatteryPct(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            batteryPct = readBatteryPct(ctx)
        }
    }
    // The screen is on (that's why we're here); the toggle was checked at launch.
    val animate = LockOrb.shouldAnimate(enabled = true, screenOn = true, batteryPct = batteryPct)
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onPress = { onTouch(); tryAwaitRelease() }) }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { onTouch() }, onDrag = { _, _ -> }) },
        contentAlignment = Alignment.Center
    ) {
        InstrumentOrb(animate, Modifier.size(340.dp).offset(y = (-20).dp))
    }
}

/**
 * The L3 instrument ring: a dark titanium dial body with segment notches, a rotating
 * frame of 60 ticks (five lit blocks travelling with it, four long cardinal ticks),
 * hairline circles, and the shared dense living sphere at its centre. The frame makes
 * one revolution per [LockOrb.ROTATION_PERIOD_MS]; under the battery rule everything
 * holds a static frame.
 */
@Composable
private fun InstrumentOrb(animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lock-ring")
    val rotLive by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(LockOrb.ROTATION_PERIOD_MS.toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "ring-rotation"
    )
    val rot = if (animate) rotLive else 0f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2
            // Titanium dial body: a thick dark ring with lighter edges and 8 notches.
            val bodyR = r - 13.dp.toPx()
            val bodySq = Size(bodyR * 2, bodyR * 2)
            val bodyTl = Offset(c.x - bodyR, c.y - bodyR)
            drawCircle(Hud.TitaniumDark, bodyR, c, style = Stroke(26.dp.toPx()))
            drawCircle(Hud.TitaniumLight.copy(alpha = 0.55f), r - 1.5.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            drawCircle(Hud.TitaniumLight.copy(alpha = 0.35f), r - 25.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            for (k in 0 until 8) {
                rotate(k * 45f + 22.5f, c) {
                    drawArc(Hud.Ground, -7f, 14f, false, bodyTl, bodySq, style = Stroke(26.dp.toPx()))
                }
            }
            // Hairline just outside the sphere.
            drawCircle(Hud.Hairline.copy(alpha = 0.5f), r * 0.66f, c, style = Stroke(1.dp.toPx()))
            // The rotating frame: lit segment arcs + the 60 ticks.
            val tickR = r * 0.80f
            val tickSq = Size(tickR * 2, tickR * 2)
            val tickTl = Offset(c.x - tickR, c.y - tickR)
            rotate(rot, c) {
                for (b in 0 until LockOrb.TICK_COUNT / LockOrb.BLOCK) {
                    drawArc(
                        Hud.Cyan.copy(alpha = 0.70f),
                        -90f + b * LockOrb.BLOCK * 6f, LockOrb.LIT_RUN * 6f - 3f,
                        false, tickTl, tickSq, style = Stroke(4.dp.toPx())
                    )
                }
                for (i in 0 until LockOrb.TICK_COUNT) {
                    val a = Math.toRadians((i * 6).toDouble())
                    val len = r * LockOrb.tickLengthFrac(i)
                    val lit = LockOrb.tickLit(i)
                    val col = when {
                        LockOrb.isMajorTick(i) -> Color(0xFFD9FFFF)
                        lit -> Hud.Ring.copy(alpha = 0.95f)
                        else -> Hud.Ring.copy(alpha = 0.16f)
                    }
                    val w = when {
                        LockOrb.isMajorTick(i) -> 2.5.dp.toPx()
                        LockOrb.isMediumTick(i) -> 1.8.dp.toPx()
                        else -> 1.2.dp.toPx()
                    }
                    drawLine(
                        col,
                        Offset(c.x + (tickR - len) * kotlin.math.sin(a).toFloat(), c.y - (tickR - len) * kotlin.math.cos(a).toFloat()),
                        Offset(c.x + tickR * kotlin.math.sin(a).toFloat(), c.y - tickR * kotlin.math.cos(a).toFloat()),
                        strokeWidth = w
                    )
                }
            }
        }
        // The same dense living sphere the home screen draws — one implementation.
        PointSphere("idle", animate, 0f, Modifier.fillMaxSize(0.62f))
    }
}
