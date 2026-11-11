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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
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
import com.jondude.anzu.assist.Atlas
import com.jondude.anzu.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Follows the phone's light or dark setting. */
private var isDark by mutableStateOf(true)

// Atlas's palette: black, white and one orange. True black and white grounds, orange for anything alive or actionable.
// The names are historical: Gold is the accent, Cream the main text, Muted the secondary text.
private val Gold: Color get() = if (isDark) Color(0xFFFF6B1A) else Color(0xFFF25C05)
private val OnGold: Color get() = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
private val Cream: Color get() = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
private val Muted: Color get() = if (isDark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B)
private val Ground: Color get() = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
private val Panel: Color get() = if (isDark) Color(0xFF141414) else Color(0xFFF4F4F5)
private val Fill: Color get() = if (isDark) Color(0xFF232323) else Color(0xFFE6E6E8)
private val Hairline: Color get() = if (isDark) Color(0xFF2A2A2A) else Color(0xFFDDDDDD)

/** The display face (Bricolage Grotesque) for titles and Atlas's own words. */
private val Display = FontFamily(
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_medium, FontWeight.Medium)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B1A),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF9A9A9A),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF5A52)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFF25C05),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF4F4F5),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE6E6E8),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFC8C8CC),
    error = Color(0xFFC81E1E)
)

/** Text fields are soft filled wells with the label inside, no outline. */
@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    unfocusedContainerColor = Fill, focusedContainerColor = Fill,
    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
    unfocusedLabelColor = Muted, focusedLabelColor = Gold, cursorColor = Gold
)

/** A quiet secondary button: tinted fill, no outline. */
@Composable
fun SoftButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
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
    Manifest.permission.WRITE_CALENDAR,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.CALL_PHONE,
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
                "Atlas" to Icons.Filled.Home,
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
            Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                when (t) {
                    0 -> HomeScreen(resumes)
                    1 -> NotesScreen()
                    2 -> WhisperScreen()
                    else -> SettingsScreen()
                }
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

/** Settings is an index of pages; this tells each Section whether it is a row in the index or the open page. */
class SettingsNav(val page: String?, val open: (String) -> Unit)

val LocalSettingsNav = compositionLocalOf<SettingsNav?> { null }

private val settingsIndex: Map<String, Pair<ImageVector, String>> = mapOf(
    "Brain" to (Icons.Filled.Star to "API key, model, what it knows about you"),
    "Identity" to (Icons.Filled.Face to "Name and personality"),
    "Presence" to (Icons.Filled.Favorite to "When and how often it speaks up"),
    "Glasses and earbuds" to (Icons.Filled.Person to "How it behaves in each wearable"),
    "Voice" to (Icons.Filled.PlayArrow to "Phone voices, ElevenLabs, custom"),
    "Listening" to (Icons.Filled.Phone to "Microphone and talking back"),
    "Buddie earbuds" to (Icons.Filled.Build to "Wake word, stream, diagnostics"),
    "Home Assistant" to (Icons.Filled.Home to "Your house and what needs a spoken okay"),
    "Connections" to (Icons.Filled.Share to "Everything Atlas can reach and control"),
    "Hermes and your vault" to (Icons.Filled.Build to "Hand-offs to Hermes, Obsidian"),
    "Obelisk and local models" to (Icons.Filled.Lock to "Your own server and models"),
    "App" to (Icons.Filled.Info to "Version, updates, usage")
)

/** A titled group of related controls on one rounded panel. Inside Settings it doubles as a page. */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    val nav = LocalSettingsNav.current
    if (nav != null && nav.page == null) {
        val (icon, subtitle) = settingsIndex[title] ?: (Icons.Filled.Settings to "")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel)
                .clickable { nav.open(title) }.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Gold.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
        }
        return
    }
    if (nav != null && nav.page != title) return
    Column {
        if (nav == null) Text(
            title, fontFamily = Display, fontWeight = FontWeight.Medium, color = Cream, fontSize = 21.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp, top = 6.dp)
        )
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
            }
        }
    }
}

/** A label on the left and its current value on the right. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 15.sp, modifier = Modifier.width(132.dp))
        Text(value, color = Cream, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/** One thing still to set up, with the button that does it. */
