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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
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
private val Gold: Color get() = if (com.jondude.anzu.assist.Discretion.grayscale()) Color(0xFF9A9A9A) else Color(0xFFFF5C00)
private val OnGold: Color get() = Color(0xFF000000)
private val Cream: Color get() = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
private val Muted: Color get() = if (isDark) Color(0xFF888888) else Color(0xFF777777)
private val Ground: Color get() = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
private val Panel: Color get() = if (isDark) Color(0xFF111111) else Color(0xFFF5F5F5)
private val Fill: Color get() = if (isDark) Color(0xFF111111) else Color(0xFFF5F5F5)
private val Hairline: Color get() = if (isDark) Color(0xFF222222) else Color(0xFFE5E5E5)

/** Square corners everywhere: flat, flush, nothing floats. */
private val Chamfer = androidx.compose.ui.graphics.RectangleShape
private val Flat = androidx.compose.ui.graphics.RectangleShape

/** Sora: the companion's voice and the whole UI. */
private val Display = FontFamily(
    Font(R.font.sora_light, FontWeight.Light),
    Font(R.font.sora_regular, FontWeight.Normal),
    Font(R.font.sora_regular, FontWeight.Medium),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_semibold, FontWeight.Bold)
)
/** Monospace lives only in raw data: command output and logs. */
private val Mono = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))

private val ConsoleType = androidx.compose.material3.Typography().let { t ->
    androidx.compose.material3.Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Display), displayMedium = t.displayMedium.copy(fontFamily = Display),
        displaySmall = t.displaySmall.copy(fontFamily = Display), headlineLarge = t.headlineLarge.copy(fontFamily = Display),
        headlineMedium = t.headlineMedium.copy(fontFamily = Display), headlineSmall = t.headlineSmall.copy(fontFamily = Display),
        titleLarge = t.titleLarge.copy(fontFamily = Display), titleMedium = t.titleMedium.copy(fontFamily = Display),
        titleSmall = t.titleSmall.copy(fontFamily = Display), bodyLarge = t.bodyLarge.copy(fontFamily = Display),
        bodyMedium = t.bodyMedium.copy(fontFamily = Display), bodySmall = t.bodySmall.copy(fontFamily = Display),
        labelLarge = t.labelLarge.copy(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 15.sp),
        labelMedium = t.labelMedium.copy(fontFamily = Display), labelSmall = t.labelSmall.copy(fontFamily = Display)
    )
}
private val ConsoleShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp), medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp), extraLarge = RoundedCornerShape(0.dp)
)

/** Every button in the app: chamfered, never a pill. */
@Composable
private fun Button(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
    elevation: androidx.compose.material3.ButtonElevation? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = Chamfer, colors = colors, elevation = elevation,
    contentPadding = contentPadding, content = content
)

/** A hardware toggle: an empty square when off, filled orange with a black centre when on. */
@Composable
private fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, colors: androidx.compose.material3.SwitchColors? = null) {
    Box(
        Modifier.size(48.dp).clickable(role = androidx.compose.ui.semantics.Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(24.dp).background(if (checked) Gold else Color.Transparent)
                .border(2.dp, if (checked) Gold else Muted)
        )
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5C00),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF888888),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF888888),
    outline = Color(0xFF888888),
    error = Color(0xFFFF5A52)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFFF5C00),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF777777),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF777777),
    outline = Color(0xFF777777),
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
        colors = ButtonDefaults.buttonColors(containerColor = Fill, contentColor = Cream),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            MaterialTheme(colorScheme = if (isDark) DarkColors else LightColors, typography = ConsoleType, shapes = ConsoleShapes) {
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
            // A flat bar: a short orange line sits above the tab you are on.
            val items = listOf(
                "Atlas" to Icons.Outlined.Home,
                "Notes" to Icons.Outlined.Create,
                "Whisper" to Icons.Outlined.Notifications,
                "Settings" to Icons.Outlined.Settings
            )
            Row(Modifier.fillMaxWidth().background(Ground).navigationBarsPadding().height(56.dp)) {
                items.forEachIndexed { i, (label, icon) ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { tab = i },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) Gold else Color.Transparent))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = label, tint = if (tab == i) Cream else Muted, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // A gated action waiting for one tap.
            val ask by com.jondude.anzu.assist.Approvals.pending.collectAsState()
            ask?.let { a ->
                Column(
                    Modifier.fillMaxWidth().background(Panel).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("${Prefs.name} is asking", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light)
                    Text(a.question, color = Cream, fontSize = 18.sp, lineHeight = 26.sp)
                    val nod by com.jondude.anzu.assist.Approvals.nodAvailable.collectAsState()
                    if (nod) Text("Nod to approve, shake your head to decline.", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { com.jondude.anzu.assist.Approvals.answer(a.id, true) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = OnGold)
                        ) { Text("Approve") }
                        SoftButton(onClick = { com.jondude.anzu.assist.Approvals.answer(a.id, false) }, Modifier.weight(1f).height(48.dp)) {
                            Text("Deny")
                        }
                    }
                }
            }
            val rel = update
            if (rel != null && !dismissed) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = Flat,
                    colors = CardDefaults.cardColors(containerColor = Panel)
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
        text, fontFamily = Display, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Cream,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/** Settings is an index of pages; this tells each Section whether it is a row in the index or the open page. */
