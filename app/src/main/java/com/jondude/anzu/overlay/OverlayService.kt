package com.jondude.anzu.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jondude.anzu.Earcons
import com.jondude.anzu.Glasses
import com.jondude.anzu.GlassesService
import com.jondude.anzu.Prefs
import com.jondude.anzu.Speaker
import com.jondude.anzu.WakeListener
import com.jondude.anzu.assist.AssistActivity
import com.jondude.anzu.assist.Atlas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/** Floating Atlas companion: an orb in a TYPE_APPLICATION_OVERLAY window, with a long-press HUD panel. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var orb: WaveformView? = null
    private var orbLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var catcher: View? = null
    private var hudOrb: WaveformView? = null
    private var hudCol: View? = null
    private var globe: WireGlobeView? = null
    private var jobsLine: TextView? = null
    private val jobTick = object : Runnable {
        override fun run() {
            if (!expanded) return
            jobsLine?.text = com.jondude.anzu.assist.Jobs.strip(com.jondude.anzu.assist.Jobs.running.value)
            main.postDelayed(this, 1_000)
        }
    }
    private var edge: EdgeGlowView? = null
    private var alertLevel = AlertLevel.NORMAL
    private var moodKind = com.jondude.anzu.assist.Mood.Kind.NONE
    private var talkSince = 0L
    private var lastLoud = 0L
    private var lastBackchannel = 0L
    private val alertReset = Runnable { applyAlert(AlertLevel.NORMAL) }
    private var convo = false
    private var stateLine: TextView? = null
    private var saidLine: TextView? = null
    private var heardLine: TextView? = null
    private var musicChip: TextView? = null
    private var glassesChip: TextView? = null
    private var sizeChip: TextView? = null
    private var bubble: View? = null
    private var clockLine: TextView? = null
    private var powerBar: SegmentBar? = null
    private var powerText: TextView? = null
    private var quietChip: TextView? = null
    private var card: View? = null
    private var ambient = false
    private var sensors: android.hardware.SensorManager? = null
    private var idLine: TextView? = null
    private var input: android.widget.EditText? = null
    private var typing = false

    private var edgeRight = true
    private var yFrac = 0.4f
    private var lastTouchAt = 0L
    private var dragging = false
    private var expanded = false
    private var glide: ValueAnimator? = null
    private var lastSaid = ""
    private var state = "idle"
    private var highlight: View? = null
    private var pointing = false

    private var sizeKey = SIZE_DEFAULT
    private val orbPx get() = dp(OrbSize.dp(sizeKey))
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun navBarH(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(48)
    }

    private fun minY() = statusBarH()
    private fun maxY() = (screenH - navBarH() - orbPx).coerceAtLeast(minY())
    private fun yFor(f: Float) = (minY() + f * (maxY() - minY())).toInt()
    private fun xFor(right: Boolean) = if (right) screenW - orbPx else 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crumb(this, "onCreate entered")
        if (!Settings.canDrawOverlays(this)) {
            crumb(this, "no overlay permission")
            stopSelf()
            return
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atlas companion", NotificationManager.IMPORTANCE_LOW))
            nm.notify(NOTIFICATION_ID, notification())
            crumb(this, "notify ok")
        } catch (t: Throwable) {
            recordError(this, "notify", t)
        }
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            edgeRight = sp.getBoolean(KEY_RIGHT, true)
            yFrac = sp.getFloat(KEY_Y, 0.4f).coerceIn(0f, 1f)
            sizeKey = OrbSize.normalize(sp.getString(KEY_SIZE, SIZE_DEFAULT))
            addOrb()
            crumb(this, "addOrb ok")
            armMic("overlay start")
            WakeListener.start(this)
            announceOnline(sp)
            armAmbient()
            try { registerReceiver(unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT)) } catch (_: Throwable) {}
        observe()
            crumb(this, "observe ok")
            scheduleRoam()
        } catch (t: Throwable) {
            isRunning = false
            recordError(this, "setup", t)
            crumb(this, "setup failed")
            setEnabled(this, false)
            orb?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
            orb = null
            stopSelf()
            return
        }
        isRunning = true
        instance = this
        clearError(this)
        crumb(this, "running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ATTENTION -> orb?.playAttention()
            ACTION_HIDE -> {
                setEnabled(this, false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Throwable) {}
        WakeListener.stop()
        try { edge?.let { wm.removeView(it) } } catch (_: Throwable) {}
        edge = null
        isRunning = false
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        glide?.cancel()
        removeHighlight()
        removeBubble()
        removeCard()
        collapse(save = false)
        orb?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        orb = null
        scope.cancel()
        try { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (_: Throwable) {}
        crumb(this, "onDestroy")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val hide = PendingIntent.getService(
            this, 11, Intent(this, OverlayService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Atlas companion is floating")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Hide", hide)
            .build()
    }

    // ---------------------------------------------------------------- orb window

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addOrb() {
        val v = WaveformView(this)
        val lp = overlayParams(orbPx, orbPx).apply {
            x = xFor(edgeRight)
            y = yFor(yFrac)
        }
        v.setOnTouchListener(touch)
        orbLp = lp
        orb = v
        wm.addView(v, lp)
        // Power on: the orb spools up from a small bright point.
        v.alpha = 0f; v.scaleX = 0.2f; v.scaleY = 0.2f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun updateOrb() {
        val v = orb ?: return
        val lp = orbLp ?: return
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private val touch = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var moved = false
        private var longFired = false
        // Lazy: this object is created during the service constructor, before Android attaches
        // the base context — reading resources (dp) there kills instantiation before onCreate.
        private val slop: Int by lazy { dp(10) }
        private val longPress = Runnable {
            if (!moved && !dragging) {
                longFired = true
                peek()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = orbLp ?: return false
            lastTouchAt = System.currentTimeMillis()
            wake()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    downAt = e.eventTime
                    moved = false; longFired = false
                    glide?.cancel()
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved && !longFired) {
                        lp.x = (startX + dx).toInt().coerceIn(0, screenW - orbPx)
                        lp.y = (startY + dy).toInt().coerceIn(minY(), maxY())
                        updateOrb()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (longFired) endPeek()
                    val wasDrag = moved
                    dragging = false
                    if (wasDrag) snapToEdge()
                    else if (!longFired && e.eventTime - downAt < TAP_MS) openConvo()
                    scheduleRoam()
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (longFired) endPeek()
                    dragging = false
                    if (moved) snapToEdge()
                }
            }
            return true
        }
    }

    /**
     * Talk to Atlas without leaving whatever you're doing. When the service's microphone is armed
     * (it has been opened once since boot) the conversation runs right here; otherwise it falls back to
     * the small sheet, which is the one path that always has mic access.
     */
    /**
     * Ask the glasses service to attach its microphone while this overlay window is visible, so the
     * mic works after a reboot without opening the app. Android may refuse; the service then stays
     * un-armed and Talk falls back to the sheet. Never disarms a mic that is already armed.
     */
    private fun armMic(why: String) {
        if (Glasses.micArmed.value) return
        try {
            GlassesService.start(this, true)
            crumb(this, "armMic requested ($why)")
        } catch (t: Throwable) {
            crumb(this, "armMic failed: ${t.javaClass.simpleName}")
        }
    }

    /** Touching the waveform (or saying "Atlas") opens the full overlay; touch also starts listening. */
    private fun openConvo() {
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        convo = true
        expand()
        talk()
    }

    private fun wakeOpen() {
        try { Earcons.play("ack") } catch (_: Throwable) {}
        convo = true
        if (!expanded) expand()
    }

    /** Speaking over ATLAS: silence him at once, flash the waveform, and go straight to listening. */
    private fun interrupt() {
        try { Speaker.stop() } catch (_: Throwable) {}
        orb?.playCut(); hudOrb?.playCut()
        haptic(HapticFeedbackConstants.REJECT)
    }

    private fun talk() {
        if (state == "speaking") interrupt()
        if (!Glasses.micArmed.value) {
            armMic("talk")
            // The service attaches the mic type asynchronously; give it a moment before choosing a path.
            scope.launch { delay(600); talkNow() }
        } else talkNow()
    }

    private fun talkNow() {
        if (Glasses.micArmed.value) {
            crumb(this, "talk: hands-free")
            scope.launch {
                try {
                    if (Atlas.busy) Speaker.stop()
                    Atlas.converse(applicationContext, null, true, 8)
                } catch (t: Throwable) {
                    recordError(this@OverlayService, "talk", t)
                }
            }
        } else {
            crumb(this, "talk: mic not armed, sheet fallback")
            tap()
        }
    }

    private fun tap() {
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun snapToEdge() {
        val lp = orbLp ?: return
        edgeRight = lp.x + orbPx / 2 > screenW / 2
        yFrac = ((lp.y - minY()).toFloat() / (maxY() - minY()).coerceAtLeast(1)).coerceIn(0f, 1f)
        persist()
        glideTo(xFor(edgeRight), lp.y, 350L, OvershootInterpolator(1.1f))
    }

    private fun persist() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RIGHT, edgeRight).putFloat(KEY_Y, yFrac).apply()
    }

    private fun glideTo(tx: Int, ty: Int, ms: Long, interp: android.view.animation.Interpolator) {
        val lp = orbLp ?: return
        glide?.cancel()
        val sx = lp.x
        val sy = lp.y
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.x = (sx + (tx - sx) * f).toInt()
                lp.y = (sy + (ty - sy) * f).toInt()
                updateOrb()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- roaming

    private val roam = Runnable { roamStep() }

    private fun scheduleRoam() {
        main.removeCallbacks(roam)
        main.postDelayed(roam, Random.nextLong(20_000, 45_000))
    }

    private fun roamStep() {
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val quiet = System.currentTimeMillis() - lastTouchAt < 30_000
        if (!screenOn || quiet || dragging || expanded || pointing || state != "idle") {
            scheduleRoam()
            return
        }
        yFrac = Random.nextFloat()
        persist()
        glideTo(xFor(edgeRight), yFor(yFrac), Random.nextLong(2_000, 4_000), android.view.animation.AccelerateDecelerateInterpolator())
        scheduleRoam()
    }

    // ---------------------------------------------------------------- speech bubble

    /** Parallax: the HUD orb shifts a few dp with phone tilt, the title line the other way, so the panel reads as layered depth. */
    private val tilt = object : android.hardware.SensorEventListener {
        private var tx = 0f
        private var ty = 0f
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            tx += (-(e.values[0] / 9.8f).coerceIn(-1f, 1f) - tx) * 0.12f
            ty += ((e.values[2] / 9.8f).coerceIn(-1f, 1f) - ty) * 0.12f
            // Real parallax: the whole projection leans with the phone; the waveform and title shift on top.
            hudCol?.rotationY = tx * 9f; hudCol?.rotationX = -ty * 7f
            hudOrb?.translationX = tx * dp(5); stateLine?.translationX = -tx * dp(2)
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    // ---------------------------------------------------------------- ambient mode

    private val goAmbient = Runnable {
        if (!expanded && !dragging && !pointing && state == "idle" && bubble == null && card == null) {
            ambient = true
            orb?.animate()?.alpha(0.35f)?.scaleX(0.72f)?.scaleY(0.72f)?.setDuration(700)?.start()
        } else armAmbient()
    }

    private fun armAmbient() {
        main.removeCallbacks(goAmbient)
        main.postDelayed(goAmbient, 45_000)
    }

    /** Anything that matters brings the orb back to full presence. */
    private fun wake() {
        armAmbient()
        if (!ambient) return
        ambient = false
        orb?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(250)?.start()
    }

    // ---------------------------------------------------------------- floating info cards

    private val hideCard = Runnable { fadeCard() }

    /** Types text in character by character with a faint flicker, like a projected readout. Measured size is already fixed, so layout doesn't jump. */
    private fun typeIn(tv: TextView, full: String, totalMs: Long = 420L) {
        tv.text = ""
        if (full.isEmpty()) return
        val n = full.length
        ValueAnimator.ofInt(0, n).apply {
            duration = totalMs.coerceIn(160L, 900L)
            addUpdateListener {
                val k = it.animatedValue as Int
                tv.text = full.substring(0, k)
                tv.alpha = if (k < n && (k % 3 == 0)) 0.72f else 1f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { tv.alpha = 1f; tv.text = full }
                override fun onAnimationCancel(a: android.animation.Animator) { tv.alpha = 1f; tv.text = full }
            })
            start()
        }
    }

    private var cardPinned = false

    /** After a drag the card glides on with a little momentum, then settles; it bounces softly off the screen edges. */
    private fun settleCard(v: View, lp: WindowManager.LayoutParams, vx: Float, vy: Float) {
        val maxX = (screenW - v.width).coerceAtLeast(0)
        val maxY = (screenH - navBarH() - v.height).coerceAtLeast(minY())
        var x = lp.x.toFloat(); var y = lp.y.toFloat()
        var ax = vx * 0.28f; var ay = vy * 0.28f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                x += ax * 0.016f; y += ay * 0.016f
                ax *= 0.93f; ay *= 0.93f
                if (x < 0f) { x = 0f; ax = -ax * 0.5f } else if (x > maxX) { x = maxX.toFloat(); ax = -ax * 0.5f }
                if (y < minY()) { y = minY().toFloat(); ay = -ay * 0.5f } else if (y > maxY) { y = maxY.toFloat(); ay = -ay * 0.5f }
                lp.x = x.toInt(); lp.y = y.toInt()
                try { if (v.parent != null) wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
            }
            start()
        }
    }

    /** Drag a card anywhere, flick it away, or tap to pin it so it stays until tapped again. */
    private fun makeCardInteractive(panel: View, lp: WindowManager.LayoutParams, title: TextView, label: String) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var downAt = 0L; var moved = false
        var vel: android.view.VelocityTracker? = null
        panel.setOnTouchListener { v, e ->
            if (vel == null) vel = android.view.VelocityTracker.obtain()
            vel?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; downAt = System.currentTimeMillis(); moved = false
                    main.removeCallbacks(hideCard)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val quick = System.currentTimeMillis() - downAt < 260
                    if (moved && quick && Math.abs(dx) > dp(70)) {
                        haptic(HapticFeedbackConstants.CLOCK_TICK)
                        try { Earcons.play("dismiss") } catch (_: Throwable) {}
                        if (card === v) card = null
                        cardPinned = false
                        v.animate().translationX(if (dx > 0) screenW.toFloat() else -screenW.toFloat()).alpha(0f).setDuration(200).withEndAction {
                            try { wm.removeView(v) } catch (_: Throwable) {}
                        }.start()
                    } else if (!moved) {
                        cardPinned = !cardPinned
                        title.text = "◢ " + Holo.tag(label) + if (cardPinned) "  ◆ PINNED" else ""
                        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                        if (!cardPinned) main.postDelayed(hideCard, 4_000)
                    } else {
                        vel?.computeCurrentVelocity(1000)
                        settleCard(v, lp, (vel?.xVelocity ?: 0f), (vel?.yVelocity ?: 0f))
                        if (!cardPinned) main.postDelayed(hideCard, 6_000)
                    }
                    vel?.recycle(); vel = null
                }
            }
            true
        }
    }


    private val backCards = ArrayList<View>()

    private fun removeCard() {
        cardPinned = false
        main.removeCallbacks(hideCard)
        card?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        card = null
        backCards.forEach { try { wm.removeView(it) } catch (_: Throwable) {} }
        backCards.clear()
    }

    /** A new card arrives: the one in front sinks back, dimmer and smaller, instead of vanishing. Two layers at most. */
    private fun pushCardBack() {
        val v = card ?: return
        card = null
        cardPinned = false
        main.removeCallbacks(hideCard)
        v.setOnTouchListener(null)
        try {
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(v, lp)
        } catch (_: Throwable) {}
        backCards.add(0, v)
        while (backCards.size > 2) {
            val old = backCards.removeAt(backCards.size - 1)
            old.animate().alpha(0f).setDuration(180).withEndAction { try { wm.removeView(old) } catch (_: Throwable) {} }.start()
        }
        backCards.forEachIndexed { i, b ->
            val depth = i + 1
            b.animate().scaleX(1f - 0.07f * depth).scaleY(1f - 0.07f * depth).alpha(0.62f - 0.22f * i).translationY(-dp(12).toFloat() * depth)
                .setDuration(260).start()
        }
        main.postDelayed({
            backCards.filter { it === v }.forEach { b ->
                b.animate().alpha(0f).setDuration(240).withEndAction { try { wm.removeView(b) } catch (_: Throwable) {} ; backCards.remove(b) }.start()
            }
        }, 6_000)
    }

    private fun fadeCard() {
        if (cardPinned) return
        val v = card ?: return
        card = null
        v.animate().alpha(0f).translationYBy(-dp(6).toFloat()).setDuration(260).withEndAction {
            try { wm.removeView(v) } catch (_: Throwable) {}
        }.start()
    }

    /** A translucent readout that hovers above the orb: weather, agenda, timers, systems. Touch-transparent. */
    private fun showCard(title: String, lines: List<String>) {
        if (orb == null || lines.isEmpty()) return
        try {
            pushCardBack()
            wake()
            try { Earcons.play("data") } catch (_: Throwable) {}
            val ctx = this
            val panel = HoloPanel(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }
            val titleView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(Color.parseColor(Holo.CYAN))
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.15f
                text = "◢ " + Holo.tag(title)
            }
            panel.addView(titleView)
            val lineViews = ArrayList<Pair<TextView, String>>()
            lines.forEachIndexed { i, l ->
                panel.addView(TextView(ctx).apply {
                    lineViews.add(this to l)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#E6FBFF"))
                    setTypeface(Typeface.MONOSPACE)
                    setPadding(0, dp(if (i == 0) 6 else 2), 0, 0)
                    text = l
                }, LinearLayout.LayoutParams(-2, -2))
            }
            val maxW = minOf((screenW * 0.8f).toInt(), dp(260))
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val w = panel.measuredWidth
            val h = panel.measuredHeight
            val ol = orbLp ?: return
            val bubbleTop = (bubble?.layoutParams as? WindowManager.LayoutParams)?.y ?: ol.y
            val lp = WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val pl = this@OverlayService.panel?.layoutParams as? WindowManager.LayoutParams
                if (expanded && pl != null) {
                    val pw = this@OverlayService.panel?.width ?: w
                    x = (if (edgeRight) pl.x + pw - w else pl.x).coerceIn(0, (screenW - w).coerceAtLeast(0))
                    val below = pl.y + (this@OverlayService.panel?.height ?: 0) + dp(8)
                    y = if (below + h <= screenH - navBarH()) below else (pl.y - h - dp(8)).coerceAtLeast(minY())
                } else {
                    x = if (edgeRight) (screenW - w - dp(8)).coerceAtLeast(0) else dp(8)
                    val above = bubbleTop - h - dp(10)
                    y = if (above >= minY()) above else (ol.y + orbPx + dp(10)).coerceAtMost((screenH - navBarH() - h).coerceAtLeast(minY()))
                }
            }
            wm.addView(panel, lp)
            card = panel
            makeCardInteractive(panel, lp, titleView, title)
            lineViews.forEachIndexed { i, (tv, full) -> tv.postDelayed({ if (tv.parent != null) typeIn(tv, full, 260L + full.length * 8L) }, 120L + i * 140L) }
            panel.cameraDistance = dp(1200).toFloat()
            panel.rotationY = if (edgeRight) -8f else 8f
            panel.alpha = 0f
            panel.scaleY = 0.6f
            panel.animate().alpha(1f).scaleY(1f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            // Gentle float, like it's hanging in light.
            android.animation.ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, 0f, -dp(3).toFloat()).apply {
                duration = 2_400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                startDelay = 400; start()
                panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { cancel() }
                })
            }
            main.postDelayed(hideCard, 9_000)
        } catch (t: Throwable) {
            crumb(this, "card failed: ${t.javaClass.simpleName}")
            removeCard()
        }
    }

    // ---------------------------------------------------------------- screen scan sweep

    private class ScanView(ctx: Context) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF"); strokeWidth = 3f * ctx.resources.displayMetrics.density
        }
        private val trail = Paint()
        var f = 0f

        override fun onDraw(c: Canvas) {
            val y = f * height
            val tail = height * 0.18f
            trail.shader = LinearGradient(0f, y - tail, 0f, y, Color.TRANSPARENT, Color.parseColor("#3300E5FF"), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - tail, width.toFloat(), y, trail)
            c.drawLine(0f, y, width.toFloat(), y, line)
        }
    }

    /** A cyan scan line sweeps the whole screen top to bottom, with the rising scan chirp. */
    private fun scanSweep() {
        try {
            com.jondude.anzu.Ears.earcon("scan")
            val v = ScanView(this)
            wm.addView(
                v,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
            )
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                addUpdateListener { v.f = it.animatedValue as Float; v.invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        try { wm.removeView(v) } catch (_: Throwable) {}
                    }
                })
                start()
            }
        } catch (t: Throwable) {
            crumb(this, "scan failed: ${t.javaClass.simpleName}")
        }
    }

    private val hideBubble = Runnable { fadeBubble() }

    /** Timer path: fade out, then remove. Everything else removes immediately via [removeBubble]. */
    private fun fadeBubble() {
        val v = bubble ?: return
        bubble = null
        v.animate().alpha(0f).translationYBy(-dp(4).toFloat()).setDuration(220).withEndAction {
            try { wm.removeView(v) } catch (_: Throwable) {}
        }.start()
    }

    private fun haptic(kind: Int) {
        try { (orb ?: panel)?.performHapticFeedback(kind) } catch (_: Throwable) {}
    }

    private fun removeBubble() {
        main.removeCallbacks(hideBubble)
        bubble?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        bubble = null
    }

    /** What Atlas just said, in a holographic bubble beside the orb. Touch-transparent; fades after a reading-time hold. */
    private fun showBubble(text: String) {
        if (expanded || dragging || orb == null) return
        try {
            removeBubble()
            val ctx = this
            val shown = HoloBubble.shorten(text)
            if (shown.isBlank()) return
            val panel = HoloPanel(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val bubbleText = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#E6FBFF"))
                setTypeface(Typeface.MONOSPACE)
                letterSpacing = 0.04f
                maxLines = 7
                ellipsize = TextUtils.TruncateAt.END
                this.text = "ATLAS ▸ $shown"
            }
            panel.addView(bubbleText)
            val maxW = minOf((screenW * 0.7f).toInt(), dp(280))
            panel.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(maxW, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val w = panel.measuredWidth
            val h = panel.measuredHeight
            val ol = orbLp ?: return
            val lp = WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (edgeRight) (ol.x - w - dp(6)).coerceAtLeast(dp(4)) else (ol.x + orbPx + dp(6)).coerceAtMost((screenW - w - dp(4)).coerceAtLeast(0))
                y = (ol.y + orbPx / 2 - h / 2).coerceIn(minY(), (screenH - navBarH() - h).coerceAtLeast(minY()))
            }
            wm.addView(panel, lp)
            bubble = panel
            typeIn(bubbleText, "ATLAS ▸ $shown", 200L + shown.length * 14L)
            panel.alpha = 0f
            panel.cameraDistance = dp(1200).toFloat()
            panel.rotationY = if (edgeRight) -8f else 8f
            panel.translationY = dp(4).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            main.postDelayed(hideBubble, HoloBubble.holdMs(shown))
        } catch (t: Throwable) {
            crumb(this, "bubble failed: ${t.javaClass.simpleName}")
            removeBubble()
        }
    }

    // ---------------------------------------------------------------- show & point

    private val finishPoint = Runnable { endPoint() }

    private fun startPoint(rect: Rect) {
        try {
            if (orb == null || rect.isEmpty) return
            endPoint()
            pointing = true
            wake()
            com.jondude.anzu.Ears.earcon("lock")
            val lp = orbLp
            if (lp != null && !expanded) {
                val gap = dp(8)
                val tx = (rect.centerX() - orbPx / 2).coerceIn(0, (screenW - orbPx).coerceAtLeast(0))
                var ty = rect.top - orbPx - gap
                if (ty < minY()) ty = rect.bottom + gap
                glideTo(tx, ty.coerceIn(minY(), maxY()), 600L, DecelerateInterpolator())
            }
            val v = HighlightView(this, rect, dp(RING_PAD_DP).toFloat(), dp(RING_STROKE_DP).toFloat()) {
                orbLp?.let { Pair(it.x + orbPx / 2f, it.y + orbPx / 2f) }
            }
            val lpH = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lpH)
            highlight = v
            main.removeCallbacks(finishPoint)
            main.postDelayed(finishPoint, POINT_MS)
        } catch (t: Throwable) {
            crumb(this, "pointAt failed: ${t.javaClass.simpleName}")
            endPoint()
        }
    }

    private fun removeHighlight() {
        (highlight as? HighlightView)?.stop()
        highlight?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        highlight = null
    }

    private fun endPoint() {
        main.removeCallbacks(finishPoint)
        removeHighlight()
        if (!pointing) return
        pointing = false
        try {
            if (!dragging && !expanded) glideTo(xFor(edgeRight), yFor(yFrac), 500L, OvershootInterpolator(1.1f))
        } catch (_: Throwable) {}
    }

    /**
     * Holographic target lock around [target]: cyan corner brackets that breathe, a faint tinted fill,
     * and a dashed leader line running from the orb to the nearest edge of the target. Touch-transparent.
     */
    private class HighlightView(
        ctx: Context, private val target: Rect, private val pad: Float, private val stroke: Float,
        private val orbCenter: () -> Pair<Float, Float>?
    ) : View(ctx) {
        private val cyan = Color.parseColor("#00E5FF")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.SQUARE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#1A00E5FF") }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke * 0.6f; color = cyan }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cyan }
        private val box = RectF()
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; typeface = Typeface.MONOSPACE; textSize = pad * 2f; letterSpacing = 0.15f; isFakeBoldText = true
        }
        private val scan = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke * 0.5f; color = cyan }
        private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
        }

        init { pulse.start() }

        fun stop() { pulse.cancel() }

        override fun onDraw(c: Canvas) {
            val f = pulse.animatedValue as Float
            val grow = pad + f * pad * 0.5f
            box.set(target.left - grow, target.top - grow, target.right + grow, target.bottom + grow)
            c.drawRect(box, fill)
            // corner brackets
            val len = (minOf(box.width(), box.height()) * 0.3f).coerceIn(pad * 2f, pad * 6f)
            paint.color = cyan
            paint.alpha = (255 * (1f - 0.35f * f)).toInt()
            val l = box.left; val t = box.top; val r = box.right; val b = box.bottom
            c.drawLine(l, t, l + len, t, paint); c.drawLine(l, t, l, t + len, paint)
            c.drawLine(r, t, r - len, t, paint); c.drawLine(r, t, r, t + len, paint)
            c.drawLine(l, b, l + len, b, paint); c.drawLine(l, b, l, b - len, paint)
            c.drawLine(r, b, r - len, b, paint); c.drawLine(r, b, r, b - len, paint)
            // scan sweep inside the box, and the lock label
            scan.alpha = 110
            val sy = box.top + f * box.height()
            c.drawLine(box.left + len * 0.3f, sy, box.right - len * 0.3f, sy, scan)
            label.alpha = (255 * (1f - 0.3f * f)).toInt()
            val ly = if (box.top > label.textSize * 1.6f) box.top - pad * 0.8f else box.bottom + label.textSize + pad * 0.8f
            c.drawText("TARGET LOCK", box.left, ly, label)
            // leader line from the orb to the nearest point on the box
            val o = orbCenter() ?: return
            val ex = o.first.coerceIn(box.left, box.right)
            val ey = o.second.coerceIn(box.top, box.bottom)
            if (Math.hypot((o.first - ex).toDouble(), (o.second - ey).toDouble()) > pad) {
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(stroke * 3f, stroke * 2f), f * stroke * 5f)
                line.alpha = 200
                c.drawLine(o.first, o.second, ex, ey, line)
            }
            c.drawCircle(ex, ey, stroke * 1.6f, dot)
        }
    }

    // ---------------------------------------------------------------- state

    private fun observe() {
        scope.launch {
            Glasses.atlasState.collect { s ->
                state = s
                if (s != "idle" && !dragging) {
                    glide?.cancel()
                    snapIfOff()
                }
                val m = when (s) {
                    "listening" -> OrbMode.LISTENING
                    "thinking" -> OrbMode.THINKING
                    "speaking" -> OrbMode.SPEAKING
                    else -> OrbMode.IDLE
                }
                orb?.mode = m
                hudOrb?.mode = m
                globe?.mode = m
                Speaker.pan = if (edgeRight) 0.55f else -0.55f
                if (s == "idle") hideEdge() else { showEdge(); tintEdge() }
                if (s != "idle") wake()
                if (expanded && convo) { if (s == "idle") armAutoCollapse(10_000) else main.removeCallbacks(autoCollapse) }
                refreshHud()
            }
        }
        scope.launch {
            Glasses.lastSaid.collect { said ->
                if (said.isNotBlank() && said != lastSaid && state == "idle") {
                    orb?.playAttention()
                    hudOrb?.playAttention()
                }
                if (said.isNotBlank() && said != lastSaid) { wake(); showBubble(said) }
                lastSaid = said
                refreshHud()
            }
        }
        scope.launch {
            combine(Glasses.lastHeard, Glasses.connected) { a, b -> a to b }.collect { (heard, _) ->
                moodKind = com.jondude.anzu.assist.Mood.detect(heard)
                tintEdge()
                refreshHud()
            }
        }
        scope.launch {
            Glasses.micLevel.collect { lv ->
                orb?.level = lv; hudOrb?.level = lv; globe?.level = lv; edge?.drive = lv
                // Backchannel: while Jon talks at length, it nods now and then, with a flash and a tick, never a sound.
                if (state == "listening") {
                    val now = System.currentTimeMillis()
                    if (lv > 0.25f) {
                        if (now - lastLoud > 700) talkSince = now
                        lastLoud = now
                        if (now - talkSince > 6_000 && now - lastBackchannel > 4_500) {
                            lastBackchannel = now
                            orb?.playAttention(); hudOrb?.playAttention(); globe?.playAttention()
                            haptic(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
            }
        }
    }

    /** If a glide was cancelled mid-way by a state change, settle on the edge so the orb never floats mid-screen. */
    private fun snapIfOff() {
        val lp = orbLp ?: return
        if (lp.x != xFor(edgeRight)) lp.x = xFor(edgeRight)
        updateOrb()
    }

    // ---------------------------------------------------------------- HUD panel

    private fun expand() {
        if (expanded) return
        expanded = true
        try { Earcons.play("open") } catch (_: Throwable) {}
        removeBubble()
        glide?.cancel()
        orb?.visibility = View.INVISIBLE
        val ctx = this

        val catcherView = View(ctx).apply { setOnClickListener { collapse() } }
        wm.addView(
            catcherView,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        )
        catcher = catcherView

        val col = HoloPanel(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        // Telemetry header: identity on the left, clock on the right.
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun mono(sizeSp: Float, color: String) = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.parseColor(color))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val idText = "A.T.L.A.S // ONLINE"
        idLine = mono(9f, Holo.CYAN_DIM).also { head.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        ValueAnimator.ofInt(0, idText.length).apply {
            duration = 550L
            addUpdateListener { idLine?.text = idText.substring(0, it.animatedValue as Int) }
            start()
        }
        clockLine = mono(9f, Holo.CYAN_DIM).also { head.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        val big = WaveformView(ctx, 13)
        hudOrb = big
        big.mode = orb?.mode ?: OrbMode.IDLE
        col.addView(big, LinearLayout.LayoutParams(-1, dp(120)))
        val gl = WireGlobeView(ctx).also { it.mode = big.mode; it.setAlert(alertLevel) }
        globe = gl
        col.addView(gl, LinearLayout.LayoutParams(-1, dp(84)))
        // Live diagnostics: four small readouts, filled in off the main thread.
        val tileRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tiles = listOf("OBELISK", "HOME", "WEATHER", "NEXT").map { name ->
            val t = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                setTextColor(Color.parseColor(Holo.CYAN))
                setTypeface(Typeface.MONOSPACE)
                maxLines = 2
                gravity = Gravity.CENTER
                text = "$name\n…"
                setPadding(dp(2), dp(4), dp(2), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0E00E5FF"))
                    setStroke(dp(1), Color.parseColor(Holo.CYAN_FAINT))
                }
            }
            tileRow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) })
            name to t
        }.toMap()
        col.addView(tileRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        // Live status of anything handed to Hermes, Helios or research; empty when nothing is running.
        val jl = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setTextColor(Color.parseColor(Holo.ACCENT))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
            maxLines = 2
        }
        jobsLine = jl
        col.addView(jl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        main.post(jobTick)
        loadTiles(ctx, tiles)

        fun text(sizeSp: Float, color: String, bold: Boolean = false) = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.parseColor(color))
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = 0.06f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        stateLine = text(15f, Holo.CYAN, true).also { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        saidLine = text(11f, "#CCE6FBFF").also { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        heardLine = text(11f, Holo.CYAN_DIM).also { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }) }

        fun chip(label: String? = null, onClick: (() -> Unit)? = null) = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor(Holo.CYAN))
            setTypeface(Typeface.MONOSPACE)
            letterSpacing = 0.08f
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1400E5FF"))
                cornerRadius = dp(2).toFloat()
                setStroke(dp(1), Color.parseColor(Holo.CYAN_DIM))
            }
            if (label != null) text = label
            if (onClick != null) setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.55f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        musicChip = chip().also { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        glassesChip = chip().also { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        val hide = chip("HIDE") {
            setEnabled(ctx, false)
            stopSelf()
        }.apply {
            setTextColor(Color.parseColor(Holo.ACCENT))
            (background as GradientDrawable).setStroke(dp(1), Color.parseColor(Holo.ACCENT))
        }
        col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        // Second row: size and hide, so four chips never overflow the 240dp panel.
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        sizeChip = chip().also { row2.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        sizeChip?.setOnClickListener { cycleSize() }
        row2.addView(chip("SCAN") {
            try {
                startActivity(Intent(ctx, ArActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                collapse()
            } catch (_: Throwable) {}
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        row2.addView(chip("HOLO BG") {
            try {
                startActivity(Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName(ctx, HoloWallpaperService::class.java))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                collapse()
            } catch (_: Throwable) {}
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        row2.addView(hide)
        col.addView(row2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) armAutoCollapse(); false }

        // Controls, so the app never has to be opened: talk, speak up, quiet, and a typed message line.
        val act = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        act.addView(chip("TALK") { convo = true; talk() }.apply {
            setTextColor(Color.parseColor("#031A24"))
            (background as GradientDrawable).setColor(Color.parseColor(Holo.CYAN))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        act.addView(chip("SPEAK UP") {
            GlassesService.start(ctx, true, GlassesService.ACTION_SPEAK_NOW)
            collapse()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        quietChip = chip().also {
            it.setOnClickListener { toggleQuiet() }
            act.addView(it, LinearLayout.LayoutParams(-2, -2))
        }
        col.addView(act, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val box = android.widget.EditText(ctx).apply {
            hint = "MESSAGE ATLAS ▸"
            setHintTextColor(Color.parseColor(Holo.CYAN_DIM))
            setTextColor(Color.parseColor("#E6FBFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1400E5FF"))
                cornerRadius = dp(2).toFloat()
                setStroke(dp(1), Color.parseColor(Holo.CYAN_DIM))
            }
            // A not-focusable overlay can't take a keyboard, so it becomes focusable only while typing.
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) beginTyping(); false }
            setOnEditorActionListener { v, _, _ ->
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) {
                    try { Earcons.play("send") } catch (_: Throwable) {}
                    Atlas.typed(ctx, t)
                    v.text = ""
                }
                endTyping()
                armAutoCollapse(15_000)
                true
            }
        }
        input = box
        col.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // Power readout: segmented bar for the phone battery.
        val pwr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pwr.addView(mono(9f, Holo.CYAN_DIM).apply { text = "PWR" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        powerBar = SegmentBar(ctx).also { pwr.addView(it, LinearLayout.LayoutParams(0, dp(8), 1f)) }
        powerText = mono(9f, Holo.CYAN).also { pwr.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }) }
        col.addView(pwr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val w = dp(264)
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        val ol = orbLp
        lp.x = if (edgeRight) screenW - w - dp(8) else dp(8)
        lp.y = ((ol?.y ?: yFor(yFrac)) - dp(HUD_ORB_DP) / 2).coerceIn(minY(), (screenH - navBarH() - dp(440)).coerceAtLeast(minY()))
        panel = FrameLayout(ctx).apply { addView(col) }
        wm.addView(panel, lp)
        // Materialise like a hologram: a thin horizontal slit that unfolds to full height.
        hudCol = col
        col.cameraDistance = dp(1200).toFloat()
        col.rotationX = -78f
        col.animate().rotationX(0f).setDuration(340).setInterpolator(DecelerateInterpolator(1.6f)).start()
        panel?.apply {
            alpha = 0f
            animate().alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        }
        haptic(HapticFeedbackConstants.LONG_PRESS)
        wake()
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            sensors = sm
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(tilt, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }
        } catch (_: Throwable) {}
        refreshHud()
        armAutoCollapse()
    }

    private val autoCollapse = Runnable { collapse() }
    private fun armAutoCollapse(ms: Long = 6_000) {
        main.removeCallbacks(autoCollapse)
        if (!typing && !(convo && state != "idle")) main.postDelayed(autoCollapse, ms)
    }

    private fun setFocusable(on: Boolean) {
        val pv = panel ?: return
        val lp = pv.layoutParams as? WindowManager.LayoutParams ?: return
        if (on) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { wm.updateViewLayout(pv, lp) } catch (_: Throwable) {}
    }

    private fun beginTyping() {
        if (typing) return
        typing = true
        main.removeCallbacks(autoCollapse)
        setFocusable(true)
        input?.post {
            input?.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)
        }
    }

    private fun endTyping() {
        if (!typing) return
        typing = false
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input?.windowToken, 0)
        } catch (_: Throwable) {}
        setFocusable(false)
    }

    private fun toggleQuiet() {
        Prefs.quietUntil = if (Prefs.quietUntil > System.currentTimeMillis()) 0L else System.currentTimeMillis() + 60 * 60_000
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        refreshHud()
        armAutoCollapse()
    }

    private fun collapse(save: Boolean = true) {
        main.removeCallbacks(autoCollapse)
        if (!expanded) return
        expanded = false
        try { Earcons.play("close") } catch (_: Throwable) {}
        try { sensors?.unregisterListener(tilt) } catch (_: Throwable) {}
        sensors = null
        // Fold back to a slit, then remove; state is cleared immediately so a quick re-open is safe.
        panel?.let { pv ->
            hudCol?.animate()?.rotationX(78f)?.setDuration(170)?.start()
            pv.animate().alpha(0f).setDuration(170).withEndAction {
                try { wm.removeView(pv) } catch (_: Throwable) {}
            }.start()
        }
        catcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; catcher = null; hudOrb = null; globe = null; jobsLine = null
        stateLine = null; saidLine = null; heardLine = null; musicChip = null; glassesChip = null; sizeChip = null
        clockLine = null; powerBar = null; powerText = null; quietChip = null; input = null; typing = false; idLine = null; hudCol = null; convo = false
        if (save) {
            orb?.visibility = View.VISIBLE
            lastTouchAt = System.currentTimeMillis()
            orbLp?.let { it.x = xFor(edgeRight); it.y = yFor(yFrac) }
            updateOrb()
            scheduleRoam()
        }
    }

    private fun loadTiles(ctx: Context, tiles: Map<String, TextView>) {
        fun short(t: String, n: Int) = t.trim().replace(Regex("\\s+"), " ").let { if (it.length > n) it.take(n - 1) + "…" else it }
        fun put(name: String, value: String, bad: Boolean = false) = main.post {
            val v = tiles[name] ?: return@post
            if (v.parent == null) return@post
            v.text = "$name\n$value"
            v.setTextColor(Color.parseColor(if (bad) AlertLevel.colorHex(AlertLevel.WARN) else Holo.CYAN))
        }
        Thread {
            try { val ms = com.jondude.anzu.assist.Obelisk.pingMs(); put("OBELISK", if (ms >= 0) "${ms}ms" else "OFFLINE", ms < 0) } catch (_: Throwable) { put("OBELISK", "—", true) }
            try { val ms = com.jondude.anzu.assist.HomeAssistant.pingMs(); put("HOME", if (ms >= 0) "${ms}ms" else "OFFLINE", ms < 0) } catch (_: Throwable) { put("HOME", "—", true) }
            try { val w = com.jondude.anzu.assist.Weather.brief(); put("WEATHER", if (w.isBlank()) "n/a" else short(w, 18)) } catch (_: Throwable) { put("WEATHER", "—") }
            try { val a = com.jondude.anzu.assist.Phone.agenda(ctx, 0); put("NEXT", if (a.isBlank()) "clear" else short(a, 18)) } catch (_: Throwable) { put("NEXT", "—") }
        }.start()
    }

    // ---------------------------------------------------------------- hold to peek
    private var peeking = false

    /** Holding the waveform shows a quick glance card that vanishes on release; tap still opens everything. */
    private fun peek() {
        if (expanded || orb == null) return
        peeking = true
        haptic(HapticFeedbackConstants.LONG_PRESS)
        try { Earcons.play("lock") } catch (_: Throwable) {}
        val bat = try { (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Throwable) { -1 }
        val time = java.text.SimpleDateFormat("HH:mm  EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date())
        val base = listOf(time, "PWR ${if (bat >= 0) "$bat%" else "—"}  //  ATLAS ${state.uppercase()}")
        showCard("Glance", base)
        val ctx = this
        Thread {
            val ag = try { com.jondude.anzu.assist.Phone.agenda(ctx, 0) } catch (_: Throwable) { "" }
            val wx = try { com.jondude.anzu.assist.Weather.brief() } catch (_: Throwable) { "" }
            val extra = HoloCards.lines(wx, 1) + HoloCards.lines(ag, 2)
            if (extra.isNotEmpty()) main.post { if (peeking) showCard("Glance", base + extra) }
        }.start()
    }

    private fun endPeek() {
        if (!peeking) return
        peeking = false
        if (!cardPinned) fadeCard()
    }

    // ---------------------------------------------------------------- morning briefing
    /** In-process guard: the morning greeting runs at most once per service instance, however the receiver fires. */
    @Volatile private var morningRan = false

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { maybeMorning() }
    }

    /** True when the morning greeting will run at the next unlock — the one condition both the startup announcement and [maybeMorning] check. */
    private fun morningPending(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        return hour in 5..10 && getSharedPreferences(PREFS, MODE_PRIVATE).getInt("morningDay", 0) != day
    }

    /**
     * The one startup announcement: boot earcon + "ATLAS ONLINE" bubble. Skipped when the morning
     * greeting is pending (that greeting IS this startup's announcement) and when an announcement
     * already played in the last 30 minutes, so sticky restarts and surface recreations stay silent.
     */
    private fun announceOnline(sp: android.content.SharedPreferences) {
        if (morningPending()) return
        val now = System.currentTimeMillis()
        if (now - sp.getLong("onlineAnnouncedAt", 0L) < 30 * 60_000L) return
        // Committed synchronously, before the bubble posts: two rapid onCreate passes (an Android Auto
        // connect burst restarts this service back to back) must not both slip past the guard.
        sp.edit().putLong("onlineAnnouncedAt", now).commit()
        com.jondude.anzu.Ears.earcon("boot")
        main.postDelayed({ showBubble("ATLAS ONLINE") }, 700)
    }

    /** First unlock between 5 and 11 am each day: boot chirp, rim glow, then weather and agenda cards in sequence. */
    private fun maybeMorning() {
        if (!morningPending() || orb == null) return
        val cal = java.util.Calendar.getInstance()
        val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("morningDay", day).apply()
        runMorning()
    }

    private fun runMorning() {
        if (morningRan) return
        morningRan = true
        // The greeting is this startup's announcement: stamp it so a restart within 30 minutes doesn't announce again.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("onlineAnnouncedAt", System.currentTimeMillis()).commit()
        val greet = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning, sir." else -> "Hello, sir." }
        wake()
        try { Earcons.play("boot") } catch (_: Throwable) {}
        showEdge()
        orb?.playAttention()
        main.postDelayed({ showBubble("$greet Systems online. Here's your day.") }, 500)
        val ctx = this
        Thread {
            val wx = try { com.jondude.anzu.assist.Weather.brief() } catch (_: Throwable) { "" }
            val ag = try { com.jondude.anzu.assist.Phone.agenda(ctx, 0) } catch (_: Throwable) { "" }
            if (wx.isNotBlank()) main.postDelayed({ showCard("Weather", HoloCards.lines(wx, 3)) }, 2_200)
            if (ag.isNotBlank()) main.postDelayed({ showCard("Today", HoloCards.lines(ag, 5)) }, 7_000)
            main.postDelayed({ if (state == "idle") hideEdge() }, 14_000)
            val hello = try { com.jondude.anzu.assist.Brain.rapport() } catch (_: Throwable) { "" }
            if (hello.isNotBlank()) main.postDelayed({ com.jondude.anzu.assist.Atlas.polite(hello, source = "rapport") }, 9_000)
        }.start()
    }

    // ---------------------------------------------------------------- edge glow + alerts
    private fun showEdge() {
        if (edge != null) return
        try {
            val v = EdgeGlowView(this).apply { colorHex = AlertLevel.colorHex(alertLevel) }
            wm.addView(
                v,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
            )
            edge = v
        } catch (_: Throwable) {}
    }

    /** The rim takes its colour from what's happening: cyan listening, deep blue thinking, bright ice speaking, warm when Jon sounds stretched. */
    private fun tintEdge() {
        if (alertLevel != AlertLevel.NORMAL) return
        edge?.colorHex = Ambient.hex(state, moodKind)
    }

    private fun hideEdge() {
        if (alertLevel != AlertLevel.NORMAL) return   // alerts keep the rim lit until they reset
        edge?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        edge = null
    }

    private fun applyAlert(level: Int) {
        alertLevel = level
        orb?.setAlert(level); hudOrb?.setAlert(level); globe?.setAlert(level)
        if (level == AlertLevel.NORMAL) {
            main.removeCallbacks(alertReset)
            edge?.pulseHz = 1.2f
            if (state == "idle") hideEdge() else edge?.colorHex = Holo.CYAN
        } else {
            showEdge()
            edge?.colorHex = AlertLevel.colorHex(level)
            edge?.pulseHz = if (level >= AlertLevel.CRIT) 2.6f else 1.6f
        }
    }

    private fun raiseAlert(level: Int, text: String) {
        applyAlert(level)
        if (level != AlertLevel.NORMAL) {
            main.removeCallbacks(alertReset)
            main.postDelayed(alertReset, AlertLevel.holdMs(level))
            try { Earcons.play(if (level >= AlertLevel.CRIT) "critical" else "urgent") } catch (_: Throwable) {}
            wake()
            showBubble(text)
        }
    }

    /** Default -> Small -> Mini -> Default. The orb resizes in place and keeps its edge and height. */
    private fun cycleSize() {
        sizeKey = OrbSize.next(sizeKey)
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SIZE, sizeKey).apply()
        orbLp?.let { it.width = orbPx; it.height = orbPx; it.x = xFor(edgeRight); it.y = yFor(yFrac) }
        updateOrb()
        refreshHud()
        armAutoCollapse()
    }

    private fun refreshHud() {
        if (!expanded) return
        stateLine?.text = "◢ " + Holo.tag(when (state) {
            "listening" -> "Listening"
            "thinking" -> "Thinking"
            "speaking" -> "Speaking"
            else -> "Standing by"
        })
        saidLine?.text = Glasses.lastSaid.value.takeIf { it.isNotBlank() }?.let { "ATLAS ▸ $it" } ?: ""
        heardLine?.text = Glasses.lastHeard.value.takeIf { it.isNotBlank() }?.let { "YOU ▸ $it" } ?: ""
        musicChip?.text = if (Atlas.musicPlaying()) "♪ AUDIO ON" else "♪ AUDIO IDLE"
        glassesChip?.text = if (Glasses.connected.value) "◉ LINK UP" else "○ LINK DOWN"
        sizeChip?.text = "SIZE ${Holo.tag(OrbSize.label(sizeKey))}"
        quietChip?.text = if (Prefs.quietUntil > System.currentTimeMillis()) "RESUME" else "QUIET 1H"
        clockLine?.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        try {
            val pct = (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) { powerBar?.percent = pct; powerText?.text = "$pct%" }
        } catch (_: Throwable) {}
    }

    companion object {
        const val ACTION_ATTENTION = "com.jondude.anzu.overlay.ATTENTION"
        private const val ACTION_HIDE = "com.jondude.anzu.overlay.HIDE"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 31
        private const val PREFS = "overlay"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RIGHT = "edgeRight"
        private const val KEY_Y = "yFrac"
        private const val KEY_SIZE = "orbSize"
        private const val SIZE_DEFAULT = "default"
        private const val KEY_ERROR = "lastError"
        private const val KEY_CRUMBS = "crumbs"
        private const val MAX_TRACE = 3000
        private const val MAX_CRUMBS = 1200
        private const val HUD_ORB_DP = 140
        private const val LONG_PRESS_MS = 500L
        private const val TAP_MS = 300L
        private const val POINT_MS = 3_000L
        private const val RING_PAD_DP = 6
        private const val RING_STROKE_DP = 3

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: OverlayService? = null

        /** The wake word was heard: open the full overlay. Safe from any thread; no-op if the overlay isn't running. */
        fun onWake() {
            val svc = instance ?: return
            svc.main.post { svc.wakeOpen() }
        }

        /** Floating info card beside the orb (weather, agenda, timers, systems). Safe from any thread; no-op if the overlay isn't running. */
        fun card(title: String, lines: List<String>) {
            val svc = instance ?: return
            svc.main.post { svc.showCard(title, lines) }
        }

        /** Colour-coded alert: lights the rim and recolours the display (amber warning, red critical). Safe from any thread. */
        fun alert(level: Int, text: String) {
            val svc = instance ?: return
            svc.main.post { svc.raiseAlert(level, text) }
        }

        /** Full-screen cyan scan sweep, used when Atlas looks at the screen. */
        fun scan() {
            val svc = instance ?: return
            svc.main.post { svc.scanSweep() }
        }

        /** Show & Point: glide the orb to the target, ring it for ~3s, then send the orb home. No-op if the service isn't running. */
        fun pointAt(ctx: Context, rect: Rect) {
            val svc = instance ?: return
            val r = Rect(rect)
            svc.main.post { svc.startPoint(r) }
        }

        fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }

        /** Starts the service (and remembers that the companion is wanted). Needs the overlay permission. */
        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            setEnabled(ctx, true)
            crumb(ctx, "start() called")
            try {
                ctx.startService(Intent(ctx, OverlayService::class.java))
            } catch (t: Throwable) {
                recordError(ctx, "start", t)
            }
        }

        /** Stores the failure (class, message, truncated stack trace, timestamp) so Settings can show it. Never throws. */
        fun recordError(ctx: Context, where: String, t: Throwable) {
            try {
                val trace = android.util.Log.getStackTraceString(t).take(MAX_TRACE)
                val text = "$where: ${t.javaClass.name}: ${t.message}\n$trace\nat ${java.util.Date()}"
                ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ERROR, text).commit()
            } catch (_: Throwable) {
            }
        }

        /** Appends a timestamped line to a ring log (last ~1200 chars), committed synchronously so it survives process death. Never throws. */
        fun crumb(ctx: Context, msg: String) {
            try {
                val sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg"
                val joined = ((sp.getString(KEY_CRUMBS, "") ?: "") + "\n" + line).trim()
                sp.edit().putString(KEY_CRUMBS, joined.takeLast(MAX_CRUMBS)).commit()
            } catch (_: Throwable) {
            }
        }

        fun crumbs(ctx: Context): String = try {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CRUMBS, "") ?: ""
        } catch (_: Throwable) {
            ""
        }

        fun clearCrumbs(ctx: Context) {
            try { ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CRUMBS, "").commit() } catch (_: Throwable) {}
        }

        fun lastError(ctx: Context): String = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, "") ?: ""

        fun clearError(ctx: Context) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ERROR, "").apply()
        }

        fun stop(ctx: Context) {
            setEnabled(ctx, false)
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        /** Boot / app-launch hook: restart the companion only if it was on and the permission is still granted. */
        fun ensureRunning(ctx: Context) {
            if (isEnabled(ctx) && !isRunning && Settings.canDrawOverlays(ctx)) start(ctx)
        }
    }
}
