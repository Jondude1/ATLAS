package com.jondude.anzu.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsControllerCompat
import com.jondude.anzu.Ears
import com.jondude.anzu.Glasses
import com.jondude.anzu.GlassesService
import com.jondude.anzu.MainActivity
import com.jondude.anzu.Prefs
import com.jondude.anzu.Speaker
import com.jondude.anzu.WakeListener
import com.jondude.anzu.R
import com.jondude.anzu.Store
import com.jondude.anzu.assist.Atlas
import com.jondude.anzu.assist.Brain
import com.jondude.anzu.assist.Hermes
import com.jondude.anzu.assist.HomeAssistant
import com.jondude.anzu.assist.Hub
import com.jondude.anzu.assist.Local
import com.jondude.anzu.assist.OnDevice
import com.jondude.anzu.assist.Spoken
import com.jondude.anzu.assist.Timers
import com.jondude.anzu.firstSentence
import com.jondude.anzu.has
import com.jondude.anzu.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Atlas AI Launcher — HOME role, built to Jon's chosen mockups (2026-11-20):
 *  - HOME (C1 "Living Sphere"): matte black, ONE thin glowing cyan ring holding a living
 *    sphere of drifting points, ATLAS in spaced lettering, thin clock, three quiet
 *    pale-cyan status lines, a minimal glowing shelf, a slim dark "Ask Atlas" pill.
 *  - LISTENING (C5): the ring flares brighter/thicker, a thin cyan waveform appears
 *    under it, "Listening…" caption.
 *  - DETAIL (C4 "Titanium Rows"): frosted-glass rows with hairline cyan borders on
 *    brushed dark titanium, reached by tapping a status line.
 * Palette is strictly black / dark titanium + cyan / electric blue + white. No orange.
 * All data is real (existing hub/HA/calendar/brief plumbing, reused unchanged); unknowns
 * are labeled, never fabricated. Drawer: swipe-up or the chevron, all launchable apps
 * via PackageManager, with search.
 */
// Hud palette + HudFont live in Orb.kt (shared with the lock-screen orb, v1.2.19).

private fun hudColors() = darkColorScheme(
    primary = Hud.Cyan, onPrimary = Color(0xFF02141C),
    background = Hud.Ground, onBackground = Hud.Text,
    surface = Hud.TitaniumDark, onSurface = Hud.Text,
    surfaceVariant = Hud.Pill, onSurfaceVariant = Hud.Muted,
    outline = Hud.Hairline, error = Hud.Blue
)

private fun hudTypography() = androidx.compose.material3.Typography().let { t ->
    androidx.compose.material3.Typography(
        displayLarge = t.displayLarge.copy(fontFamily = HudFont), displayMedium = t.displayMedium.copy(fontFamily = HudFont),
        displaySmall = t.displaySmall.copy(fontFamily = HudFont), headlineLarge = t.headlineLarge.copy(fontFamily = HudFont),
        headlineMedium = t.headlineMedium.copy(fontFamily = HudFont), headlineSmall = t.headlineSmall.copy(fontFamily = HudFont),
        titleLarge = t.titleLarge.copy(fontFamily = HudFont), titleMedium = t.titleMedium.copy(fontFamily = HudFont),
        titleSmall = t.titleSmall.copy(fontFamily = HudFont), bodyLarge = t.bodyLarge.copy(fontFamily = HudFont),
        bodyMedium = t.bodyMedium.copy(fontFamily = HudFont), bodySmall = t.bodySmall.copy(fontFamily = HudFont),
        labelLarge = t.labelLarge.copy(fontFamily = HudFont), labelMedium = t.labelMedium.copy(fontFamily = HudFont),
        labelSmall = t.labelSmall.copy(fontFamily = HudFont)
    )
}

class LauncherActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-bleed black like the mockups: the launcher owns its window chrome.
        // Scoped to this activity only — the rest of the app keeps its own styling.
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // The wake word runs from the start on this phone (v1.2.17). start() is
        // idempotent; GlassesService/OverlayService call it too. The listener itself
        // still gates on the mic permission + micArmed (set by the service start on
        // resume below), so this is safe before either lands.
        WakeListener.start(this)
        OverlayService.ensureRunning(this)
        setContent {
            MaterialTheme(colorScheme = hudColors(), typography = hudTypography()) {
                LauncherHome(resumes.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        GlassesService.start(this, true)
        WakeListener.start(this)
    }
}

/** Phone battery: percent and charging, from the sticky battery intent. -1 when unreadable. */
private fun readBattery(ctx: Context): Pair<Int, Boolean> = try {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
} catch (_: Exception) {
    -1 to false
}

private fun transportLabel(ctx: Context): String = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    when {
        caps == null -> "OFFLINE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> "LINK"
    }
} catch (_: Exception) {
    "UNKNOWN"
}