class SettingsNav(val page: String?, val open: (String) -> Unit)

val LocalSettingsNav = compositionLocalOf<SettingsNav?> { null }

private val settingsIndex: Map<String, Pair<ImageVector, String>> = mapOf(
    "Brain" to (Icons.Filled.Star to "API key, model, what it knows about you"),
    "Identity" to (Icons.Filled.Face to "Name and personality"),
    "Presence" to (Icons.Filled.Favorite to "When and how often it speaks up"),
    "Earbuds" to (Icons.Filled.Person to "Volume, mic sensitivity, how often it speaks"),
    "Voice" to (Icons.Filled.PlayArrow to "Phone voices, ElevenLabs, custom"),
    "Listening" to (Icons.Filled.Phone to "Microphone and talking back"),
    "Home Assistant" to (Icons.Filled.Home to "Your house and what needs a spoken okay"),
    "Connections" to (Icons.Filled.Share to "Everything Atlas can reach and control"),
    "Athena" to (Icons.Filled.Build to "Containers on Athena, and a command line to it"),
    "Systems" to (Icons.Filled.Share to "Every service on Obelisk, Athena and the house, checked live"),
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
        Column(Modifier.fillMaxWidth().clickable { nav.open(title) }) {
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Cream, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(start = 20.dp, end = 12.dp)) {
                    Text(title, color = Cream, fontSize = 16.sp)
                    if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
            }
        }
        return
    }
    if (nav != null && nav.page != title) return
    Column {
        if (nav == null) Text(
            title, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)
        )
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
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
            Modifier.fillMaxWidth(0.86f).clip(Flat)
                .background(if (mine) Fill else Panel).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(speaker, color = if (mine) Gold else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = Cream, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/** "Good evening." by the hour. */
fun greeting(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): String = when (hour) {
    in 5..11 -> "Good morning."
    in 12..16 -> "Good afternoon."
    in 17..21 -> "Good evening."
    else -> "Late night."
}

fun firstSentence(text: String): String {
    val t = text.trim().replace(Regex("\\s+"), " ")
    val end = Regex("[.!?](\\s|$)").find(t)?.range?.first
    return if (end != null) t.substring(0, end + 1) else t.take(120)
}

/** What ATLAS knows right now, in one or two plain sentences. Only real readings; unknowns are left out. */
fun knowsLine(
    connected: Boolean, wear: String?, battery: Int, haUp: Boolean?, hubUp: Boolean?,
    athena: com.jondude.anzu.assist.Hub.AthenaStatus?, brief: String,
    needsToken: Boolean = false,
    day: String = SimpleDateFormat("EEEE", Locale.US).format(Date())
): String = buildString {
    append("It's $day.")
    if (connected) {
        append(
            when (wear) {
                "buddie" -> " Your earbuds are on"
                "pixelbuds" -> " Your Pixel Buds are on"
                "galaxybuds" -> " Your Galaxy Buds are on"
                else -> " Your glasses are on"
            }
        )
        append(if (battery >= 0) " ($battery%)." else ".")
    }
    when (haUp) { true -> append(" The house is connected."); false -> append(" I can't reach the house."); else -> {} }
    if (athena != null) {
        val down = athena.containers.count { !it.up }
        append(if (down == 0) " Athena's running all ${athena.containers.size} services." else " Athena has $down service${if (down == 1) "" else "s"} down.")
    } else if (hubUp == false) append(" Obelisk isn't answering.")
    if (brief.isNotBlank()) append(" ").append(brief)
    if (needsToken) append(" The hub needs its token: Settings, Obelisk.")
}

private fun reachable(url: String): Boolean = try {
    val c = java.net.URL(tailnet(url)).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 3000; c.readTimeout = 3000
    val ok = c.responseCode > 0
    c.disconnect(); ok
} catch (e: Exception) { false }

/** One cell of the home screen's grid. */
@Composable
fun Tile(modifier: Modifier, tint: Color = Panel, content: @Composable () -> Unit) {
    Column(
        modifier.clip(Flat).background(tint).padding(16.dp),
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
        shape = Flat,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * ATLAS's presence: one flat 2dp ring. Idle, it breathes in grey. Listening, it ripples outward in orange.
 * Thinking, an orange sweep turns around it. Speaking, it swells and settles like a voice.
 */
@Composable
fun Orb(state: String, diameter: Dp = 240.dp) {
    val orange = Color(0xFFFF5C00)
    val grey = Muted
    val t = rememberInfiniteTransition(label = "ring")
    val breath by t.animateFloat(0.2f, 0.6f, infiniteRepeatable(tween(4000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse), label = "breath")
    val ripple by t.animateFloat(1f, 1.15f, infiniteRepeatable(tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "ripple")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "turn")
    val voice by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "voice")
    // A speech-like envelope: a few overlapping syllables, eased by a spring.
    val tau = (2 * Math.PI).toFloat()
    val syllables = (0.5f + 0.5f * kotlin.math.sin(tau * voice * 5)) * (0.6f + 0.4f * kotlin.math.sin(tau * voice * 2 + 1f))
    val swell by androidx.compose.animation.core.animateFloatAsState(
        if (state == "speaking") 1f + 0.25f * syllables.coerceIn(0f, 1f) else 1f,
        androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMedium), label = "swell"
    )
    Canvas(Modifier.size(diameter)) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension / 2 / 1.25f - 2.dp.toPx()
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        when (state) {
            "listening" -> drawCircle(orange, base * ripple, c, style = stroke)
            "thinking" -> rotate(turn, c) {
                drawCircle(
                    androidx.compose.ui.graphics.Brush.sweepGradient(listOf(orange.copy(alpha = 0f), orange), c),
                    base, c, style = stroke
                )
            }
            "speaking" -> drawCircle(orange, base * swell, c, style = stroke)
            else -> drawCircle(grey.copy(alpha = breath), base, c, style = stroke)
        }
    }
}

/** Open loops from the hub: oldest first, tap Done to close; a peek at what's been closed. */
@Composable
fun LoopsPanel() {
    val scope = rememberCoroutineScope()
    var loops by remember { mutableStateOf<List<com.jondude.anzu.assist.Hub.Loop>?>(null) }
    var closed by remember { mutableStateOf(false) }
    fun refresh() = scope.launch {
        loops = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jondude.anzu.assist.Hub.openLoops(closed) }
    }
    LaunchedEffect(closed) { refresh() }
    val l = loops
    Section("Open loops") {
        when {
            l == null -> Text(
                if (com.jondude.anzu.assist.Hub.needsToken) "The hub needs its token (Settings, Obelisk) to show your loops." else "Checking with the hub…",
                color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light
            )
            l.isEmpty() -> Text(if (closed) "Nothing closed yet." else "Nothing open. Say \"${Prefs.name}, todo …\" to add one.", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light)
            else -> l.forEach { loop ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(loop.text, color = if (loop.ackedAt > 0) Muted else Cream, fontSize = 16.sp, lineHeight = 22.sp)
                        Text(
                            timeAgo(loop.createdAt) + (if (loop.spokenCount > 0) " · mentioned ${loop.spokenCount}×" else "") + (if (loop.ackedAt > 0) " · done" else ""),
                            color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Light
                        )
                    }
                    if (loop.ackedAt == 0L) Text("Done", color = Gold, fontSize = 14.sp, modifier = Modifier.clickable {
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jondude.anzu.assist.Hub.ackLoops(listOf(loop.id)) }
                            refresh()
                        }
                    }.padding(8.dp))
                }
            }
        }
        Text(if (closed) "Show open" else "Show closed", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { closed = !closed })
    }
}

