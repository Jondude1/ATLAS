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
import android.graphics.Paint
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
import com.jondude.anzu.Glasses
import com.jondude.anzu.assist.AssistActivity
import com.jondude.anzu.assist.Atlas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/** Floating Atlas companion: an orb in a TYPE_APPLICATION_OVERLAY window, with a long-press HUD panel. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var orb: ParticleOrbView? = null
    private var orbLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var catcher: View? = null
    private var hudOrb: ParticleOrbView? = null
    private var stateLine: TextView? = null
    private var saidLine: TextView? = null
    private var heardLine: TextView? = null
    private var musicChip: TextView? = null
    private var glassesChip: TextView? = null
    private var sizeChip: TextView? = null

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
        isRunning = false
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        glide?.cancel()
        removeHighlight()
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
        val v = ParticleOrbView(this)
        val lp = overlayParams(orbPx, orbPx).apply {
            x = xFor(edgeRight)
            y = yFor(yFrac)
        }
        v.setOnTouchListener(touch)
        orbLp = lp
        orb = v
        wm.addView(v, lp)
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
                expand()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = orbLp ?: return false
            lastTouchAt = System.currentTimeMillis()
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
                    val wasDrag = moved
                    dragging = false
                    if (wasDrag) snapToEdge()
                    else if (!longFired && e.eventTime - downAt < TAP_MS) tap()
                    scheduleRoam()
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    dragging = false
                    if (moved) snapToEdge()
                }
            }
            return true
        }
    }

    private fun tap() {
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

    // ---------------------------------------------------------------- show & point

    private val finishPoint = Runnable { endPoint() }

    private fun startPoint(rect: Rect) {
        try {
            if (orb == null || rect.isEmpty) return
            endPoint()
            pointing = true
            val lp = orbLp
            if (lp != null && !expanded) {
                val gap = dp(8)
                val tx = (rect.centerX() - orbPx / 2).coerceIn(0, (screenW - orbPx).coerceAtLeast(0))
                var ty = rect.top - orbPx - gap
                if (ty < minY()) ty = rect.bottom + gap
                glideTo(tx, ty.coerceIn(minY(), maxY()), 600L, DecelerateInterpolator())
            }
            val v = HighlightView(this, rect, dp(RING_PAD_DP).toFloat(), dp(RING_STROKE_DP).toFloat())
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

    /** One orange rounded-rect ring around [target] with a soft pulse. Touch-transparent. */
    private class HighlightView(ctx: Context, private val target: Rect, private val pad: Float, stroke: Float) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.parseColor("#FF5C00")
        }
        private val box = RectF()
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
            box.set(
                target.left - grow, target.top - grow, target.right + grow, target.bottom + grow
            )
            paint.alpha = (255 * (1f - 0.45f * f)).toInt()
            val r = (minOf(box.width(), box.height()) / 4f).coerceIn(pad, pad * 3f)
            c.drawRoundRect(box, r, r, paint)
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
                refreshHud()
            }
        }
        scope.launch {
            Glasses.lastSaid.collect { said ->
                if (said.isNotBlank() && said != lastSaid && state == "idle") {
                    orb?.playAttention()
                    hudOrb?.playAttention()
                }
                lastSaid = said
                refreshHud()
            }
        }
        scope.launch {
            combine(Glasses.lastHeard, Glasses.connected) { a, b -> a to b }.collect { refreshHud() }
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
        val big = ParticleOrbView(ctx, 130)
        hudOrb = big
        big.mode = orb?.mode ?: OrbMode.IDLE
        col.addView(big, LinearLayout.LayoutParams(dp(HUD_ORB_DP), dp(HUD_ORB_DP)))

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
        row2.addView(hide)
        col.addView(row2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) armAutoCollapse(); false }

        val w = dp(264)
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        val ol = orbLp
        lp.x = if (edgeRight) screenW - w - dp(8) else dp(8)
        lp.y = ((ol?.y ?: yFor(yFrac)) - dp(HUD_ORB_DP) / 2).coerceIn(minY(), (screenH - navBarH() - dp(300)).coerceAtLeast(minY()))
        panel = FrameLayout(ctx).apply { addView(col) }
        wm.addView(panel, lp)
        panel?.apply {
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
        refreshHud()
        armAutoCollapse()
    }

    private val autoCollapse = Runnable { collapse() }
    private fun armAutoCollapse() {
        main.removeCallbacks(autoCollapse)
        main.postDelayed(autoCollapse, 6_000)
    }

    private fun collapse(save: Boolean = true) {
        main.removeCallbacks(autoCollapse)
        if (!expanded) return
        expanded = false
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        catcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; catcher = null; hudOrb = null
        stateLine = null; saidLine = null; heardLine = null; musicChip = null; glassesChip = null; sizeChip = null
        if (save) {
            orb?.visibility = View.VISIBLE
            lastTouchAt = System.currentTimeMillis()
            orbLp?.let { it.x = xFor(edgeRight); it.y = yFor(yFrac) }
            updateOrb()
            scheduleRoam()
        }
    }

    /** Default -> Small -> Mini -> Default. The orb resizes in place and keeps its edge and height. */
    private fun cycleSize() {
        sizeKey = OrbSize.next(sizeKey)
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