private fun entityPairs(states: JSONArray): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    for (i in 0 until states.length()) {
        val o = states.optJSONObject(i) ?: continue
        out.add(o.optString("entity_id") to o.optString("state"))
    }
    return out
}

private class DrawerRow(val app: LauncherLogic.DrawerApp, val icon: androidx.compose.ui.graphics.ImageBitmap?)

@Composable
fun LauncherHome(resumes: Int) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()

    val atlasState by Glasses.atlasState.collectAsState()
    val lastSaid by Glasses.lastSaid.collectAsState()
    val micLevel by Glasses.micLevel.collectAsState()
    val heldItems by Glasses.heldItems.collectAsState()
    val heldCount by Glasses.heldCount.collectAsState()
    val wakeState by WakeListener.state.collectAsState()

    // ---- wake word mic permission (v1.2.17): asked once, from the setup card ----
    var micGranted by remember { mutableStateOf(ctx.has(Manifest.permission.RECORD_AUDIO)) }
    var micPrompted by remember { mutableStateOf(Prefs.wakeMicPrompted) }
    val micRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) WakeListener.start(appCtx)
    }
    val wakeText = LauncherLogic.wakeLabel(Prefs.wakeListen, micGranted, wakeState, Prefs.micSource)

    // ---- live data, refreshed once a minute while the launcher is up (Home's cadence) ----
    var hubUp by remember { mutableStateOf<Boolean?>(null) }
    var hermesUp by remember { mutableStateOf<Boolean?>(null) }
    var briefText by remember { mutableStateOf("") }
    var briefUrgent by remember { mutableIntStateOf(0) }
    var briefFetchedAt by remember { mutableStateOf(0L) }
    var tokenMissing by remember { mutableStateOf(Prefs.hubToken.isBlank()) }
    var nextUp by remember { mutableStateOf<List<String>?>(null) }
    var calendarGranted by remember { mutableStateOf(false) }
    var houseSummary by remember { mutableStateOf<String?>(null) }
    var houseMode by remember { mutableStateOf("House · unknown") }
    var phoneBattery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf("") }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(resumes) {
        while (true) {
            val (pct, chg) = readBattery(ctx)
            phoneBattery = pct; charging = chg
            transport = transportLabel(ctx)
            nowMs = System.currentTimeMillis()
            calendarGranted = ctx.has(Manifest.permission.READ_CALENDAR)
            micGranted = ctx.has(Manifest.permission.RECORD_AUDIO)
            nextUp = withContext(Dispatchers.IO) { Brain.todayEvents(ctx) }
            val brief = withContext(Dispatchers.IO) { Hub.brief() }
            if (brief != null) {
                hubUp = true
                briefText = brief.text
                briefUrgent = brief.urgent.size
                briefFetchedAt = System.currentTimeMillis()
            } else {
                hubUp = withContext(Dispatchers.IO) {
                    try {
                        val c = java.net.URL(com.jondude.anzu.tailnet(Prefs.obeliskUrl.trim().trimEnd('/') + "/atlas/status"))
                            .openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 3000; c.readTimeout = 3000
                        val ok = c.responseCode > 0; c.disconnect(); ok
                    } catch (_: Exception) { false }
                }
            }
            tokenMissing = Hub.needsToken || Prefs.hubToken.isBlank()
            // Hermes is hardwired (address + model built in); only its key is provisioned.
            // The moment a hub token exists and the key hasn't landed, ask the hub for it —
            // throttled inside Hub (6h), silent on failure. Any HTTP answer from the
            // hardwired Hermes address counts as reachable for the tailnet diagnosis.
            if (Prefs.hubToken.isNotBlank() && Prefs.hermesKey.isBlank()) {
                withContext(Dispatchers.IO) { Hub.provisionHermesNow() }
            }
            hermesUp = withContext(Dispatchers.IO) {
                try {
                    val c = java.net.URL(com.jondude.anzu.HERMES_URL).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 3000
                    val ok = c.responseCode > 0; c.disconnect(); ok
                } catch (_: Exception) { false }
            }
            if (HomeAssistant.configured()) {
                val states = withContext(Dispatchers.IO) { HomeAssistant.states() }
                if (states != null) {
                    houseMode = LauncherLogic.houseModeLabel(entityPairs(states))
                    houseSummary = withContext(Dispatchers.IO) { HomeAssistant.summary() }
                } else {
                    houseMode = "House · unknown"
                    houseSummary = "Couldn't reach Home Assistant."
                }
            } else {
                houseMode = "House · unknown"
                houseSummary = null
            }
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowMs = System.currentTimeMillis()
            val (pct, chg) = readBattery(ctx)
            phoneBattery = pct; charging = chg
        }
    }

    // ---- thinking elapsed (never a silent hang) ----
    var thinkingSince by remember { mutableStateOf(0L) }
    var thinkingTick by remember { mutableStateOf(0L) }
    LaunchedEffect(atlasState) {
        if (atlasState == "thinking") {
            thinkingSince = System.currentTimeMillis()
            while (true) {
                thinkingTick = System.currentTimeMillis()
                delay(1000)
            }
        } else thinkingSince = 0L
    }
    val thinkingSecs = if (thinkingSince > 0) ((thinkingTick - thinkingSince) / 1000).coerceAtLeast(0) else 0

    // ---- push-to-talk: hold starts the existing listen path, release flushes it ----
    var holding by remember { mutableStateOf(false) }
    var pttJob by remember { mutableStateOf<Job?>(null) }
    var pttNote by remember { mutableStateOf("") }
    fun startPtt() {
        if (pttJob?.isActive == true) return
        if (!ctx.has(Manifest.permission.RECORD_AUDIO)) {
            pttNote = "Microphone permission is off — grant it in Atlas → Settings."
            return
        }
        pttNote = ""
        pttJob = scope.launch {
            val heard = Ears.listen(appCtx)
            if (heard != null && heard.text.isNotBlank()) Atlas.heard(appCtx, heard.text)
        }
    }

    // ---- screens: home / detail (C4) / drawer ----
    var showDetail by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var drawerQuery by remember { mutableStateOf("") }
    var drawerRows by remember { mutableStateOf<List<DrawerRow>>(emptyList()) }
    // Loaded once at startup: the drawer needs it, and the shelf finds Muse /
    // Home Assistant by label from the same list. Every launchable app, no shortlist.
    LaunchedEffect(Unit) {
        if (drawerRows.isEmpty()) {
            drawerRows = withContext(Dispatchers.IO) {
                try {
                    val pm = ctx.packageManager
                    val found = pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    ).map {
                        LauncherLogic.DrawerApp(it.loadLabel(pm).toString(), it.activityInfo.packageName)
                    }
                    LauncherLogic.sortDrawerApps(found).map { app ->
                        val icon = try {
                            pm.getApplicationIcon(app.pkg).toBitmap(72, 72).asImageBitmap()
                        } catch (_: Exception) { null }
                        DrawerRow(app, icon)
                    }
                } catch (_: Exception) { emptyList() }
            }
        }
    }
    fun launchPackage(pkg: String): Boolean = try {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) false else { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    } catch (_: Exception) { false }
    fun launchByLabel(label: String): Boolean {
        val hit = drawerRows.firstOrNull { it.app.label.equals(label, ignoreCase = true) }
        return hit != null && launchPackage(hit.app.pkg)
    }
    var shelfNote by remember { mutableStateOf("") }
    fun shelfLaunch(label: String, action: () -> Boolean) {
        shelfNote = if (action()) "" else "$label isn't available on this phone."
    }

    // ---- ask bar ----
    var typed by remember { mutableStateOf("") }
    fun sendTyped() {
        val t = typed.trim()
        if (t.isNotEmpty()) {
            Atlas.typed(appCtx, t)
            typed = ""
        }
    }

    // ---- quick action dialogs ----
    var timerOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var missedOpen by remember { mutableStateOf(false) }
    var quickNote by remember { mutableStateOf("") }

    BackHandler(enabled = showDrawer || showDetail) {
        if (showDrawer) showDrawer = false else showDetail = false
    }

    // ---- the full last response, readable on screen (Jon, v1.2.17) ----
    // Speech-only-on-earphones means an unspoken reply must be fully readable here.
    // Spoken.lastFull is the untruncated text of the last reply; lastSaid carries the
    // same full text for lines that arrived outside the reply path. Neither is ever
    // truncated in the detail view — only the home preview line shortens.
    val fullResponse = Spoken.lastFull.ifBlank { lastSaid }
    var readResponse by remember { mutableStateOf("") }
    val unreadResponse = LauncherLogic.responseNeedsReading(fullResponse, Speaker.lastSaySuppressed, readResponse)
    LaunchedEffect(showDetail) {
        if (showDetail) readResponse = fullResponse
    }

    val tier = LauncherLogic.brainTierLabel(
        hermesReady = Hermes.configured() && Prefs.hermesKey.isNotBlank(),
        onDevicePreferred = OnDevice.preferred(ctx),
        hasApiKey = Brain.hasKey(),
        hermesModel = Prefs.hermesModel,
        claudeModel = Prefs.model,
        localConfigured = Local.configured(),
        localModel = Prefs.ollamaModel
    )
    val animate = LauncherLogic.orbAnimates(phoneBattery)
    val listening = holding || atlasState == "listening"
    val effectiveState = when {
        listening -> "listening"
        else -> atlasState
    }

    // ================= DRAWER =================
    if (showDrawer) {
        val visible = LauncherLogic.filterDrawerApps(drawerRows.map { it.app }, drawerQuery)
            .mapNotNull { app -> drawerRows.firstOrNull { it.app.pkg == app.pkg } }
        Column(Modifier.fillMaxSize().background(Hud.Ground).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Hud.Cyan, fontSize = 30.sp, modifier = Modifier.clickable { showDrawer = false }.padding(end = 12.dp))
                Column {
                    Text("ATLAS · DRAWER", color = Hud.Cyan, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 2.2.sp)
                    Text("ALL APPS", color = Hud.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 3.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = drawerQuery, onValueChange = { drawerQuery = it },
                placeholder = { Text("Search apps…", color = Hud.Muted, fontFamily = HudFont) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Hud.Pill, focusedContainerColor = Hud.Pill,
                    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Hud.Cyan,
                    cursorColor = Hud.Ring, unfocusedTextColor = Hud.Text, focusedTextColor = Hud.Text
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (drawerRows.isEmpty()) {
                Text("Loading apps…", color = Hud.Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp))
            } else if (visible.isEmpty()) {
                Text("No apps match “${drawerQuery}”.", color = Hud.Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.app.pkg }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (launchPackage(row.app.pkg)) showDrawer = false }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (row.icon != null) Image(row.icon, contentDescription = null, modifier = Modifier.size(38.dp))
                        else Box(Modifier.size(38.dp).clip(CircleShape).border(1.dp, Hud.Hairline, CircleShape))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(row.app.label, color = Hud.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.app.pkg, color = Hud.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Hairline.copy(alpha = 0.35f)))
                }
            }
        }
        return
    }

    // ================= DETAIL (C4 Titanium Rows) =================
    if (showDetail) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Hud.TitaniumLight, Hud.TitaniumDark, Color(0xFF07090C))))
                .verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Hud.Cyan, fontSize = 30.sp, modifier = Modifier.clickable { showDetail = false }.padding(end = 14.dp))
                // mini ring, C4-style: thin circle with one brighter arc
                Canvas(Modifier.size(54.dp)) {
                    drawCircle(Hud.Ring.copy(alpha = 0.75f), size.minDimension / 2 - 2.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    drawArc(Hud.Ring, -40f, 70f, false, style = Stroke(2.5.dp.toPx()))
                }
                Spacer(Modifier.width(14.dp))
                Text("A T L A S", color = Hud.Text, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 4.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    SimpleDateFormat("h:mm", Locale.US).format(Date(nowMs)),
                    color = Hud.Ring, fontFamily = HudFont, fontWeight = FontWeight.Medium, fontSize = 22.sp
                )
            }
            Spacer(Modifier.height(26.dp))
            if (fullResponse.isNotBlank()) {
                // The whole reply, untruncated and scrollable with the page — this is
                // where Jon reads what the headphones-only rule kept him from hearing.
                DetailRow("LAST RESPONSE") {
                    Text(fullResponse, color = Hud.Text, fontSize = 16.sp, lineHeight = 23.sp)
                }
            }
            DetailRow("NEXT UP") {
                when {
                    !calendarGranted -> Text("Calendar permission not granted.", color = Hud.Muted, fontSize = 16.sp)
                    nextUp == null -> Text("Checking the calendar…", color = Hud.Muted, fontSize = 16.sp)
                    nextUp!!.isEmpty() -> Text("Nothing else today.", color = Hud.Text, fontSize = 17.sp)
                    else -> nextUp!!.forEach { Text(it, color = Hud.Text, fontSize = 17.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
            DetailRow("HOUSE") {
                Text(
                    houseSummary ?: "Not set up — add Home Assistant in Atlas → Settings → Home Assistant.",
                    color = if (houseSummary == null) Hud.Muted else Hud.Text, fontSize = 16.sp, lineHeight = 22.sp
                )
                Text(houseMode, color = Hud.Pale, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            DetailRow("BRIEF") {
                val stale = if (hubUp == false && briefFetchedAt > 0)
                    LauncherLogic.staleText(System.currentTimeMillis() - briefFetchedAt) else null
                when {
                    tokenMissing && briefText.isBlank() ->
                        Text("The hub needs its token — Atlas → Settings → Obelisk and local models → Hub token.", color = Hud.Muted, fontSize = 16.sp, lineHeight = 22.sp)
                    briefText.isBlank() ->
                        Text(if (hubUp == false) "Hub offline — no brief yet." else "No brief yet.", color = Hud.Muted, fontSize = 16.sp)
                    else -> {
                        Text(briefText, color = Hud.Text, fontSize = 16.sp, lineHeight = 22.sp)
                        if (stale != null) Text(stale.uppercase(), color = Hud.Cyan, fontSize = 11.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            DetailRow("SYSTEM") {
                DiagLine("BRAIN", tier)
                DiagLine("WAKE", wakeText)
                DiagLine("HUB", LauncherLogic.hubLabel(hubUp))
                DiagLine("LINK", LauncherLogic.linkLabel(LauncherLogic.linkState(hubUp, hermesUp)) + " · " + transport)
                DiagLine("POWER", (if (phoneBattery >= 0) "$phoneBattery%" else "unknown") + if (charging) " · charging" else "")
            }
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    // ================= HOME (C1 Living Sphere) =================
    // Three quiet status lines, exactly the C1 slots: next event / house / brief.
    val nextLine = when {
        !calendarGranted -> "Calendar permission not granted"
        nextUp == null -> "Checking your calendar…"
        nextUp!!.isEmpty() -> "Nothing else today"
        else -> nextUp!!.first()
    }
    val houseLine = when {
        houseSummary == null -> "House not set up"
        else -> firstSentence(houseSummary!!)
    }
    val briefLine = when {
        briefUrgent > 0 -> "$briefUrgent urgent item${if (briefUrgent == 1) "" else "s"} in your brief"
        briefText.isNotBlank() -> "Your brief is ready"
        hubUp == false -> "Brief unavailable · hub offline"
        else -> "No brief yet"
    }

    Column(
        Modifier.fillMaxSize().background(Hud.Ground)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -28f) showDrawer = true
                }
            }
            .verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Slim diagnostics line — instrument data, kept quiet like everything on this screen.
        val linkState = LauncherLogic.linkState(hubUp, hermesUp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TinyDiag("BRAIN", tier.substringBefore(" ·").ifBlank { tier })
            TinyDiag("HUB", LauncherLogic.hubLabel(hubUp).substringAfter("· ").uppercase())
            TinyDiag("PWR", if (phoneBattery >= 0) "$phoneBattery%" else "—")
            TinyDiag(
                "LINK", when (linkState) {
                    "TAILNET_DOWN" -> "TAILNET DOWN"
                    "HUB_DOWN" -> "HUB DOWN"
                    "HERMES_DOWN" -> "HERMES DOWN"
                    "CHECKING" -> "…"
                    else -> transport
                }
            )
            // Compact form for the strip; the full label is on the C4 SYSTEM rows.
            TinyDiag(
                "WAKE", when (wakeText) {
                    "LISTENING · PHONE MIC" -> "LISTENING"
                    "NO MIC PERMISSION" -> "NO MIC"
                    else -> wakeText
                }
            )
        }
        Spacer(Modifier.height(18.dp))

        // The ring + living sphere (C1). Hold = push-to-talk (C5 flare while held).
        SphereStage(
            state = effectiveState,
            animate = animate,
            micLevel = micLevel,
            batteryPct = phoneBattery,
            onHoldStart = { holding = true; startPtt() },
            onHoldEnd = { holding = false; Ears.stopListening() }
        )

        Text(
            "A T L A S", color = Hud.Text, fontFamily = HudFont, fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp, letterSpacing = 6.sp, modifier = Modifier.padding(top = 10.dp)
        )

        if (listening) {
            // C5: waveform under the ring, Listening… caption.
            Spacer(Modifier.height(14.dp))
            Waveform(micLevel = micLevel, animate = animate)
            Text(
                "Listening…", color = Hud.Text, fontFamily = HudFont, fontWeight = FontWeight.Medium,
                fontSize = 19.sp, modifier = Modifier.padding(top = 12.dp)
            )
            if (pttNote.isNotBlank()) {
                Text(pttNote, color = Hud.Cyan, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            // Thin clock (C1) + the three quiet status lines (tap through to C4 detail).
            Text(
                SimpleDateFormat("h:mm", Locale.US).format(Date(nowMs)),
                color = Hud.Text, fontFamily = HudFont, fontWeight = FontWeight.Medium, fontSize = 34.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                SimpleDateFormat("EEEE · MMM d", Locale.US).format(Date(nowMs)),
                color = Hud.Muted, fontFamily = HudFont, fontSize = 12.sp, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.clickable { showDetail = true },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                StatusLine(nextLine)
                StatusLine(houseLine)
                StatusLine(briefLine)
            }
            // State line: thinking with elapsed, or the last thing Atlas said.
            val stateLine = when {
                pttNote.isNotBlank() -> pttNote
                effectiveState == "thinking" -> "Thinking · ${thinkingSecs}s"
                effectiveState == "speaking" -> "Speaking…"
                lastSaid.isNotBlank() -> "“${lastSaid.take(140)}${if (lastSaid.length > 140) "…" else ""}”"
                else -> "Hold the sphere to talk"
            }
            Text(
                stateLine, color = Hud.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp)
                    .then(if (lastSaid.isNotBlank()) Modifier.clickable { showDetail = true } else Modifier)
            )
            if (unreadResponse) {
                // The reply was never spoken (no earphones): make the unread full text
                // unmissable until it's opened. One quiet cyan line, house style.
                Text(
                    "TAP TO READ FULL RESPONSE", color = Hud.Ring, fontFamily = HudFont,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.8.sp,
                    modifier = Modifier.clickable { showDetail = true }
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 2.dp)
                )
            }
            // Quiet quick actions (brief scope), text-weight only so the screen stays C1-quiet.
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                QuietAction("TIMER") { timerOpen = true }
                QuietAction("NOTE") { noteOpen = true }
                QuietAction(if (heldCount > 0) "MISSED · $heldCount" else "MISSED") { missedOpen = true }
            }
        }

        Spacer(Modifier.height(26.dp))
        // Chevron affordance for the drawer (the mockups' handle) — swipe-up works too.
        Text(
            "⌃", color = Hud.Muted, fontSize = 20.sp,
            modifier = Modifier.clickable { showDrawer = true }.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        // Minimal glowing shelf: thin-ring glyph nodes, C1 style.
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            ShelfGlyph("PH", "Phone") {
                shelfLaunch("Phone") {
                    try { ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                    catch (_: Exception) { false }
                }
            }
            ShelfGlyph("MS", "Messages") {
                shelfLaunch("Messages") {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ); true
                    } catch (_: Exception) { false }
                }
            }
            ShelfGlyph("CM", "Camera") {
                shelfLaunch("Camera") {
                    try { ctx.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                    catch (_: Exception) { false }
                }
            }
            ShelfGlyph("MU", "Muse") { shelfLaunch("Muse") { launchByLabel("Muse") } }
            ShelfGlyph("HA", "Home") { shelfLaunch("Home Assistant") { launchByLabel("Home Assistant") } }
            ShelfGlyph("ST", "Settings") {
                shelfLaunch("Settings") {
                    try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                    catch (_: Exception) { false }
                }
            }
        }
        if (shelfNote.isNotBlank()) {
            Text(shelfNote, color = Hud.Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(14.dp))

        // The Ask Atlas pill (C1): slim, dark, rounded — the ask bar lives inside it.
        TextField(
            value = typed, onValueChange = { typed = it },
            placeholder = {
                Text("Ask Atlas", color = Hud.Muted, fontFamily = HudFont, fontWeight = FontWeight.Medium,
                    fontSize = 17.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Hud.Pill, focusedContainerColor = Hud.Pill,
                unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                cursorColor = Hud.Ring, unfocusedTextColor = Hud.Text, focusedTextColor = Hud.Text
            ),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.width(264.dp).height(52.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { sendTyped() })
        )
        if (typed.isNotBlank()) {
            Text(
                "SEND", color = Hud.Ring, fontFamily = HudFont, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.clickable { sendTyped() }.padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
            )
        }

        // First-run setup, in C4 row language so it belongs on this phone.
        if (tokenMissing) {
            Spacer(Modifier.height(18.dp))
            DetailRow("SETUP REQUIRED") {
                Text(
                    "Add the hub token so Atlas can reach Obelisk and auto-provision its brain: Atlas → Settings → Obelisk and local models → Hub token.",
                    color = Hud.Text, fontSize = 15.sp, lineHeight = 21.sp
                )
                Text(
                    "OPEN ATLAS", color = Hud.Ring, fontFamily = HudFont, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.clickable {
                        try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { }
                    }.padding(top = 10.dp)
                )
            }
        }
        // Wake word needs the microphone once (v1.2.17). Asked exactly once: after a
        // denial this card never comes back — only the WAKE diagnostics line remains.
        if (Prefs.wakeListen && !micGranted && !micPrompted) {
            Spacer(Modifier.height(18.dp))
            DetailRow("SETUP · MICROPHONE") {
                Text(
                    "Atlas listens for its name through this phone's microphone — nothing leaves the phone until it hears it. Android needs your OK once.",
                    color = Hud.Text, fontSize = 15.sp, lineHeight = 21.sp
                )
                Text(
                    "GRANT MICROPHONE", color = Hud.Ring, fontFamily = HudFont, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.clickable {
                        Prefs.wakeMicPrompted = true
                        micPrompted = true
                        micRequest.launch(Manifest.permission.RECORD_AUDIO)
                    }.padding(top = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    // ===== Dialogs =====
    if (timerOpen) {
        AlertDialog(
            onDismissRequest = { timerOpen = false },
            containerColor = Hud.TitaniumDark, titleContentColor = Hud.Text, textContentColor = Hud.Text,
            title = { Text("SET TIMER", fontFamily = HudFont, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 5, 10, 15, 30, 60).forEach { mins ->
                        Text(
                            "$mins MIN", color = Hud.Ring, fontSize = 18.sp, fontFamily = HudFont, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable {
                                Timers.add(mins.toDouble(), "Timer")
                                timerOpen = false
                            }.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { timerOpen = false }) { Text("CANCEL", color = Hud.Cyan) } }
        )
    }
    if (noteOpen) {
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            containerColor = Hud.TitaniumDark, titleContentColor = Hud.Text, textContentColor = Hud.Text,
            title = { Text("QUICK NOTE", fontFamily = HudFont, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) },
            text = {
                TextField(
                    value = quickNote, onValueChange = { quickNote = it },
                    placeholder = { Text("Note…", color = Hud.Muted) },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = Hud.Pill, focusedContainerColor = Hud.Pill,
                        unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Hud.Cyan,
                        cursorColor = Hud.Ring, unfocusedTextColor = Hud.Text, focusedTextColor = Hud.Text
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (quickNote.isNotBlank()) Store.addNote(quickNote.trim())
                    quickNote = ""
                    noteOpen = false
                }) { Text("SAVE", color = Hud.Cyan) }
            },
            dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("CANCEL", color = Hud.Cyan) } }
        )
    }
    if (missedOpen) {
        AlertDialog(
            onDismissRequest = { missedOpen = false },
            containerColor = Hud.TitaniumDark, titleContentColor = Hud.Text, textContentColor = Hud.Text,
            title = { Text("WHAT DID I MISS?", fontFamily = HudFont, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) },
            text = {
                // Every held line, in full, scrollable: held lines are stored whole
                // (Glasses.hold never truncates), and an announcement Jon couldn't hear
                // must be readable end to end here — no silent 8-item cap.
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (heldItems.isEmpty()) Text("Nothing was held back.", color = Hud.Muted, fontSize = 16.sp)
                    else heldItems.reversed().forEach { line ->
                        Text(line, color = Hud.Text, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = {
                if (heldItems.isNotEmpty()) TextButton(onClick = { Glasses.takeHeld(); missedOpen = false }) {
                    Text("CLEAR", color = Hud.Cyan)
                }
            },
            dismissButton = { TextButton(onClick = { missedOpen = false }) { Text("CLOSE", color = Hud.Cyan) } }
        )
    }
}

/** One quiet pale-cyan status line (C1). */
@Composable
private fun StatusLine(text: String) {
    Text(
        text, color = Hud.Pale, fontFamily = HudFont, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
    )
}

@Composable
private fun QuietAction(label: String, onClick: () -> Unit) {
    Text(
        label, color = Hud.Muted, fontFamily = HudFont, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, letterSpacing = 1.8.sp,
        modifier = Modifier.clickable { onClick() }.padding(6.dp)
    )
}

@Composable
private fun TinyDiag(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Hud.Muted, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = Hud.Pale, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp, maxLines = 1)
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Hud.Muted, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.8.sp, modifier = Modifier.width(64.dp))
        Text(value, color = Hud.Text, fontSize = 15.sp)
    }
}

/** A C4 frosted row: rounded glass, hairline cyan border, small cyan section label, then content. */
@Composable
private fun DetailRow(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Hud.Glass)
            .border(1.dp, Hud.Hairline.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(title, color = Hud.Ring, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 2.4.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Minimal glowing shelf node (C1): a thin cyan ring with a two-letter glyph and a tiny label. */
@Composable
private fun ShelfGlyph(tag: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .border(1.dp, Hud.Ring.copy(alpha = 0.85f), CircleShape)
                .background(Brush.radialGradient(listOf(Hud.Ring.copy(alpha = 0.10f), Color.Transparent)))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(tag, color = Hud.Ring, fontFamily = HudFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(
            label.uppercase(), color = Hud.Muted, fontFamily = HudFont, fontWeight = FontWeight.Medium,
            fontSize = 8.sp, letterSpacing = 0.6.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * The C5 waveform: thin cyan bars whose heights follow the live mic level, with a
 * deterministic per-bar envelope so it reads as a voice trace, not noise. When the
 * battery rule has paused animation it draws a flat dotted line instead.
 */
@Composable
private fun Waveform(micLevel: Float, animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    Canvas(Modifier.width(250.dp).height(46.dp)) {
        val bars = 41
        val bw = size.width / bars
        val mid = size.height / 2
        for (i in 0 until bars) {
            val env = sin(i * 0.55f).let { abs(it) } * 0.7f + 0.3f
            val h = if (!animate) 2.dp.toPx()
            else (2f + micLevel * env * (0.7f + 0.3f * sin(phase * 2f + i * 0.8f)) * size.height * 0.46f)
            drawLine(
                Hud.Ring, Offset(i * bw + bw / 2, mid - h / 2), Offset(i * bw + bw / 2, mid + h / 2),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

/**
 * The living sphere in its one thin ring (C1). The ring is drawn here — thin and quiet
 * at idle, flared bright and thick while listening (C5), with a rotating arc while
 * thinking. Under the battery rule (<20%) all motion stops and the pause is labeled.
 */
@Composable
private fun SphereStage(
    state: String,
    animate: Boolean,
    micLevel: Float,
    batteryPct: Int,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "sphere")
    val arcRot by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "arc"
    )
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )
    val ringSize = 272.dp
    Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2 - 6.dp.toPx()
            when (state) {
                "listening" -> {
                    // C5 flare: thick bright ring + wide soft glow.
                    drawCircle(Hud.Ring.copy(alpha = 0.20f), r, c, style = Stroke(16.dp.toPx()))
                    drawCircle(Hud.Ring.copy(alpha = 0.45f), r, c, style = Stroke(9.dp.toPx()))
                    drawCircle(Color(0xFFD9FFFF), r, c, style = Stroke(5.dp.toPx()))
                }
                "thinking" -> {
                    drawCircle(Hud.Ring.copy(alpha = 0.35f), r, c, style = Stroke(1.5.dp.toPx()))
                    rotate(if (animate) arcRot else 0f, c) {
                        drawArc(Hud.Ring, 0f, 80f, false, style = Stroke(3.dp.toPx()))
                    }
                }
                "speaking" -> {
                    val g = if (animate) 0.14f + pulse * 0.10f else 0.14f
                    drawCircle(Hud.Ring.copy(alpha = g), r, c, style = Stroke(8.dp.toPx()))
                    drawCircle(Hud.Ring, r, c, style = Stroke(2.5.dp.toPx()))
                }
                else -> {
                    // C1 idle: one thin glowing ring.
                    drawCircle(Hud.Ring.copy(alpha = 0.16f), r, c, style = Stroke(5.dp.toPx()))
                    drawCircle(Hud.Ring, r, c, style = Stroke(1.8.dp.toPx()))
                }
            }
        }
        Box(
            Modifier.size(216.dp).clip(CircleShape)
                .semantics { contentDescription = "Hold to talk to Atlas" }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onHoldStart()
                        tryAwaitRelease()
                        onHoldEnd()
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            PointSphere(state, animate, micLevel, Modifier.fillMaxSize())
        }
        if (!animate) {
            Text(
                "PAUSED · BATT ${if (batteryPct >= 0) "$batteryPct%" else "—"}",
                color = Hud.Muted, fontFamily = HudFont, fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp, letterSpacing = 1.6.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            )
        }
    }
}