/** Every built-in service: a sentence first, then each one with an Open link. */
@Composable
fun SystemsPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<com.jondude.anzu.assist.Systems.Check>?>(null) }
    fun refresh() = scope.launch { checks = com.jondude.anzu.assist.Systems.check() }
    LaunchedEffect(Unit) { refresh() }
    val c = checks
    Text(
        if (c == null) "Checking your systems…" else com.jondude.anzu.assist.Systems.sentence(c),
        color = Cream, fontSize = 18.sp, lineHeight = 26.sp
    )
    SoftButton(onClick = { checks = null; refresh() }) { Text("Check again") }
    c?.groupBy { it.service.machine }?.forEach { (machine, list) ->
        Text(machine, color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 8.dp))
        list.forEach { ch ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ch.service.url))) } catch (_: Exception) {}
                }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ch.up) Gold else MaterialTheme.colorScheme.error))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(ch.service.name, color = Cream, fontSize = 16.sp)
                    Text(
                        if (ch.up) ch.service.what + (if (ch.code == 401) " · needs a key to use" else "") else "not answering",
                        color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light
                    )
                }
                Text("Open", color = Muted, fontSize = 14.sp)
            }
        }
    }
}

/** Athena in words first; the containers and a command line in monospace one tap down. */
@Composable
fun AthenaPanel() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<com.jondude.anzu.assist.Hub.AthenaStatus?>(null) }
    var raw by remember { mutableStateOf(false) }
    var cmd by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    fun refresh() = scope.launch {
        status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jondude.anzu.assist.Hub.athenaStatus() }
    }
    LaunchedEffect(Unit) { refresh() }
    val st = status
    Text(
        when {
            st == null -> "Checking on Athena…"
            st.error != null -> st.error
            else -> athenaSentence(st)
        },
        color = Cream, fontSize = 18.sp, lineHeight = 26.sp
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton(onClick = { raw = !raw }) { Text(if (raw) "Hide raw telemetry" else "View raw telemetry") }
        SoftButton(onClick = { status = null; refresh() }) { Text("Refresh") }
    }
    if (!raw) return
    val mono = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Cream)
    if (st != null && st.error == null) {
        Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
            if (st.uptime.isNotBlank()) Text("uptime  ${st.uptime}", style = mono.copy(color = Muted))
            if (st.disk.isNotBlank()) Text("disk    ${st.disk}", style = mono.copy(color = Muted))
            st.containers.groupBy { it.stack }.toSortedMap().forEach { (stack, items) ->
                Text("\n$stack", style = mono.copy(color = Gold))
                items.forEach { c ->
                    Text(
                        "  ${c.name.padEnd(18).take(18)} ${if (c.up) c.ports.substringBefore(',') else c.status}",
                        style = mono.copy(color = if (c.up) Cream else MaterialTheme.colorScheme.error), maxLines = 1
                    )
                }
            }
        }
    }
    TextField(
        value = cmd, onValueChange = { cmd = it },
        placeholder = { Text("Command for Athena", color = Muted) },
        textStyle = mono.copy(fontSize = 14.sp),
        singleLine = true, shape = Flat,
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = Panel, focusedContainerColor = Panel,
            unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, cursorColor = Gold
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = cmd.isNotBlank() && !running, onClick = {
            val c = cmd.trim(); running = true; output = "$ $c\n…"
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jondude.anzu.assist.Hub.athenaRun(c) }
                output = "$ $c\n" + r.out.take(6000) + (if (r.exit > 0) "\n[exit ${r.exit}]" else "")
                running = false
            }
        }) { Text("Run") }
        listOf("docker ps", "uptime").forEach { q -> SoftButton(onClick = { cmd = q }) { Text(q) } }
    }
    if (output.isNotBlank()) Text(
        output, style = mono,
        modifier = Modifier.fillMaxWidth().background(Panel).padding(12.dp).horizontalScroll(rememberScrollState())
    )
}

