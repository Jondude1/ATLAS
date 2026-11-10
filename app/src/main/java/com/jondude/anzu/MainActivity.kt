package com.jondude.anzu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.jondude.anzu.assist.AssistActivity
import com.jondude.anzu.assist.Brain
import com.jondude.anzu.assist.Corin
import com.jondude.anzu.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Follows the phone's light or dark setting. */
private var isDark by mutableStateOf(true)

// Corin's palette: a forest at dusk in dark mode, sage paper in light mode.
// The names are historical: Gold is the accent, Cream the main text, Muted the secondary text.
private val Gold: Color get() = if (isDark) Color(0xFFE3B655) else Color(0xFF1F5D45)
private val OnGold: Color get() = if (isDark) Color(0xFF1B1405) else Color(0xFFFFFFFF)
private val Cream: Color get() = if (isDark) Color(0xFFF1ECDF) else Color(0xFF13201A)
private val Muted: Color get() = if (isDark) Color(0xFF8FA397) else Color(0xFF5E7067)
private val Ground: Color get() = if (isDark) Color(0xFF0C1512) else Color(0xFFEEF2EE)
private val Panel: Color get() = if (isDark) Color(0xFF16231D) else Color(0xFFFFFFFF)
private val Fill: Color get() = if (isDark) Color(0xFF213529) else Color(0xFFE3EAE4)
private val Hairline: Color get() = if (isDark) Color(0xFF26392F) else Color(0xFFD5DED7)

/** The display face (Bricolage Grotesque) for titles and Corin's own words. */
private val Display = FontFamily(
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_medium, FontWeight.Medium)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE3B655),
    onPrimary = Color(0xFF1B1405),
    secondary = Color(0xFF8FA397),
    onSecondary = Color(0xFF0C1512),
    background = Color(0xFF0C1512),
    onBackground = Color(0xFFF1ECDF),
    surface = Color(0xFF16231D),
    onSurface = Color(0xFFF1ECDF),
    surfaceVariant = Color(0xFF213529),
    onSurfaceVariant = Color(0xFF8FA397),
    outline = Color(0xFF3A5246),
    error = Color(0xFFFF8A73)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5D45),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF5E7067),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFEEF2EE),
    onBackground = Color(0xFF13201A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF13201A),
    surfaceVariant = Color(0xFFE3EAE4),
    onSurfaceVariant = Color(0xFF5E7067),
    outline = Color(0xFFB9C6BD),
    error = Color(0xFFB3261E)
)

/** A quiet secondary button: tinted fill, no outline. */
@Composable
fun SoftButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.14f), contentColor = Gold),
        elevation = null
    ) { content() }
}

val REQUESTED = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.ACTIVITY_RECOGNITION
)

class MainActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            isDark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (isDark) DarkColors else LightColors) {
                AppRoot(resumes.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        // Starting from a visible activity arms the reply microphone.
        GlassesService.start(this, true)
    }
}

@Composable
fun AppRoot(resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val update by Updater.available.collectAsState()
    val progress by Updater.progress.collectAsState()
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(resumes) { Updater.check() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // A floating bar: the selected tab opens into a labelled pill.
            val items = listOf(
                "Corin" to Icons.Filled.Home,
                "Notes" to Icons.Filled.Create,
                "Whisper" to Icons.Filled.Notifications,
                "Settings" to Icons.Filled.Settings
            )
            Box(Modifier.fillMaxWidth().background(Ground).padding(start = 20.dp, end = 20.dp, bottom = 14.dp, top = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Panel).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { i, (label, icon) ->
                        val selected = tab == i
                        Row(
                            Modifier.clip(RoundedCornerShape(26.dp))
                                .background(if (selected) Gold else Color.Transparent)
                                .clickable { tab = i }
                                .padding(horizontal = if (selected) 18.dp else 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = if (selected) null else label,
                                tint = if (selected) OnGold else Muted, modifier = Modifier.size(22.dp)
                            )
                            if (selected) {
                                Spacer(Modifier.width(8.dp))
                                Text(label, color = OnGold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val rel = update
            if (rel != null && !dismissed) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Gold.copy(alpha = 0.14f))
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Update available: ${rel.tag}", fontWeight = FontWeight.SemiBold)
                        if (progress >= 0f) {
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        } else {
                            Row {
                                TextButton(onClick = { scope.launch { Updater.downloadAndInstall(ctx, rel) } }) { Text("Update") }
                                TextButton(onClick = { dismissed = true }) { Text("Later") }
                            }
                        }
                    }
                }
            }
            when (tab) {
                0 -> HomeScreen(resumes)
                1 -> NotesScreen()
                2 -> WhisperScreen()
                else -> SettingsScreen()
            }
        }
    }
}