@Composable
fun SetupRow(title: String, why: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(why, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Button(onClick = onClick) { Text(action) }
    }
}

/** A gentle placeholder for a list with nothing in it yet. */
@Composable
fun EmptyHint(text: String) {
    Text(text, color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
}

/** One line of today's conversation as a chat bubble. */
@Composable
fun Bubble(speaker: String, text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(20.dp))
                .background(if (mine) Gold.copy(alpha = 0.16f) else Panel).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(speaker, color = if (mine) Gold else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = Cream, fontSize = 15.sp, lineHeight = 21.sp)
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
    TextField(
        value = v,
        onValueChange = { v = it; onChange(it) },
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(16.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Atlas's presence: a glowing orange sphere that breathes, and quickens and brightens when listening or thinking. */
@Composable
fun Orb(state: String, diameter: Dp = 148.dp) {
    val period = when (state) {
        "listening" -> 700
        "thinking" -> 450
        "speaking" -> 1000
        else -> 3000
    }
    // White-hot where the light falls, orange through the body, ember-dark in the shadow.
    val light = when (state) {
        "listening", "speaking" -> Color(0xFFFFFFFF)
        else -> Color(0xFFFFE2C4)
    }
    val body = when (state) {
        "listening" -> Color(0xFFFF8A3D)
        "speaking" -> Color(0xFFFFA057)
        else -> Color(0xFFFF6B1A)
    }
    val deep = if (state == "idle") Color(0xFF5A1E00) else Color(0xFF7A2B00)
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
    val state by Glasses.atlasState.collectAsState()
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

    // A typed exchange shows its answer for a short while, then the screen goes quiet again.
    var replyUntil by remember { mutableStateOf(0L) }
    var devicesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(replyUntil) {
        if (replyUntil > 0) {
            kotlinx.coroutines.delay(90_000)
            replyUntil = 0
        }
    }

    fun send() {
        val t = typed.trim()
        if (t.isNotEmpty()) {
            Atlas.typed(ctx, t)
            typed = ""
            replyUntil = System.currentTimeMillis() + 90_000
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { LargeTitle(Prefs.name.uppercase()) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(state, 220.dp)
            // No running transcript here: one quiet status line, and a typed answer only while it's fresh.
            Text(
                when (state) {
                    "listening" -> "Listening"
                    "thinking" -> "Thinking"
                    "speaking" -> "Speaking"
                    else -> if (connected) "With you" else "Standing by"
                },
                fontFamily = Display, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Cream
            )
            if (replyUntil > 0 && said.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    said, color = Muted, fontSize = 16.sp, lineHeight = 23.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Modifier.weight(1.5f).height(118.dp).clip(RoundedCornerShape(26.dp)).clickable { devicesOpen = true }) {
                Text("Wearing", color = Muted, fontSize = 13.sp)
                Column {
                    Text(
                        when {
                            !connected -> "Nothing"
                            activeWear == "buddie" -> "Earbuds"
                            activeWear == "pixelbuds" -> "Pixel Buds"
                            else -> "Glasses"
                        },
                        fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Cream
                    )
                    Text(
                        when {
                            !connected -> "Put them on and I'm there"
                            activeWear == "buddie" -> "Private, hands-free"
                            activeWear == "pixelbuds" -> "Private"
                            else -> "Open-ear, discreet"
                        },
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
        TextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Type to ${Prefs.name}") },
            singleLine = true, // so Enter on a hardware or wrist keyboard sends
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Panel, focusedContainerColor = Panel,
                unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedLabelColor = Muted, focusedLabelColor = Gold, cursorColor = Gold
            ),
            trailingIcon = {
                IconButton(onClick = { send() }) { Icon(Icons.Filled.Send, contentDescription = "Send", tint = Gold) }
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() })
        )
        val quiet = quietUntil > System.currentTimeMillis()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { ctx.startActivity(Intent(ctx, AssistActivity::class.java)) },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("Talk", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            SoftButton(
                onClick = { GlassesService.start(ctx, true, GlassesService.ACTION_SPEAK_NOW) },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("Speak up", maxLines = 1) }
            SoftButton(
                onClick = {
                    Prefs.quietUntil = if (quiet) 0L else System.currentTimeMillis() + 60 * 60_000
                    quietUntil = Prefs.quietUntil
                },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text(if (quiet) "Resume" else "Quiet 1h", maxLines = 1) }
        }
        if (quiet) Text(
            "Staying quiet until ${SimpleDateFormat("h:mm a", Locale.US).format(Date(quietUntil))}.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp)
        )
        if (!awake && Prefs.apiKey.isNotBlank()) {
            Button(enabled = !waking, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                waking = true
                scope.launch {
                    val first = Brain.awaken()
                    waking = false
                    awake = Prefs.identity.isNotBlank()
                    if (first != null) Atlas.announce(first)
                }
            }) { Text(if (waking) "Coming online…" else "Wake ${Prefs.name} for the first time") }
        }

        val overlay = remember(resumes) { Settings.canDrawOverlays(ctx) }
        if (missing.isNotEmpty() || !listenerOn || !unrestricted || !overlay || Prefs.apiKey.isBlank()) {
            Section("Finish setting up") {
                if (Prefs.apiKey.isBlank()) Text("Add your Anthropic API key under Settings → Brain so ${Prefs.name} can think.", color = Cream, fontSize = 15.sp)
                if (missing.isNotEmpty()) SetupRow(
                    "Permissions", "Bluetooth, microphone, notifications, location, calendar, contacts, and texts and calls on your say-so.", "Grant"
                ) { launcher.launch(REQUESTED) }
                if (!listenerOn) SetupRow("Notification access", "So ${Prefs.name} can read messages to you.", "Allow") {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                if (!unrestricted) SetupRow("Background running", "Keeps ${Prefs.name} talking while the phone is in your pocket.", "Allow") {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (_: Exception) {
                    }
                }
                if (!overlay) SetupRow("Opening apps", "Lets ${Prefs.name} open apps, start navigation and set alarms from your pocket.", "Allow") {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } catch (_: Exception) {
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (devicesOpen) {
        AlertDialog(
            onDismissRequest = { devicesOpen = false },
            containerColor = Panel, titleContentColor = Cream, textContentColor = Cream,
            confirmButton = { TextButton(onClick = { devicesOpen = false }) { Text("Done") } },
            title = { Text("Devices", fontFamily = Display, fontWeight = FontWeight.Medium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (meeting) Text("Taking meeting notes", color = Gold, fontWeight = FontWeight.SemiBold)
            InfoRow("Glasses", deviceName.ifBlank { "Not chosen yet. Anything named Anzu is picked up." })
            if (connected) InfoRow("Worn", "${Glasses.wornMinutes()} min" + (if (activeWear.isNotBlank()) " · ${Wear.label()}" else ""))
            if (buddie != "off") InfoRow("Earbud stream", buddie)
            InfoRow("Reply microphone", if (armed) "Ready" else "Open this app once after a restart")
            InfoRow("Last seen", timeAgo(Prefs.lastSeenTime))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(onClick = { picking = true }) { Text("Choose glasses") }
                SoftButton(onClick = {
                    try {
                        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                            .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000)
                    } catch (_: Exception) {
                    }
                }) { Text("Ping") }
                if (Prefs.lastSeenLat.isNotBlank()) {
                    SoftButton(onClick = {
                        val q = "${Prefs.lastSeenLat},${Prefs.lastSeenLon}"
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q(Glasses)")))
                        } catch (_: Exception) {
                        }
                    }) { Text("Find") }
                }
            }
                }
            }
        )
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
            containerColor = Panel, titleContentColor = Cream, textContentColor = Cream,
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            title = { Text("Which are your glasses?", fontFamily = Display, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired devices found. Pair the glasses in Bluetooth settings first.")
                    devices.forEach { (name, address) ->
                        Text(
                            name, color = Cream, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                                Prefs.deviceName = name
                                Prefs.deviceAddress = address
                                deviceName = name
                                picking = false
                            }.padding(vertical = 12.dp, horizontal = 8.dp)
                        )
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LargeTitle("Notes")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft, onValueChange = { draft = it },
                label = { Text("New note") }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Panel, focusedContainerColor = Panel,
                    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                    unfocusedLabelColor = Muted, focusedLabelColor = Gold, cursorColor = Gold
                )
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { Store.addNote(draft); draft = "" }, modifier = Modifier.height(52.dp)) { Text("Add") }
        }
        if (notes.isEmpty()) {
            EmptyHint("No notes yet. Type one above, or say “note that…” and it lands here.")
        }
        notes.forEachIndexed { i, n ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(n.text, color = Cream, fontSize = 16.sp, lineHeight = 22.sp)
                    Text(timeAgo(n.time), color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { Store.removeNote(i) }) { Icon(Icons.Filled.Close, contentDescription = "Delete note", tint = Muted) }
            }
        }

        Section("Checklists") {
            if (lists.isEmpty()) EmptyHint("Say “add milk to my grocery list” and then “what's my next step?”")
            lists.forEach { (name, listItems) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        TextButton(onClick = { Store.listClear(name) }) { Text("Remove") }
                    }
                    listItems.forEachIndexed { i, item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Store.listToggle(name, i) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(if (item.done) Gold else Fill),
                                contentAlignment = Alignment.Center
                            ) { if (item.done) Icon(Icons.Filled.Check, contentDescription = null, tint = OnGold, modifier = Modifier.size(14.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Text(item.text, color = if (item.done) Muted else Cream, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        Section("What ${Prefs.name} remembers") {
            if (memory.isEmpty()) EmptyHint("Nothing yet. It builds up as you talk.")
            memory.forEachIndexed { i, fact ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fact, Modifier.weight(1f), color = Cream, fontSize = 15.sp, lineHeight = 21.sp)
                    TextButton(onClick = { Store.removeMemory(i) }) { Text("Forget") }
                }
            }
        }

        Section("Today's conversation") {
            val line = remember { Regex("^- (\\d\\d:\\d\\d) \\*\\*(.+?)\\*\\*: (.*)$") }
            val today = remember(notes, actions) {
                Store.todayLog().lines().mapNotNull { line.find(it) }
                    .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                    .filter { it.second == "Jon" || it.second == Prefs.name }
                    .takeLast(12)
            }
            if (today.isEmpty()) EmptyHint("Nothing said yet today.")
            today.forEach { (time, who, text) -> Bubble("$who · $time", text, who == "Jon") }
        }

        Section("Done on its own") {
            if (actions.isEmpty()) EmptyHint("Nothing yet. Anything ${Prefs.name} does without being asked shows up here.")
            actions.take(30).forEach { a ->
                Column {
                    Text(a.text, color = Cream, fontSize = 15.sp, lineHeight = 21.sp)
                    Text(timeAgo(a.time), color = Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private class AppRow(val label: String, val pkg: String, val icon: ImageBitmap?)

@Composable
fun WhisperScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var allowed by remember { mutableStateOf(Prefs.allowedApps) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            try {
                val pm = ctx.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }
                    .map {
                        val icon = try {
                            it.loadIcon(pm).toBitmap(72, 72).asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                        AppRow(it.loadLabel(pm).toString(), it.activityInfo.packageName, icon)
                    }
                    .sortedWith(compareByDescending<AppRow> { it.pkg in allowed }.thenBy { it.label.lowercase() })
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            LargeTitle("Whisper")
            Spacer(Modifier.height(6.dp))
            Section("Reading to you") {
                ToggleRow("Read notifications to me", "Only while you're wearing something", Prefs.readNotifications) { Prefs.readNotifications = it }
                ToggleRow("Triage", "Messages, calls and reminders right away. Everything else waits until you ask what you missed.", Prefs.triage) { Prefs.triage = it }
            }
            Text(
                "Apps allowed to reach your ears", fontFamily = Display, fontWeight = FontWeight.Medium, color = Cream,
                fontSize = 21.sp, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 4.dp)
            )
        }
        items(apps, key = { it.pkg }) { app ->
            val on = app.pkg in allowed
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                else Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Fill))
                Text(app.label, Modifier.weight(1f).padding(horizontal = 14.dp), color = Cream, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Switch(
                    checked = on,
                    onCheckedChange = { v ->
                        allowed = if (v) allowed + app.pkg else allowed - app.pkg
                        Prefs.allowedApps = allowed
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnGold, checkedTrackColor = Gold,
                        uncheckedThumbColor = Muted, uncheckedTrackColor = Fill, uncheckedBorderColor = Color.Transparent
                    )
                )
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
    var page by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    BackHandler(enabled = page != null) { page = null }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (page == null) 8.dp else 16.dp)
    ) {
        val open = page
        if (open == null) {
            LargeTitle("Settings")
            Spacer(Modifier.height(4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                IconButton(onClick = { page = null }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Cream) }
                Text(open, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Cream)
            }
        }
        CompositionLocalProvider(LocalSettingsNav provides SettingsNav(page) { page = it }) {
        Section("Brain") {
            PrefField("Anthropic API key", Prefs.apiKey, secret = true) { Prefs.apiKey = it.trim() }
            PrefField("Model", Prefs.model) { Prefs.model = it.trim() }
            PrefField("About you (things it should know from day one)", Prefs.aboutMe, lines = 3) { Prefs.aboutMe = it }
        }

        Section("Identity") {
            PrefField("Name", Prefs.name) { Prefs.name = it.trim().ifBlank { "Atlas" } }
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
                Atlas.announce("This is how I sound now. Not bad, or should we keep looking?")
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
                    containerColor = Panel, titleContentColor = Cream, textContentColor = Cream,
                    confirmButton = { TextButton(onClick = { pick = "" }) { Text("Close") } },
                    title = { Text(if (pick == "engine") "Speech engine" else "Voices (tap to hear)", fontFamily = Display, fontWeight = FontWeight.Medium) },
                    text = {
                        LazyColumn(Modifier.height(380.dp)) {
                            if (options.isEmpty()) item { Text("Nothing found.") }
                            items(options) { (id, label) ->
                                Text(
                                    label, color = Cream, fontSize = 16.sp,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                                    when (pick) {
                                        "voice" -> {
                                            Prefs.voiceName = id; chosen = id
                                            Speaker.applyVoice()
                                            Atlas.announce("This is how I sound now.")
                                        }
                                        "engine" -> {
                                            Prefs.ttsEngine = id; Prefs.voiceName = ""; chosen = ""
                                            Speaker.restart()
                                            pick = ""
                                        }
                                        else -> {
                                            Prefs.elevenVoiceId = id; Prefs.elevenVoiceName = label; eleven = label
                                            Atlas.announce("This is how I sound now.")
                                        }
                                    }
                                    }.padding(vertical = 12.dp, horizontal = 8.dp)
                                )
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
            Text("From the glasses, use the Anzu voice-assistant gesture and pick ${Prefs.name} when Android asks which app to use.", color = Muted, fontSize = 13.sp)
            ToggleRow("Use the glasses microphone", "Off uses the phone's mic", Prefs.useGlassesMic) { Prefs.useGlassesMic = it }
            ToggleRow("Whisper back", "A quiet question gets a quiet answer", Prefs.whisperBack) { Prefs.whisperBack = it }
            Text("Speaking speed")
            Slider(value = rate, onValueChange = { rate = it; Prefs.speechRate = it.toInt() }, valueRange = 70f..160f)
            SoftButton(onClick = { Atlas.micTest(ctx) }) { Text("Test voice and microphone") }
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

        Section("Connections") {
            val mcpStatus by com.jondude.anzu.assist.Mcp.status.collectAsState()
            var servers by remember { mutableStateOf(com.jondude.anzu.assist.Mcp.servers()) }
            var newName by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            var newToken by remember { mutableStateOf("") }
            fun reload() { servers = com.jondude.anzu.assist.Mcp.servers() }
            fun check(s: com.jondude.anzu.assist.McpServer) {
                scope.launch(Dispatchers.IO) { com.jondude.anzu.assist.Mcp.discover(s) }
            }
            LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.jondude.anzu.assist.Mcp.refresh(force = true) } }
            Text(
                "Anything that speaks MCP can be connected here, and its tools become things ${Prefs.name} can do: your house, your desktop, your servers, your other apps. Home Assistant has it built in.",
                color = Muted, fontSize = 13.sp
            )
            if (servers.isEmpty()) EmptyHint("Nothing connected yet.")
            servers.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(s.name, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (!s.enabled) "Off" else mcpStatus[s.name] ?: "Not checked yet",
                            color = if ((mcpStatus[s.name] ?: "").startsWith("unreachable")) MaterialTheme.colorScheme.error else Muted,
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { on ->
                            com.jondude.anzu.assist.Mcp.setEnabled(s.name, on); reload()
                            if (on) check(s.copy(enabled = true))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnGold, checkedTrackColor = Gold,
                            uncheckedThumbColor = Muted, uncheckedTrackColor = Fill, uncheckedBorderColor = Color.Transparent
                        )
                    )
                    IconButton(onClick = { com.jondude.anzu.assist.Mcp.remove(s.name); reload() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${s.name}", tint = Muted)
                    }
                }
            }
            if (Prefs.haUrl.isNotBlank() && servers.none { it.url.contains("/api/mcp") }) {
                SoftButton(onClick = {
                    val s = com.jondude.anzu.assist.McpServer("Home Assistant", Prefs.haUrl.trim().trimEnd('/') + "/api/mcp", Prefs.haToken)
                    com.jondude.anzu.assist.Mcp.upsert(s); reload(); check(s)
                }) { Text("Connect Home Assistant") }
            }
            Text("Add a connection", color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            PrefField("Name (for example Athena files)", "") { newName = it }
            PrefField("Address (for example http://athena:8000/mcp)", "") { newUrl = it.trim() }
            PrefField("Token, if it needs one", "", secret = true) { newToken = it.trim() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = newName.isNotBlank() && newUrl.startsWith("http"), onClick = {
                    val s = com.jondude.anzu.assist.McpServer(newName.trim(), newUrl, newToken)
                    com.jondude.anzu.assist.Mcp.upsert(s); reload(); check(s)
                }) { Text("Connect") }
                SoftButton(onClick = { scope.launch(Dispatchers.IO) { com.jondude.anzu.assist.Mcp.refresh(force = true) } }) { Text("Check all") }
            }
            Text("Changes to the add form take effect when you tap Connect. Addresses can be Tailscale names, since the phone is on your tailnet.", color = Muted, fontSize = 12.sp)
        }

        Section("Hermes and your vault") {
            Text("${Prefs.name} hands bigger, tool-heavy jobs to Hermes through the hub on Obelisk, then reports back. A direct Hermes address is only needed if you're not using the hub.", color = Muted, fontSize = 13.sp)
            PrefField("Hermes API address (ends in /v1)", Prefs.hermesUrl) { Prefs.hermesUrl = it.trim() }
            PrefField("Hermes API key (if it needs one)", Prefs.hermesKey, secret = true) { Prefs.hermesKey = it.trim() }
            PrefField("Model name to send", Prefs.hermesModel) { Prefs.hermesModel = it.trim() }
            var vault by remember { mutableStateOf(Prefs.vaultUri) }
            val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    Prefs.vaultUri = uri.toString()
                    vault = Prefs.vaultUri
                }
            }
            Text(
                if (vault.isBlank()) "Obsidian vault: none chosen. Pick the vault folder on this phone and ${Prefs.name} can search it, read notes, and keep each day's conversation in a Atlas folder there."
                else "Obsidian vault: " + Uri.decode(vault).substringAfterLast(':'),
                color = Muted, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(onClick = { pickVault.launch(null) }) { Text(if (vault.isBlank()) "Choose vault folder" else "Change folder") }
                if (vault.isNotBlank()) SoftButton(onClick = { Prefs.vaultUri = ""; vault = "" }) { Text("Disconnect") }
            }
        }

        Section("Obelisk and local models") {
            PrefField("Hub on Obelisk (built in)", Prefs.obeliskUrl) { Prefs.obeliskUrl = it.trim().ifBlank { OBELISK_HUB } }
            PrefField("Hub token, if you set one", Prefs.hubToken, secret = true) { Prefs.hubToken = it.trim() }
            Text("Your apps report to the hub, its local model digests everything for free, and ${Prefs.name} only reads a short brief. It also logs every exchange to your vault. The server is in the repo under obelisk/.", color = Muted, fontSize = 12.sp)
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
        }
        Spacer(Modifier.height(24.dp))
    }
}