/** Athena's state as ATLAS would say it. */
fun athenaSentence(st: com.jondude.anzu.assist.Hub.AthenaStatus): String {
    val down = st.containers.filter { !it.up }
    val n = st.containers.size
    return when {
        n == 0 -> "Athena answered, but reported no services."
        down.isEmpty() -> "Athena's fine. All $n services are up."
        down.size == 1 -> "Athena needs a look: ${down[0].name} is down. The other ${n - 1} are up."
        else -> "Athena needs a look: ${down.size} of $n services are down (${down.take(3).joinToString { it.name }}${if (down.size > 3) "…" else ""})."
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
    val inputSource by Glasses.inputSource.collectAsState()
    val wake by WakeListener.state.collectAsState()
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

    // Real links only: the hub on Obelisk and Home Assistant, checked once a minute while this screen is up.
    var hubUp by remember { mutableStateOf<Boolean?>(null) }
    var haUp by remember { mutableStateOf<Boolean?>(null) }
    var athena by remember { mutableStateOf<com.jondude.anzu.assist.Hub.AthenaStatus?>(null) }
    var briefLine by remember { mutableStateOf("") }
    var tokenMissing by remember { mutableStateOf(false) }
    LaunchedEffect(resumes) {
        while (true) {
            hubUp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                reachable(Prefs.obeliskUrl.trim().trimEnd('/') + "/atlas/status")
            }
            haUp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reachable(Prefs.haUrl.trim()) }
            if (hubUp == true) {
                athena = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jondude.anzu.assist.Hub.athenaStatus() }
                    .takeIf { it.error == null }
                briefLine = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jondude.anzu.assist.Hub.brief() }
                    ?.text?.let { firstSentence(it) } ?: ""
                tokenMissing = com.jondude.anzu.assist.Hub.needsToken
            }
            kotlinx.coroutines.delay(60_000)
        }
    }
    val knows = knowsLine(connected, activeWear, battery, haUp, hubUp, athena, briefLine, tokenMissing)

    fun send() {
        val t = typed.trim()
        if (t.isNotEmpty()) {
            Atlas.typed(ctx, t)
            typed = ""
            replyUntil = System.currentTimeMillis() + 90_000
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewport = maxHeight
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = viewport)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // A presence, not a dashboard: a greeting that knows the hour, what ATLAS knows in a sentence,
        // the ring, the last thing it said, then the message box and three actions.
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(greeting(), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Cream)
            Text(knows, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = 0.18.sp, color = Muted)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.clip(CircleShape).clickable(onClickLabel = "Talk") { ctx.startActivity(Intent(ctx, AssistActivity::class.java)) }
            ) { Orb(state, 240.dp) }
            val line = when {
                state == "listening" -> "Listening" + inputSource.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                state == "thinking" -> "Thinking"
                said.isNotBlank() -> "“${said.take(160)}${if (said.length > 160) "…" else ""}”"
                wake.startsWith("listening") -> "Listening for “${Prefs.name}”" + (wake.substringAfter("·", "").takeIf { it.isNotBlank() }?.let { " ·$it" } ?: "")
                else -> "Standing by"
            }
            Text(
                line, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.28.sp, fontWeight = FontWeight.Light,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        Column {
        TextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = { Text("Message ${Prefs.name}...", color = Muted) },
            singleLine = true, // so Enter on a hardware or wrist keyboard sends
            shape = Flat,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Panel, focusedContainerColor = Panel,
                unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, cursorColor = Gold
            ),
            trailingIcon = {
                IconButton(onClick = { send() }) { Icon(Icons.Filled.Send, contentDescription = "Send", tint = Gold) }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() })
        )
        Spacer(Modifier.height(16.dp))
        val quiet = quietUntil > System.currentTimeMillis()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { ctx.startActivity(Intent(ctx, AssistActivity::class.java)) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = OnGold)
            ) { Text("Talk", maxLines = 1) }
            SoftButton(onClick = { GlassesService.start(ctx, true, GlassesService.ACTION_SPEAK_NOW) }, Modifier.weight(1f).height(48.dp)) {
                Text("Speak up", maxLines = 1)
            }
            SoftButton(onClick = {
                Prefs.quietUntil = if (quiet) 0L else System.currentTimeMillis() + 60 * 60_000
                quietUntil = Prefs.quietUntil
            }, Modifier.weight(1f).height(48.dp)) { Text(if (quiet) "Resume" else "Quiet 1h", maxLines = 1) }
        }
        if (quiet) Text(
            "Quiet until ${SimpleDateFormat("h:mm a", Locale.US).format(Date(quietUntil))}.",
            color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(12.dp))
        val heldNow by Glasses.heldItems.collectAsState()
        if (heldNow.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Held for later" + (com.jondude.anzu.assist.Discretion.current()?.let { " · $it" } ?: ""),
                    color = Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                Text("Clear", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { Glasses.takeHeld() }.padding(8.dp))
            }
            heldNow.takeLast(6).reversed().forEach { line ->
                Text(line, color = Muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().background(Panel).padding(16.dp))
                Spacer(Modifier.height(8.dp))
            }
        }
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

        Spacer(Modifier.height(4.dp))
        }
    }
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
                            modifier = Modifier.fillMaxWidth().clip(Flat).clickable {
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LargeTitle("Notes")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft, onValueChange = { draft = it },
                label = { Text("New note") }, modifier = Modifier.weight(1f),
                shape = Chamfer,
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
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(n.text, color = Cream, fontSize = 16.sp, lineHeight = 22.sp)
                    Text(timeAgo(n.time), color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { Store.removeNote(i) }) { Icon(Icons.Filled.Close, contentDescription = "Delete note", tint = Muted) }
            }
        }

        LoopsPanel()
        val captures = notes.filter { n -> listOf("Idea: ", "Note: ", "Reminder: ", "To do: ").any { n.text.startsWith(it) } }
        if (captures.isNotEmpty()) Section("Captured") {
            captures.takeLast(12).reversed().forEach { n ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(n.text.substringBefore(": "), color = Gold, fontSize = 13.sp)
                    Text(n.text.substringAfter(": "), color = Cream, fontSize = 16.sp, lineHeight = 22.sp)
                    Text(timeAgo(n.time), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Light)
                }
            }
            Text("Say \"${Prefs.name}, idea: …\", \"note …\", \"todo …\" or \"remind me to …\". Your hub sorts them overnight.", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Light)
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
                            Modifier.fillMaxWidth().clip(Flat).clickable { Store.listToggle(name, i) }.padding(vertical = 8.dp),
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Modifier.fillMaxWidth().clip(Flat).background(Panel).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(34.dp).clip(Flat))
                else Box(Modifier.size(34.dp).clip(Flat).background(Fill))
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
    var rate by remember { mutableStateOf(Prefs.speechRate.toFloat()) }
    var mode by remember { mutableIntStateOf(Prefs.listenMode) }
    var identity by remember { mutableStateOf(Prefs.identity) }
    var page by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    BackHandler(enabled = page != null) { page = null }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (page == null) 0.dp else 16.dp)
    ) {
        val open = page
        if (open == null) {
            LargeTitle("Settings")
            Spacer(Modifier.height(4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                IconButton(onClick = { page = null }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Cream) }
                Text(open, fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Cream)
            }
        }
        CompositionLocalProvider(LocalSettingsNav provides SettingsNav(page) { page = it }) {
        Section("Brain") {
            PrefField("Anthropic API key", Prefs.apiKey, secret = true) { Prefs.apiKey = it.trim() }
            var model by remember { mutableStateOf(Prefs.model) }
            var custom by remember { mutableStateOf(Prefs.model !in Prefs.KNOWN_MODELS) }
            Text("Model", color = Cream, fontSize = 16.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Sonnet 5.5 (recommended)" to "claude-sonnet-5-5",
                    "Sonnet 4.5" to "claude-sonnet-4-5",
                    "Haiku 4.5 (economy)" to "claude-haiku-4-5"
                ).forEach { (label, id) ->
                    if (!custom && model == id) Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ground), elevation = null) { Text(label) }
                    else SoftButton(onClick = { model = id; custom = false; Prefs.model = id }) { Text(label) }
                }
                if (custom) Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ground), elevation = null) { Text("Custom…") }
                else SoftButton(onClick = { custom = true }) { Text("Custom…") }
            }
            if (custom) PrefField("Custom model ID", model) { model = it; Prefs.model = it.trim() }
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
            Text("Keeping it down", color = Cream, fontSize = 16.sp)
            Text("At the D&D table, with guests over, after bedtime and during work hours, ${Prefs.name} holds low-priority things as cards on Home instead of speaking. Urgent things and your own questions still come through. Say \"${Prefs.name}, guests are here\" to turn on guest mode.", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light)
            ToggleRow("During work hours (weekdays ${Prefs.workStart}:00–${Prefs.workEnd}:00)", null, Prefs.workDiscretion) { Prefs.workDiscretion = it }
            ToggleRow("Duck music when someone's talking with you", "With the Buddie earbuds, which can tell your voice from others': music and audiobooks dip while you talk with someone, and briefs wait as cards until you're done. Loud machinery and calls mute the wake word so noise can't set it off.", Prefs.duckForConversation) { Prefs.duckForConversation = it }
            ToggleRow("Co-pilot while driving", "When you're driving (or Android Auto is on): one or two short sentences, nothing to look at, yes-or-no out loud instead of tapping. House and other low-priority things wait until you park.", Prefs.coPilot) { Prefs.coPilot = it }
            ToggleRow("Grayscale after bedtime (${Prefs.bedtimeHour}:00–${Prefs.wakeHour}:00)", null, Prefs.grayscaleNight) { Prefs.grayscaleNight = it }

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

        Section("Earbuds") {
            val often = listOf("now and then (about every 2 hours)", "regularly (about every 50 minutes)", "chatty (about every 20 minutes)")
            var chatBuds by remember { mutableStateOf(Prefs.chattinessBuds.toFloat()) }
            var volBuds by remember { mutableStateOf(Prefs.volumeBuds.toFloat()) }
            Text("Your earbuds are the private channel — only you hear what's said.", color = Muted, fontSize = 13.sp)
            Text("Speaks up " + often[chatBuds.toInt().coerceIn(0, 2)])
            Slider(value = chatBuds, onValueChange = { chatBuds = it; Prefs.chattinessBuds = it.toInt().coerceIn(0, 2) }, valueRange = 0f..2f, steps = 1)
            Text("Volume ${volBuds.toInt()}%")
            Slider(value = volBuds, onValueChange = { volBuds = it; Prefs.volumeBuds = it.toInt() }, valueRange = 30f..300f)
            Text("Above 100% adds a loudness boost on top of the earbuds' own volume.", color = Muted, fontSize = 13.sp)
            var lift by remember { mutableStateOf(Prefs.minSpeechVolume.toFloat()) }
            Text(if (lift.toInt() == 0) "Leave the phone's volume alone" else "While ${Prefs.name} speaks, raise the phone's volume to at least ${lift.toInt()}%")
            Slider(value = lift, onValueChange = { lift = it; Prefs.minSpeechVolume = it.toInt() }, valueRange = 0f..100f)
            var mic by remember { mutableStateOf(Prefs.micBoost.toFloat()) }
            Text("Earbud mic sensitivity: " + listOf("normal", "high", "max")[mic.toInt().coerceIn(0, 2)])
            Slider(value = mic, onValueChange = { mic = it; Prefs.micBoost = it.toInt().coerceIn(0, 2) }, valueRange = 0f..2f, steps = 1)
            Text("Higher hears you better when you speak softly; lower if it picks up too much around you. Re-seat the buds after changing.", color = Muted, fontSize = 13.sp)
            ToggleRow("Notifications: sender only", "Off reads the whole message, since only you can hear", Prefs.senderOnlyBuds) { Prefs.senderOnlyBuds = it }
        }

        Section("Voice") {
            var provider by remember { mutableIntStateOf(Prefs.voiceProvider) }
            var pick by remember { mutableStateOf("") }
            var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var chosen by remember { mutableStateOf(Prefs.voiceName) }
            var eleven by remember { mutableStateOf(Prefs.elevenVoiceName) }
            var voiceNote by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Phone" to 0, "ElevenLabs" to 1, "Kokoro" to 3, "Chatterbox" to 4, "Custom" to 2).forEach { (label, i) ->
                    if (provider == i) Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ground), elevation = null) { Text(label) }
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
                3 -> {
                    Text("Kokoro on Obelisk — fast, light, 54 built-in voices. Runs at home, no credits, no internet needed beyond the tailnet.", color = Muted, fontSize = 13.sp)
                    PrefField("Voice", Prefs.kokoroVoice) { Prefs.kokoroVoice = it.trim() }
                    Text("Try af_heart, af_bella, am_michael, bf_emma — a = American, b = British, f/m = female/male.", color = Muted, fontSize = 12.sp)
                }
                4 -> {
                    Text("Chatterbox Turbo on Obelisk — the expressive one, closest to ElevenLabs. Built-in voice for now; cloning from a short clip comes later.", color = Muted, fontSize = 13.sp)
                    PrefField("Voice", Prefs.chatterboxVoice) { Prefs.chatterboxVoice = it.trim() }
                    Text("Leave blank (or \"default\") for the built-in voice.", color = Muted, fontSize = 12.sp)
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
                                    modifier = Modifier.fillMaxWidth().clip(Flat).clickable {
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
                    if (mode == i) Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ground), elevation = null) { Text(label) }
                    else SoftButton(onClick = { mode = i; Prefs.listenMode = i }) { Text(label) }
                }
            }
            Text("From the glasses, use the Anzu voice-assistant gesture and pick ${Prefs.name} when Android asks which app to use.", color = Muted, fontSize = 13.sp)
            var mic by remember { mutableIntStateOf(Prefs.micSource) }
            val lastMic by Glasses.inputSource.collectAsState()
            Text("Voice input source", color = Cream, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..2).forEach { i ->
                    if (mic == i) Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ground)) { Text(MicRoute.choiceLabel(i)) }
                    else SoftButton(onClick = { mic = i; Prefs.micSource = i }) { Text(MicRoute.choiceLabel(i)) }
                }
            }
            Text(
                when (mic) {
                    1 -> "Listens through the connected Bluetooth headset's mic."
                    2 -> "Listens through the phone's own mics; replies still play in your buds or glasses."
                    else -> "Listens through whatever you're wearing, or the phone if nothing is."
                } + if (lastMic.isNotBlank()) " Last listened through: $lastMic." else "",
                color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light
            )
            val ctxHt = LocalContext.current
            val headTracker = remember { com.jondude.anzu.assist.HeadGesture.tracker(ctxHt)?.name }
            Text(
                if (headTracker != null) "Head tracking: $headTracker. Nod to approve, shake to decline." else "Head tracking: none from your current headset, so approvals use the card or a spoken yes.",
                color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light
            )
            val wakeNow by WakeListener.state.collectAsState()
            ToggleRow(
                "Wake word: “${Prefs.name}” hands-free",
                "Listens on the phone for its name through your headset (or the phone, with Phone mic). Nothing leaves the phone until it hears it. " +
                    "Keeping a Bluetooth mic open uses more of the buds' battery and drops them to call-quality sound, so it pauses whenever music or a call is playing, and during quiet time.",
                Prefs.wakeListen
            ) { Prefs.wakeListen = it }
            Text(
                "Right now: $wakeNow." + if (Prefs.lastWake > 0) " Last woke ${timeAgo(Prefs.lastWake)} through ${Prefs.lastWakeSource}." else "",
                color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Light
            )
            ToggleRow("Whisper back", "A quiet question gets a quiet answer", Prefs.whisperBack) { Prefs.whisperBack = it }
            Text("Speaking speed")
            Slider(value = rate, onValueChange = { rate = it; Prefs.speechRate = it.toInt() }, valueRange = 70f..160f)
            SoftButton(onClick = { Atlas.micTest(ctx) }) { Text("Test voice and microphone") }
        }

        Section("Systems") { SystemsPanel() }

        Section("Athena") { AthenaPanel() }

        Section("Home Assistant") {
            Text("Address: $HOME_ASSISTANT (built in)", fontSize = 14.sp)
            PrefField("Long-lived access token", Prefs.haToken, secret = true) { Prefs.haToken = it.trim() }
            ToggleRow("House alerts", "Tells you when something is left open or unlocked, and when people come and go", Prefs.homeAlerts) { Prefs.homeAlerts = it }
            PrefField("Alert after open for (minutes)", Prefs.homeOpenMinutes.toString()) { s -> s.toIntOrNull()?.let { Prefs.homeOpenMinutes = it.coerceIn(1, 240) } }
            PrefField("Don't announce comings and goings for", Prefs.homeIgnore) { Prefs.homeIgnore = it }
            ToggleRow("Use a house speaker when I'm not wearing anything", "Timers, alerts and briefs go to a speaker in the house", Prefs.houseSpeaker) { Prefs.houseSpeaker = it }
            ToggleRow("Follow me room to room", "Speak on the speaker in the room you're in (from a room sensor, or the latest motion in a room with a speaker). The speaker below is the fallback.", Prefs.followMe) { Prefs.followMe = it }
            PrefField("Room sensor, if you have one (e.g. sensor.jons_phone_area)", Prefs.followMeSensor) { Prefs.followMeSensor = it.trim() }
            PrefField("Fallback speaker (e.g. media_player.office)", Prefs.haSpeaker) { Prefs.haSpeaker = it.trim() }
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
                "Anything that speaks MCP can be connected here, and its tools become things ${Prefs.name} can do: your house, your desktop, your servers, your other apps. Home Assistant and the Atlas hub have it built in.",
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
            if (Prefs.haToken.isBlank()) Text("Home Assistant connects here by itself once its token is in under Settings → Home Assistant.", color = Muted, fontSize = 13.sp)
            if (Prefs.hubToken.isBlank()) Text("The Atlas hub connects here by itself once its token is in.", color = Muted, fontSize = 13.sp)
            Text("Add a connection", color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            PrefField("Name (for example Athena files)", "") { newName = it }
            PrefField("Address (for example http://$ATHENA:8000/mcp)", "") { newUrl = it.trim() }
            PrefField("Token, if it needs one", "", secret = true) { newToken = it.trim() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = newName.isNotBlank() && newUrl.startsWith("http"), onClick = {
                    val s = com.jondude.anzu.assist.McpServer(newName.trim(), newUrl, newToken)
                    com.jondude.anzu.assist.Mcp.upsert(s); reload(); check(s)
                }) { Text("Connect") }
                SoftButton(onClick = { scope.launch(Dispatchers.IO) { com.jondude.anzu.assist.Mcp.refresh(force = true) } }) { Text("Check all") }
            }
            Text("Your machines: Obelisk is $OBELISK, Athena is $ATHENA, Home Assistant is ${HOME_ASSISTANT.removePrefix("http://")}.", color = Muted, fontSize = 12.sp)
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
            Text("Hub: $OBELISK_HUB (built in)", fontSize = 14.sp)
            PrefField("Hub token, if you set one", Prefs.hubToken, secret = true) { Prefs.hubToken = it.trim() }
            Text("Your apps report to the hub, its local model digests everything for free, and ${Prefs.name} only reads a short brief. It also logs every exchange to your vault. The server is in the repo under obelisk/.", color = Muted, fontSize = 12.sp)
            Text("Ollama: $OLLAMA (built in)", fontSize = 14.sp)
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