/** The screen title, set in the display face. */
@Composable
fun LargeTitle(text: String) {
    Text(
        text, fontFamily = Display, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Cream,
        letterSpacing = (-0.5).sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
    )
}

/** A titled group of related controls on one rounded panel. */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title, fontFamily = Display, fontWeight = FontWeight.Medium, color = Cream, fontSize = 21.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp, top = 6.dp)
        )
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

/** One cell of the home screen's grid. */
@Composable
fun Tile(modifier: Modifier, tint: Color = Panel, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(26.dp)).background(tint).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) { content() }
}

@Composable
fun ToggleRow(label: String, hint: String? = null, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 16.sp, color = Cream)
            if (hint != null) Text(hint, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Switch(
            checked = v, onCheckedChange = { v = it; onChange(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnGold, checkedTrackColor = Gold,
                uncheckedThumbColor = Muted, uncheckedTrackColor = Fill, uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun PrefField(label: String, initial: String, secret: Boolean = false, lines: Int = 1, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v,
        onValueChange = { v = it; onChange(it) },
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Color.Transparent, focusedBorderColor = Gold,
            unfocusedContainerColor = Fill, focusedContainerColor = Fill,
            unfocusedLabelColor = Muted, focusedLabelColor = Gold
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Corin's presence: a sphere of gold light over deep green that breathes, and quickens when listening or thinking. */
@Composable
fun Orb(state: String, diameter: Dp = 148.dp) {
    val period = when (state) {
        "listening" -> 700
        "thinking" -> 450
        "speaking" -> 1000
        else -> 3000
    }
    val light = when (state) {
        "listening" -> Color(0xFFB9F2C9)
        "speaking" -> Color(0xFFFFF4D6)
        else -> Color(0xFFF4D488)
    }
    val body = when (state) {
        "listening" -> Color(0xFF2FA36B)
        "thinking" -> Color(0xFFE3B655)
        else -> Color(0xFFC9952B)
    }
    val deep = if (state == "listening") Color(0xFF0F3B2A) else Color(0xFF1F5D45)
    key(period) {
        val transition = rememberInfiniteTransition(label = "orb")
        val pulse by transition.animateFloat(
            initialValue = 0.88f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period), RepeatMode.Reverse), label = "pulse"
        )
        Canvas(Modifier.size(diameter)) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            // a wide, faint glow that spills past the sphere
            drawCircle(Brush.radialGradient(listOf(body.copy(alpha = 0.34f), body.copy(alpha = 0.08f), Color.Transparent), c, r * pulse), r * pulse, c)
            // the sphere: bright where the light falls, deep green in its shadow
            val core = r * 0.6f * pulse
            drawCircle(
                Brush.radialGradient(listOf(light, body, deep), Offset(c.x - core * 0.35f, c.y - core * 0.4f), core * 1.7f),
                core, c
            )
        }
    }
}

private fun timeAgo(t: Long): String {
    if (t <= 0) return "never"
    return SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(t))
}

@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by Glasses.connected.collectAsState()
    val battery by Glasses.battery.collectAsState()
    val armed by Glasses.micArmed.collectAsState()
    val wearing by Glasses.wearing.collectAsState()
    val activeWear by Glasses.active.collectAsState()
    val buddie by Glasses.buddieStream.collectAsState()
    val buddieStats by BuddieStats.summary.collectAsState()
    val meeting by Glasses.meeting.collectAsState()
    val state by Glasses.corinState.collectAsState()
    val said by Glasses.lastSaid.collectAsState()
    val heard by Glasses.lastHeard.collectAsState()
    var typed by remember { mutableStateOf("") }
    var quietUntil by remember(resumes) { mutableStateOf(Prefs.quietUntil) }
    var deviceName by remember(resumes) { mutableStateOf(Prefs.deviceName) }
    var picking by remember { mutableStateOf(false) }
    var awake by remember(resumes) { mutableStateOf(Prefs.identity.isNotBlank()) }
    var waking by remember { mutableStateOf(false) }

    val missing = remember(resumes) { REQUESTED.filter { !ctx.has(it) } }
    val listenerOn = remember(resumes) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val unrestricted = remember(resumes) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        GlassesService.start(ctx, true)
    }

    fun send() {
        val t = typed.trim()
        if (t.isNotEmpty()) {
            Corin.typed(ctx, t)
            typed = ""
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { LargeTitle(Prefs.name) }
            Text(
                state, color = if (state == "idle") Muted else OnGold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (state == "idle") Panel else Gold).padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(state, 220.dp)
            Text(
                if (said.isBlank()) "Nothing said yet." else said,
                fontFamily = Display, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium, color = Cream,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (heard.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("You: $heard", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Modifier.weight(1.5f).height(118.dp)) {
                Text("Wearing", color = Muted, fontSize = 13.sp)
                Column {
                    Text(
                        when {
                            !connected -> "Nothing"
                            activeWear == "buddie" -> "Earbuds"
                            else -> "Glasses"
                        },
                        fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Cream
                    )
                    Text(
                        if (!connected) "Put them on and I'm there" else if (activeWear == "buddie") "Private, hands-free" else "Open-ear, discreet",
                        color = Muted, fontSize = 13.sp
                    )
                }
            }
            Tile(Modifier.weight(1f).height(118.dp), tint = if (connected && battery in 0..20) MaterialTheme.colorScheme.error.copy(alpha = 0.18f) else Panel) {
                Text("Battery", color = Muted, fontSize = 13.sp)
                Text(
                    if (connected && battery >= 0) "$battery%" else "–",
                    fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Cream
                )
            }
        }
        Section("Talk") {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Type to ${Prefs.name}") },
                singleLine = true, // so Enter on a hardware or wrist keyboard sends
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Gold,
                    unfocusedContainerColor = Fill, focusedContainerColor = Fill,
                    unfocusedLabelColor = Muted, focusedLabelColor = Gold
                ),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ctx.startActivity(Intent(ctx, AssistActivity::class.java)) }) { Text("Talk") }
                SoftButton(onClick = { send() }) { Text("Send") }
                SoftButton(onClick = {
                    GlassesService.start(ctx, true, GlassesService.ACTION_SPEAK_NOW)
                }) { Text("Speak up") }
            }
            val quiet = quietUntil > System.currentTimeMillis()
            SoftButton(onClick = {
                Prefs.quietUntil = if (quiet) 0L else System.currentTimeMillis() + 60 * 60_000
                quietUntil = Prefs.quietUntil
            }) { Text(if (quiet) "Quiet until ${SimpleDateFormat("h:mm a", Locale.US).format(Date(quietUntil))} · resume" else "Quiet for an hour") }
            if (!awake && Prefs.apiKey.isNotBlank()) {
                Button(enabled = !waking, onClick = {
                    waking = true
                    scope.launch {
                        val first = Brain.awaken()
                        waking = false
                        awake = Prefs.identity.isNotBlank()
                        if (first != null) Corin.announce(first)
                    }
                }) { Text(if (waking) "Coming online…" else "Wake ${Prefs.name} for the first time") }
            }
        }

        val today = remember(said, heard, resumes) {
            Store.todayLog().lines().filter { it.isNotBlank() }.takeLast(10)
                .map { it.removePrefix("- ").replace("**", "") }
        }
        if (today.isNotEmpty()) {
            Section("Today") {
                today.forEach { Text(it, fontSize = 13.sp, color = Muted) }
            }
        }

        Section("Wearables") {
            if (meeting) Text("Taking meeting notes", color = Gold, fontWeight = FontWeight.Bold)
            Text(
                if (connected) "Wearing ${wearing.ifBlank { "glasses" }}" + (if (battery >= 0) " · battery $battery%" else "") +
                    " · worn ${Glasses.wornMinutes()} min"
                else "Not connected"
            )
            Text(if (deviceName.isBlank()) "No glasses chosen yet (anything named Anzu is picked up automatically)" else "Glasses: $deviceName", color = Muted, fontSize = 13.sp)
            if (connected && activeWear.isNotBlank()) Text("Mode: ${Wear.label()}", color = Gold, fontSize = 13.sp)
            Text("Buddie earbuds stream: $buddie", color = Muted, fontSize = 13.sp)
            if (buddie != "off") Text(buddieStats, color = Muted, fontSize = 12.sp)
            Text(
                if (armed) "Conversation window armed: ${Prefs.name} can listen after speaking up."
                else "Conversation window not armed. Opening this app arms it until the phone restarts.",
                color = Muted, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(onClick = { picking = true }) { Text("Choose glasses") }
                SoftButton(onClick = {
                    try {
                        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                            .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000)
                    } catch (_: Exception) {
                    }
                }) { Text("Ping") }
            }
            Text("Last seen: ${timeAgo(Prefs.lastSeenTime)}", color = Muted, fontSize = 13.sp)
            if (Prefs.lastSeenLat.isNotBlank()) {
                SoftButton(onClick = {
                    val q = "${Prefs.lastSeenLat},${Prefs.lastSeenLon}"
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q(Glasses)")))
                    } catch (_: Exception) {
                    }
                }) { Text("Where I last had them") }
            }
        }

        if (missing.isNotEmpty() || !listenerOn || !unrestricted || Prefs.apiKey.isBlank()) {
            Section("Setup") {
                if (missing.isNotEmpty()) {
                    Text("Bluetooth, microphone, notifications, location (find my glasses) and calendar access.")
                    Button(onClick = { launcher.launch(REQUESTED) }) { Text("Grant permissions") }
                }
                if (!listenerOn) {
                    Text("Notification access lets ${Prefs.name} read messages to you.")
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Allow notification access") }
                }
                if (!unrestricted) {
                    Text("Turn off battery optimization so ${Prefs.name} keeps talking while the phone is in your pocket.")
                    Button(onClick = {
                        try {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                            )
                        } catch (_: Exception) {
                        }
                    }) { Text("Allow background running") }
                }
                if (Prefs.apiKey.isBlank()) Text("Add your Anthropic API key in Settings so ${Prefs.name} can think.")
            }
        }

        Section("Talking from the glasses") {
            Text(
                "Use the Anzu voice-assistant gesture and pick ${Prefs.name} when Android asks which app to use " +
                    "(or set it under Settings → Apps → Default apps → Digital assistant app).",
                color = Muted, fontSize = 13.sp
            )
        }
    }

    if (picking) {
        val devices: List<Pair<String, String>> = remember {
            val out = ArrayList<Pair<String, String>>()
            try {
                if (ctx.has(Manifest.permission.BLUETOOTH_CONNECT)) {
                    val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    adapter?.bondedDevices?.forEach { d -> out.add((d.name ?: d.address) to d.address) }
                }
            } catch (_: Exception) {
            }
            out
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            title = { Text("Paired devices") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired devices found. Pair the glasses in Bluetooth settings first.")
                    devices.forEach { (name, address) ->
                        TextButton(onClick = {
                            Prefs.deviceName = name
                            Prefs.deviceAddress = address
                            deviceName = name
                            picking = false
                        }) { Text(name) }
                    }
                }
            }
        )
    }
}

@Composable
fun NotesScreen() {
    val notes by Store.notes.collectAsState()
    val memory by Store.memory.collectAsState()
    val actions by Store.actions.collectAsState()
    val lists by Store.lists.collectAsState()
    var draft by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            LargeTitle("Notes")
            Text("Notes and ideas", fontFamily = Display, color = Cream, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    label = { Text("New note") }, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { Store.addNote(draft); draft = "" }) { Text("Add") }
            }
        }
        if (notes.isEmpty()) item { Text("Say \"note that…\" or \"save this idea\" and it lands here.", color = Muted) }
        items(notes.size) { i ->
            val n = notes[i]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.text)
                        Text(timeAgo(n.time), color = Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { Store.removeNote(i) }) { Text("Delete") }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Checklists", fontFamily = Display, color = Cream, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            if (lists.isEmpty()) Text("Say \"add milk to my grocery list\" or \"what's my next step?\"", color = Muted)
            lists.forEach { (name, listItems) ->
                Card(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { Store.listClear(name) }) { Text("Delete") }
                        }
                        listItems.forEachIndexed { i, item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.done, onCheckedChange = { Store.listToggle(name, i) })
                                Text(item.text, color = if (item.done) Muted else Cream)
                            }
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("What ${Prefs.name} remembers", fontFamily = Display, color = Cream, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            if (memory.isEmpty()) Text("Nothing yet. It builds up as you talk.", color = Muted)
        }
        items(memory.size) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(memory[i], Modifier.weight(1f))
                TextButton(onClick = { Store.removeMemory(i) }) { Text("Forget") }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Done on its own", fontFamily = Display, color = Cream, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            if (actions.isEmpty()) Text("Nothing yet.", color = Muted)
        }
        items(actions.take(30)) { a ->
            Text("${timeAgo(a.time)} · ${a.text}", fontSize = 13.sp, color = Muted)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun WhisperScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allowed by remember { mutableStateOf(Prefs.allowedApps) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            try {
                val pm = ctx.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .sortedWith(compareByDescending<Pair<String, String>> { it.second in allowed }.thenBy { it.first.lowercase() })
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            LargeTitle("Whisper")
            Section("Notification whisper") {
                ToggleRow("Read notifications to me", "Only while the glasses are on", Prefs.readNotifications) { Prefs.readNotifications = it }
                ToggleRow("Triage", "Messages, calls and reminders now; everything else waits until you ask \"what did I miss?\"", Prefs.triage) { Prefs.triage = it }
                Text("Whether the message itself is read out is set per wearable in Settings, under Glasses and earbuds.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Apps allowed to reach your ears", fontFamily = Display, fontWeight = FontWeight.Medium, color = Cream, fontSize = 21.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        }
        items(apps, key = { it.second }) { (label, pkg) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = pkg in allowed, onCheckedChange = { on ->
                    allowed = if (on) allowed + pkg else allowed - pkg
                    Prefs.allowedApps = allowed
                })
                Text(label)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Updater.status.collectAsState()
    var chat by remember { mutableStateOf(Prefs.chattiness.toFloat()) }
    var rate by remember { mutableStateOf(Prefs.speechRate.toFloat()) }
    var mode by remember { mutableIntStateOf(Prefs.listenMode) }
    var identity by remember { mutableStateOf(Prefs.identity) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        LargeTitle("Settings")
        Section("Brain") {
            PrefField("Anthropic API key", Prefs.apiKey, secret = true) { Prefs.apiKey = it.trim() }
            PrefField("Model", Prefs.model) { Prefs.model = it.trim() }
            PrefField("About you (things it should know from day one)", Prefs.aboutMe, lines = 3) { Prefs.aboutMe = it }
        }

        Section("Identity") {
            PrefField("Name", Prefs.name) { Prefs.name = it.trim().ifBlank { "Corin" } }
            Text(
                if (identity.isBlank()) "Not awake yet. On first connection it writes its own personality and picks its own voice, and keeps them."
                else identity,
                color = Muted, fontSize = 13.sp
            )
            if (Prefs.drift.isNotBlank()) Text("Lately: ${Prefs.drift}", color = Muted, fontSize = 13.sp)
            if (Prefs.voiceName.isNotBlank()) Text("Voice: ${Prefs.voiceName}", color = Muted, fontSize = 13.sp)
            if (identity.isNotBlank()) {
                TextButton(onClick = {
                    Prefs.identity = ""; Prefs.drift = ""; Prefs.voiceName = ""
                    identity = ""
                }) { Text("Reset identity (it will choose again)") }
            }
        }

        Section("Presence") {
            ToggleRow("Speak up on its own", "Random check-ins, thoughts and questions through the day", Prefs.proactive) { Prefs.proactive = it }
            Text("How often it speaks is set per wearable below.", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    PrefField("Active from (hour)", Prefs.activeStart.toString()) { s -> s.toIntOrNull()?.let { Prefs.activeStart = it.coerceIn(0, 23) } }
                }
                Column(Modifier.weight(1f)) {
                    PrefField("Until (hour)", Prefs.activeEnd.toString()) { s -> s.toIntOrNull()?.let { Prefs.activeEnd = it.coerceIn(0, 24) } }
                }
            }
            ToggleRow("Talk over music and podcasts", null, Prefs.talkOverMedia) { Prefs.talkOverMedia = it }
            ToggleRow("Evening wrap-up", "Asks how the day went, then writes a journal entry", Prefs.journal) { Prefs.journal = it }
            PrefField("Wrap-up hour", Prefs.journalHour.toString()) { s -> s.toIntOrNull()?.let { Prefs.journalHour = it.coerceIn(0, 23) } }
            ToggleRow("Activity aware", "No unprompted chatter while driving; opens a conversation when you head out on a walk", Prefs.activityAware) { Prefs.activityAware = it }
            ToggleRow("20-20-20 eye breaks", "Every 20 minutes of wear", Prefs.eyeBreaks) { Prefs.eyeBreaks = it }
            ToggleRow("Calendar heads-ups", "Ten minutes before events", Prefs.calendarHeadsUp) { Prefs.calendarHeadsUp = it }
            ToggleRow("Leave-now alerts", "For calendar events with a location, based on the current drive time", Prefs.commuteAlerts) { Prefs.commuteAlerts = it }
        }

        Section("Glasses and earbuds") {
            val often = listOf("now and then (about every 2 hours)", "regularly (about every 50 minutes)", "chatty (about every 20 minutes)")
            var chatBuds by remember { mutableStateOf(Prefs.chattinessBuds.toFloat()) }
            var volGlasses by remember { mutableStateOf(Prefs.volumeGlasses.toFloat()) }
            var volBuds by remember { mutableStateOf(Prefs.volumeBuds.toFloat()) }
            Text("${Prefs.name} notices which one you put on and behaves differently in each. If both are connected, the one you put on last wins.", color = Muted, fontSize = 13.sp)

            Text("In the glasses (open-ear)", fontWeight = FontWeight.Bold)
            Text("Speaks up " + often[chat.toInt().coerceIn(0, 2)])
            Slider(value = chat, onValueChange = { chat = it; Prefs.chattiness = it.toInt().coerceIn(0, 2) }, valueRange = 0f..2f, steps = 1)
            Text("Volume ${volGlasses.toInt()}%")
            Slider(value = volGlasses, onValueChange = { volGlasses = it; Prefs.volumeGlasses = it.toInt() }, valueRange = 30f..100f)
            ToggleRow("Discreet", "People nearby can hear open-ear speakers: short replies, and nothing private said out loud", Prefs.discreetGlasses) { Prefs.discreetGlasses = it }
            ToggleRow("Notifications: sender only", "Say who it's from, not what it says", Prefs.senderOnlyGlasses) { Prefs.senderOnlyGlasses = it }

            Text("In the earbuds (private)", fontWeight = FontWeight.Bold)
            Text("Speaks up " + often[chatBuds.toInt().coerceIn(0, 2)])
            Slider(value = chatBuds, onValueChange = { chatBuds = it; Prefs.chattinessBuds = it.toInt().coerceIn(0, 2) }, valueRange = 0f..2f, steps = 1)
            Text("Volume ${volBuds.toInt()}%")
            Slider(value = volBuds, onValueChange = { volBuds = it; Prefs.volumeBuds = it.toInt() }, valueRange = 30f..100f)
            ToggleRow("Notifications: sender only", "Off reads the whole message, since only you can hear", Prefs.senderOnlyBuds) { Prefs.senderOnlyBuds = it }

            ToggleRow("Say so when I switch", "A short line when you swap between them", Prefs.announceSwitch) { Prefs.announceSwitch = it }
            Text("Also different by design: eye-break nudges only happen in the glasses, and in the earbuds you answer hands-free through their own microphone stream.", color = Muted, fontSize = 12.sp)
        }

        Section("Voice") {
            var provider by remember { mutableIntStateOf(Prefs.voiceProvider) }
            var pick by remember { mutableStateOf("") }
            var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var chosen by remember { mutableStateOf(Prefs.voiceName) }
            var eleven by remember { mutableStateOf(Prefs.elevenVoiceName) }
            var voiceNote by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Phone", "ElevenLabs", "Custom").forEachIndexed { i, label ->
                    if (provider == i) Button(onClick = {}) { Text(label) }
                    else SoftButton(onClick = { provider = i; Prefs.voiceProvider = i }) { Text(label) }
                }
            }
            when (provider) {
                0 -> {
                    Text("Voices built into the phone. Higher-quality ones are listed first.", color = Muted, fontSize = 13.sp)
                    Text("Voice: ${chosen.ifBlank { "engine default" }}", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(onClick = { options = Speaker.allVoices(); pick = "voice" }) { Text("Choose voice") }
                        SoftButton(onClick = { options = Speaker.engines().map { it.second to it.first }; pick = "engine" }) { Text("Engine") }
                    }
                    SoftButton(onClick = {
                        try {
                            ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                        }
                    }) { Text("Download more voices") }
                    Text("Any speech engine app you install shows up under Engine.", color = Muted, fontSize = 12.sp)
                }
                1 -> {
                    Text("Neural voices from your ElevenLabs account, including ones you design or clone there. Uses your ElevenLabs credits.", color = Muted, fontSize = 13.sp)
                    PrefField("ElevenLabs API key", Prefs.elevenKey, secret = true) { Prefs.elevenKey = it.trim() }
                    Text("Voice: ${eleven.ifBlank { "none chosen" }}", fontSize = 14.sp)
                    SoftButton(onClick = {
                        voiceNote = "Loading…"
                        scope.launch {
                            val list = withContext(Dispatchers.IO) { CloudVoice.elevenVoices() }
                            voiceNote = if (list.isEmpty()) "Couldn't load voices. Check the key." else ""
                            if (list.isNotEmpty()) {
                                options = list
                                pick = "eleven"
                            }
                        }
                    }) { Text("Load my voices") }
                    PrefField("Model", Prefs.elevenModel) { Prefs.elevenModel = it.trim() }
                }
                else -> {
                    Text("Any server with an OpenAI-style speech endpoint: OpenAI itself, or a Kokoro or Piper server on Obelisk.", color = Muted, fontSize = 13.sp)
                    PrefField("Base URL", Prefs.speechUrl) { Prefs.speechUrl = it.trim() }
                    PrefField("API key (leave empty for a local server)", Prefs.speechKey, secret = true) { Prefs.speechKey = it.trim() }
                    PrefField("Model", Prefs.speechModel) { Prefs.speechModel = it.trim() }
                    PrefField("Voice", Prefs.speechVoice) { Prefs.speechVoice = it.trim() }
                }
            }
            if (voiceNote.isNotBlank()) Text(voiceNote, color = Muted, fontSize = 13.sp)
            SoftButton(onClick = {
                voiceNote = ""
                Corin.announce("This is how I sound now. Not bad, or should we keep looking?")
                scope.launch {
                    kotlinx.coroutines.delay(6000)
                    if (provider != 0 && Speaker.lastCloudError.isNotBlank()) {
                        voiceNote = "Fell back to the phone voice. ${Speaker.lastCloudError}"
                    }
                }
            }) { Text("Preview") }
            if (provider != 0) Text("If the voice service can't be reached, ${Prefs.name} falls back to the phone voice.", color = Muted, fontSize = 12.sp)

            if (pick.isNotBlank()) {
                AlertDialog(
                    onDismissRequest = { pick = "" },
                    confirmButton = { TextButton(onClick = { pick = "" }) { Text("Close") } },
                    title = { Text(if (pick == "engine") "Speech engine" else "Voices (tap to hear)") },
                    text = {
                        LazyColumn(Modifier.height(380.dp)) {
                            if (options.isEmpty()) item { Text("Nothing found.") }
                            items(options) { (id, label) ->
                                TextButton(onClick = {
                                    when (pick) {
                                        "voice" -> {
                                            Prefs.voiceName = id; chosen = id
                                            Speaker.applyVoice()
                                            Corin.announce("This is how I sound now.")
                                        }
                                        "engine" -> {
                                            Prefs.ttsEngine = id; Prefs.voiceName = ""; chosen = ""
                                            Speaker.restart()
                                            pick = ""
                                        }
                                        else -> {
                                            Prefs.elevenVoiceId = id; Prefs.elevenVoiceName = label; eleven = label
                                            Corin.announce("This is how I sound now.")
                                        }
                                    }
                                }) { Text(label) }
                            }
                        }
                    }
                )
            }
        }

        Section("Listening") {
            Text(listOf(
                "Off: never opens the microphone. Type instead.",
                "Tap to talk: listens only when you use the glasses gesture or the Talk button.",
                "Conversation window: also listens for your reply right after it speaks."
            )[mode.coerceIn(0, 2)], color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Off", "Tap", "Window").forEachIndexed { i, label ->
                    if (mode == i) Button(onClick = {}) { Text(label) }
                    else SoftButton(onClick = { mode = i; Prefs.listenMode = i }) { Text(label) }
                }
            }
            ToggleRow("Use the glasses microphone", "Off uses the phone's mic", Prefs.useGlassesMic) { Prefs.useGlassesMic = it }
            ToggleRow("Whisper back", "A quiet question gets a quiet answer", Prefs.whisperBack) { Prefs.whisperBack = it }
            Text("Speaking speed")
            Slider(value = rate, onValueChange = { rate = it; Prefs.speechRate = it.toInt() }, valueRange = 70f..160f)
            SoftButton(onClick = { Corin.micTest(ctx) }) { Text("Test voice and microphone") }
        }

        Section("Buddie earbuds") {
            Text("Buddie earbuds stream their always-on microphone to the phone, so ${Prefs.name} can hear you without a gesture. Anything named Buddie is picked up automatically.", color = Muted, fontSize = 13.sp)
            ToggleRow("Listen through Buddie", "Connect to the earbuds' audio stream when you put them in", Prefs.buddieListen) { Prefs.buddieListen = it }
            ToggleRow("Wake word", "Say \"${Prefs.name}\" and then what you want. For 30 seconds after it speaks, you can just answer.", Prefs.wakeWord) { Prefs.wakeWord = it }
            ToggleRow("Ambient context", "Also transcribe other people nearby so ${Prefs.name} knows what's going on. Off means only your own voice is ever transcribed.", Prefs.buddieAmbient) { Prefs.buddieAmbient = it }
            ToggleRow("Take notes on phone calls", "Off means call audio from the earbuds is never transcribed. Only turn this on where recording calls is allowed and the other person knows.", Prefs.callNotes) { Prefs.callNotes = it }
            val stats by BuddieStats.summary.collectAsState()
            Text("Diagnostics: $stats", color = Muted, fontSize = 12.sp)
            SoftButton(onClick = {
                try {
                    val f = java.io.File(ctx.cacheDir, "buddie_last.wav")
                    if (f.exists()) {
                        val mp = android.media.MediaPlayer()
                        mp.setDataSource(f.absolutePath)
                        mp.setOnCompletionListener { it.release() }
                        mp.prepare()
                        mp.start()
                    }
                } catch (_: Exception) {
                }
            }) { Text("Play what the earbuds last heard") }
            val model by Whisperer.status.collectAsState()
            val modelReady = remember(model) { Whisperer.downloaded(ctx) }
            Text(
                "Speech model: " + when {
                    modelReady -> "ready"
                    model.isBlank() -> "not downloaded yet (300 MB, one time)"
                    else -> model
                },
                color = if (modelReady) Muted else Gold, fontSize = 14.sp
            )
            if (!modelReady && !model.startsWith("downloading")) {
                SoftButton(onClick = { Whisperer.ensure(ctx, force = true) }) { Text("Download speech model now") }
            }
            Text("The earbuds send heavily compressed audio. ${Prefs.name} uses its own on-phone speech model to understand it, so nothing you say through them leaves the phone to be transcribed.", color = Muted, fontSize = 12.sp)
            ToggleRow("Experimental: lighter stream", "Asks the earbuds for one audio channel instead of two, which should use less of their battery. Turn it off again if they start disconnecting. Re-seat the buds after changing.", Prefs.buddieCompressedFirst) { Prefs.buddieCompressedFirst = it }
            ToggleRow("Experimental: faster link", "May fix choppy audio. Turn it off again if the earbuds start disconnecting. Re-seat the buds after changing.", Prefs.buddieFastLink) { Prefs.buddieFastLink = it }
            SoftButton(onClick = { BuddieLink.captureAndShare(ctx) }) { Text("Capture 8 seconds of earbud data") }
            val captureStatus by BuddieLink.captureStatus.collectAsState()
            if (captureStatus.isBlank()) {
                Text("Tap it and talk normally for 8 seconds. A zip file is saved to your Downloads folder. It contains raw microphone data from the earbuds for that stretch.", color = Muted, fontSize = 12.sp)
            } else {
                Text(captureStatus, color = Gold, fontSize = 14.sp)
                if (captureStatus.startsWith("Saved")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { BuddieLink.shareCapture(ctx) }) { Text("Share it") }
                        SoftButton(onClick = {
                            try {
                                ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {
                            }
                        }) { Text("Open Downloads") }
                    }
                }
            }
            Text("If the earbuds don't report when you're the one speaking, ${Prefs.name} checks all nearby speech for its name. That happens on the phone and nothing is kept unless Ambient context is on.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { BuddieLink.forget() }) { Text("Forget earbuds and search again next time") }
        }

        Section("Home Assistant") {
            PrefField("URL (e.g. your Tailscale address, port 8123)", Prefs.haUrl) { Prefs.haUrl = it.trim() }
            PrefField("Long-lived access token", Prefs.haToken, secret = true) { Prefs.haToken = it.trim() }
            ToggleRow("House alerts", "Tells you when something is left open or unlocked, and when people come and go", Prefs.homeAlerts) { Prefs.homeAlerts = it }
            PrefField("Alert after open for (minutes)", Prefs.homeOpenMinutes.toString()) { s -> s.toIntOrNull()?.let { Prefs.homeOpenMinutes = it.coerceIn(1, 240) } }
            PrefField("Don't announce comings and goings for", Prefs.homeIgnore) { Prefs.homeIgnore = it }
            ToggleRow("Use a house speaker when I'm not wearing anything", "Timers and alerts go to the speaker below", Prefs.houseSpeaker) { Prefs.houseSpeaker = it }
            PrefField("Speaker entity (e.g. media_player.office)", Prefs.haSpeaker) { Prefs.haSpeaker = it.trim() }
            PrefField("Speech service", Prefs.haTtsService) { Prefs.haTtsService = it.trim() }
            PrefField("Ask first (amber) when a command mentions", Prefs.amberWords) { Prefs.amberWords = it }
            PrefField("Always refuse (red) when a command mentions", Prefs.redWords) { Prefs.redWords = it }
        }

        Section("Obelisk and local models") {
            PrefField("Corin server URL on Obelisk (optional)", Prefs.obeliskUrl) { Prefs.obeliskUrl = it.trim() }
            Text("Sends every exchange to your Obsidian vault and lets recall search it. Server file is in the repo under obelisk/.", color = Muted, fontSize = 12.sp)
            PrefField("Ollama URL (optional)", Prefs.ollamaUrl) { Prefs.ollamaUrl = it.trim() }
            PrefField("Ollama model", Prefs.ollamaModel) { Prefs.ollamaModel = it.trim() }
            ToggleRow("Use the local model for small talk", "Unprompted remarks cost nothing; falls back to Claude if the node is unreachable", Prefs.localSmallTalk) { Prefs.localSmallTalk = it }
        }

        Section("App") {
            Text("Version ${BuildConfig.VERSION_NAME}")
            Text(Usage.summary(), color = Muted, fontSize = 13.sp)
            if (status.isNotBlank()) Text(status, color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(onClick = { scope.launch { Updater.check(true) } }) { Text("Check for updates") }
                SoftButton(onClick = { Store.clearHistory() }) { Text("Clear recent chat") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
