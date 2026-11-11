package com.jondude.anzu

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.reflect.KProperty

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        Speaker.init(this)
        com.jondude.anzu.assist.Brain.appContext = this
    }
}

fun Context.has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

class StrPref(private val key: String, private val def: String) {
    operator fun getValue(t: Any?, p: KProperty<*>): String = Prefs.sp.getString(key, def) ?: def
    operator fun setValue(t: Any?, p: KProperty<*>, v: String) = Prefs.sp.edit().putString(key, v).apply()
}

class BoolPref(private val key: String, private val def: Boolean) {
    operator fun getValue(t: Any?, p: KProperty<*>): Boolean = Prefs.sp.getBoolean(key, def)
    operator fun setValue(t: Any?, p: KProperty<*>, v: Boolean) = Prefs.sp.edit().putBoolean(key, v).apply()
}

class IntPref(private val key: String, private val def: Int) {
    operator fun getValue(t: Any?, p: KProperty<*>): Int = Prefs.sp.getInt(key, def)
    operator fun setValue(t: Any?, p: KProperty<*>, v: Int) = Prefs.sp.edit().putInt(key, v).apply()
}

class LongPref(private val key: String, private val def: Long) {
    operator fun getValue(t: Any?, p: KProperty<*>): Long = Prefs.sp.getLong(key, def)
    operator fun setValue(t: Any?, p: KProperty<*>, v: Long) = Prefs.sp.edit().putLong(key, v).apply()
}

// Jon's machines on his tailnet. Built in so nothing has to be typed. Tailnet IPs are stable and don't depend on
// MagicDNS, which isn't always active on the phone; the names are kept for display and as a fallback map.
/** The hub on Obelisk. */
const val OBELISK_HUB = "http://100.65.202.81:8765"
/** Home Assistant (port 80). */
const val HOME_ASSISTANT = "http://100.68.172.113"
/** Athena, the Windows desktop. */
const val ATHENA = "100.88.113.24"
/** Obelisk, the local AI node (Ollama, Hermes, the hub). */
const val OBELISK = "100.65.202.81"

/** MagicDNS names → tailnet IPs, so an address typed or saved by name still works when the name won't resolve. */
val TAILNET = mapOf(
    "obalisk.tail8dab0b.ts.net" to "100.65.202.81",
    "obalisk" to "100.65.202.81",
    "athena.tail8dab0b.ts.net" to "100.88.113.24",
    "athena" to "100.88.113.24",
    "homeassistant-1.tail8dab0b.ts.net" to "100.68.172.113",
    "homeassistant-1" to "100.68.172.113"
)

/** Rewrites a URL whose host is one of Jon's tailnet names to its IP. Anything else is returned unchanged. */
fun tailnet(url: String): String = try {
    val u = java.net.URI(url.trim())
    val ip = u.host?.lowercase()?.let { TAILNET[it] }
    if (ip == null) url.trim() else java.net.URI(u.scheme, u.userInfo, ip, u.port, u.path, u.query, u.fragment).toString()
} catch (_: Exception) {
    url.trim()
}

object Prefs {
    lateinit var sp: SharedPreferences

    val DEFAULT_APPS = setOf(
        "com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger",
        "com.discord", "org.thoughtcrime.securesms", "com.google.android.gm",
        "com.google.android.dialer", "com.facebook.orca", "com.Slack",
        "com.microsoft.teams", "com.google.android.calendar"
    )

    fun init(c: Context) {
        // The settings file keeps its original name so nothing is lost across the rename to Atlas.
        sp = c.getSharedPreferences("corin", Context.MODE_PRIVATE)
        // Jon's own network addresses are built in, so there's nothing to type. Fill them in for
        // anyone who installed before they were (an empty field counts as not set).
        if (!sp.getBoolean("networkDefaultsV1", false)) {
            val e = sp.edit().putBoolean("networkDefaultsV1", true)
            if (sp.getString("obeliskUrl", "").isNullOrBlank()) e.putString("obeliskUrl", OBELISK_HUB)
            if (sp.getString("haUrl", "").isNullOrBlank()) e.putString("haUrl", HOME_ASSISTANT)
            e.apply()
        }
        if (!sp.getBoolean("micSourceV1", false)) {
            val e = sp.edit().putBoolean("micSourceV1", true)
            if (!sp.getBoolean("useGlassesMic", true)) e.putInt("micSource", 2)
            e.apply()
        }
        if (!sp.getBoolean("louderV1", false)) {
            // Jon found the earbuds too quiet: start everyone above 100% (the extra comes from a loudness boost).
            val e = sp.edit().putBoolean("louderV1", true)
            if (sp.getInt("volumeBuds", 100) <= 100) e.putInt("volumeBuds", 160)
            if (sp.getInt("volumeGlasses", 85) <= 100) e.putInt("volumeGlasses", 120)
            e.apply()
        }
        if (!sp.getBoolean("networkByIpV2", false)) {
            // Older builds saved the MagicDNS names; move them to the IPs.
            val e = sp.edit().putBoolean("networkByIpV2", true)
            listOf("obeliskUrl", "haUrl", "ollamaUrl", "hermesUrl").forEach { k ->
                val v = sp.getString(k, null)
                if (!v.isNullOrBlank()) e.putString(k, tailnet(v))
            }
            e.apply()
        }
        if (!sp.getBoolean("renamedToAtlas", false)) {
            val e = sp.edit().putBoolean("renamedToAtlas", true)
            if (sp.getString("name", null) == "Corin") e.putString("name", "Atlas")
            e.apply()
        }
    }

    var apiKey by StrPref("apiKey", "")
    var model by StrPref("model", "claude-haiku-5-5")
    var name by StrPref("name", "Atlas")
    /** Core identity Atlas wrote for itself on first run. Fixed afterwards. */
    var identity by StrPref("identity", "")
    /** How the personality has shifted lately. Rewritten slowly over time. */
    var drift by StrPref("drift", "")
    var lastDriftDay by StrPref("lastDriftDay", "")
    var voiceName by StrPref("voiceName", "")
    /** 0 = the phone's speech engine, 1 = ElevenLabs, 2 = any OpenAI-style speech server */
    var voiceProvider by IntPref("voiceProvider", 0)
    var ttsEngine by StrPref("ttsEngine", "")
    var elevenKey by StrPref("elevenKey", "")
    var elevenVoiceId by StrPref("elevenVoiceId", "")
    var elevenVoiceName by StrPref("elevenVoiceName", "")
    var elevenModel by StrPref("elevenModel", "eleven_flash_v2_5")
    var speechUrl by StrPref("speechUrl", "https://api.openai.com/v1")
    var speechKey by StrPref("speechKey", "")
    var speechModel by StrPref("speechModel", "gpt-4o-mini-tts")
    var speechVoice by StrPref("speechVoice", "alloy")
    var obeliskUrl by StrPref("obeliskUrl", OBELISK_HUB)
    var hubToken by StrPref("hubToken", "")
    var ollamaUrl by StrPref("ollamaUrl", "")
    var ollamaModel by StrPref("ollamaModel", "mistral-nemo")
    var localSmallTalk by BoolPref("localSmallTalk", false)
    var amberWords by StrPref("amberWords", "unlock, garage, open the door, alarm, thermostat, oven, heater")
    var redWords by StrPref("redWords", "disarm")
    /** 0 = never listen, 1 = tap to talk, 2 = conversation window after Atlas speaks */
    var listenMode by IntPref("listenMode", 2)
    var whisperBack by BoolPref("whisperBack", true)
    var triage by BoolPref("triage", true)
    var journal by BoolPref("journal", true)
    var journalHour by IntPref("journalHour", 21)
    var lastJournalDay by StrPref("lastJournalDay", "")
    var homeAlerts by BoolPref("homeAlerts", true)
    var homeOpenMinutes by IntPref("homeOpenMinutes", 10)
    var homeIgnore by StrPref("homeIgnore", "jon")
    var activityAware by BoolPref("activityAware", true)
    var buddieListen by BoolPref("buddieListen", true)
    var buddieAmbient by BoolPref("buddieAmbient", false)
    var buddieBleAddress by StrPref("buddieBleAddress", "")
    var wakeWord by BoolPref("wakeWord", true)
    var callNotes by BoolPref("callNotes", false)
    var vaultUri by StrPref("vaultUri", "")
    var hermesUrl by StrPref("hermesUrl", "")
    var hermesKey by StrPref("hermesKey", "")
    var hermesModel by StrPref("hermesModel", "hermes-agent")
    var buddieFastLink by BoolPref("buddieFastLink", false)
    var buddieCompressedFirst by BoolPref("buddieCompressedFirst", false)
    var commuteAlerts by BoolPref("commuteAlerts", true)
    var houseSpeaker by BoolPref("houseSpeaker", false)
    var haSpeaker by StrPref("haSpeaker", "")
    var haTtsService by StrPref("haTtsService", "tts.google_translate_say")
    var aboutMe by StrPref("aboutMe", "")
    var haUrl by StrPref("haUrl", HOME_ASSISTANT)
    /** Whether Home Assistant's own MCP tools are offered under Connections. */
    var haMcp by BoolPref("haMcp", true)
    var haToken by StrPref("haToken", "")
    var deviceAddress by StrPref("deviceAddress", "")
    var deviceName by StrPref("deviceName", "")

    var proactive by BoolPref("proactive", true)
    /** 0 = now and then, 1 = regularly, 2 = chatty */
    var chattiness by IntPref("chattiness", 1)
    var activeStart by IntPref("activeStart", 8)
    var activeEnd by IntPref("activeEnd", 22)
    var talkOverMedia by BoolPref("talkOverMedia", false)
    var useGlassesMic by BoolPref("useGlassesMic", true)
    /** Where the voice loop listens: 0 auto (worn device if there is one), 1 the Bluetooth headset, 2 the phone's own mics. */
    var micSource by IntPref("micSource", 0)
    /** Hands-free "Atlas" through a headset or the phone (WakeListener). On by default. */
    var wakeListen by BoolPref("wakeListen", true)
    // Context Discretion
    var guestsUntil by LongPref("guestsUntil", 0L)
    var bedtimeHour by IntPref("bedtimeHour", 23)
    var wakeHour by IntPref("wakeHourMorning", 7)
    var workDiscretion by BoolPref("workDiscretion", true)
    var workStart by IntPref("workStart", 8)
    var workEnd by IntPref("workEnd", 17)
    var grayscaleNight by BoolPref("grayscaleNight", true)
    var lastWake by LongPref("lastWake", 0L)
    var lastWakeSource by StrPref("lastWakeSource", "")

    var readNotifications by BoolPref("readNotifications", true)
    // Per-wearable behavior. The glasses are open-ear (people nearby can hear); the buds are private.
    var senderOnlyGlasses by BoolPref("senderOnlyGlasses", true)
    var senderOnlyBuds by BoolPref("senderOnlyBuds", false)
    var chattinessBuds by IntPref("chattinessBuds", 1)
    var volumeGlasses by IntPref("volumeGlasses", 120)
    /** While Atlas speaks, lift the phone's media volume to at least this share of maximum (0 = leave it). */
    var minSpeechVolume by IntPref("minSpeechVolume", 70)
    /** Kinds of proactive lines Jon has cut off, with when (see Interrupts). */
    var cutoffs by StrPref("cutoffs", "")
    /** Extra microphone sensitivity for the earbud stream: 0 normal, 1 high, 2 max. */
    var micBoost by IntPref("micBoost", 1)
    var volumeBuds by IntPref("volumeBuds", 160)
    var discreetGlasses by BoolPref("discreetGlasses", true)
    var announceSwitch by BoolPref("announceSwitch", true)
    var eyeBreaks by BoolPref("eyeBreaks", false)
    var calendarHeadsUp by BoolPref("calendarHeadsUp", true)
    var speechRate by IntPref("speechRate", 105)

    var quietUntil by LongPref("quietUntil", 0L)
    var lastGreetDay by StrPref("lastGreetDay", "")
    var lastSeenTime by LongPref("lastSeenTime", 0L)
    var lastSeenLat by StrPref("lastSeenLat", "")
    var lastSeenLon by StrPref("lastSeenLon", "")

    var allowedApps: Set<String>
        get() = sp.getStringSet("allowedApps", null) ?: DEFAULT_APPS
        set(v) = sp.edit().putStringSet("allowedApps", v).apply()
}

data class Note(val text: String, val time: Long)

data class Item(val text: String, val done: Boolean)

/** How Atlas behaves depending on which wearable it's in. */
object Wear {
    /** Any in-ear, private wearable: the Buddie earbuds or the Pixel Buds. */
    fun onBuds(): Boolean = Glasses.active.value in setOf("buddie", "pixelbuds", "galaxybuds")
    fun onPixelBuds(): Boolean = Glasses.active.value == "pixelbuds"
    fun onGlasses(): Boolean = Glasses.active.value == "glasses"

    /** Unprompted-speech frequency for the active wearable (0 to 2). */
    fun chattiness(): Int = (if (onBuds()) Prefs.chattinessBuds else Prefs.chattiness).coerceIn(0, 2)

    /** Whether notifications are read as sender only. */
    fun senderOnly(): Boolean = if (onBuds()) Prefs.senderOnlyBuds else Prefs.senderOnlyGlasses

    /** Speaking volume multiplier. */
    fun volume(): Float = when {
        onBuds() -> Prefs.volumeBuds / 100f
        onGlasses() -> Prefs.volumeGlasses / 100f
        else -> 1f
    }.coerceIn(0.2f, 3f)

    /** With the buds' always-on stream running, replies come through it instead of opening a call-style mic. */
    fun repliesViaStream(): Boolean = Glasses.active.value == "buddie" && Glasses.buddieStream.value == "listening"

    /** Eye-break nudges only make sense with the screen glasses on. */
    fun eyeBreaks(): Boolean = onGlasses()

    fun label(): String = when {
        onPixelBuds() -> "Pixel Buds: private"
        Glasses.active.value == "galaxybuds" -> "Galaxy Buds: private"
        onBuds() -> "earbuds: private"
        onGlasses() -> if (Prefs.discreetGlasses) "glasses: open-ear, discreet" else "glasses: open-ear"
        else -> ""
    }

    /** What the model is told about the situation. */
    fun promptLine(): String = when {
        Glasses.active.value == "galaxybuds" -> "You're in his Galaxy Buds right now. Only he can hear you, so you can be personal and take a little more room when it's worth it. He talks to you with the Talk button, the widget or the assistant gesture, and can answer right after you speak."
        onPixelBuds() -> "You're in his Pixel Buds Pro 2 right now. Only he can hear you, so you can be personal and take a little more room when it's worth it. These have good sound and noise cancelling, so he may be listening to something or out and about; he can answer you right after you speak."
        onBuds() -> "You're in his Buddie earbuds right now. Only he can hear you, so you can be personal and take a little more room when it's worth it. He can talk to you hands-free by saying your name, and he may be out and about."
        onGlasses() -> "You're in his Razer Anzu glasses right now. They are open-ear speakers, so people near him can hear you" +
            (if (Prefs.discreetGlasses) ": keep it to one or two short sentences, and don't say private details out loud (money, health, personal messages, anything about other people); say there's something private and offer to save it for the earbuds or his phone instead." else ": keep it to one or two short sentences.") +
            " These are his screen glasses, so he's most likely at a desk."
        else -> ""
    }

    /** The line Atlas says when he swaps wearables mid-session. */
    fun switchLine(): String = when {
        onPixelBuds() -> "Pixel Buds. It's just us now."
        onBuds() -> "Earbuds. It's just us now."
        else -> "Glasses on. I'll keep it discreet."
    }
}

/** How long ago something was, in words a person would say. */
object When {
    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        val m = (now - t) / 60_000
        val clock = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(t))
        return when {
            m < 2 -> "just now"
            m < 60 -> "$m minutes ago"
            m < 120 -> "an hour ago, at $clock"
            m < 24 * 60 && sameDay(t, now) -> "${m / 60} hours ago, at $clock"
            m < 48 * 60 -> "yesterday at $clock"
            else -> java.text.SimpleDateFormat("EEEE MMM d 'at' h:mm a", java.util.Locale.US).format(java.util.Date(t))
        }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return f.format(java.util.Date(a)) == f.format(java.util.Date(b))
    }

    fun stamp(t: Long): String = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(t))
}

/** Rough daily token meter so API spend stays visible. */
object Usage {
    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    @Synchronized
    fun add(input: Int, output: Int) {
        val sp = Prefs.sp
        val same = sp.getString("usageDay", "") == today()
        sp.edit().putString("usageDay", today())
            .putInt("usageCalls", (if (same) sp.getInt("usageCalls", 0) else 0) + 1)
            .putInt("usageIn", (if (same) sp.getInt("usageIn", 0) else 0) + input)
            .putInt("usageOut", (if (same) sp.getInt("usageOut", 0) else 0) + output)
            .apply()
    }

    fun summary(): String {
        val sp = Prefs.sp
        if (sp.getString("usageDay", "") != today()) return "No model calls yet today."
        return "Today: ${sp.getInt("usageCalls", 0)} calls, ${sp.getInt("usageIn", 0)} tokens in, ${sp.getInt("usageOut", 0)} out."
    }
}

/** Atlas's long-term memory, Jon's notes, and the rolling conversation. */
object Store {
    private lateinit var dir: File
    val memory = MutableStateFlow<List<String>>(emptyList())
    val notes = MutableStateFlow<List<Note>>(emptyList())
    val actions = MutableStateFlow<List<Note>>(emptyList())
    val lists = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    /** Recent exchanges with when they happened: (what he said, the answer, epoch ms). */
    private val history = ArrayList<Triple<String, String, Long>>()

    fun init(c: Context) {
        dir = c.filesDir
        File(dir, "log").mkdirs()
        history.clear()
        lists.value = emptyMap()
        try {
            val o = JSONObject(File(dir, "lists.json").readText())
            val m = LinkedHashMap<String, List<Item>>()
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                m[k] = (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.optString("t"), it.optBoolean("d")) }
            }
            lists.value = m
        } catch (_: Exception) {
        }
        actions.value = readArray("actions.json").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Note(it.optString("text"), it.optLong("time")) }
        }
        memory.value = readArray("memory.json").let { a -> (0 until a.length()).map { a.getString(it) } }
        notes.value = readArray("notes.json").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Note(it.optString("text"), it.optLong("time")) }
        }
        val h = readArray("history.json")
        for (i in 0 until h.length()) {
            val o = h.getJSONObject(i)
            // entries from before timestamps were kept have no time; they're too old to matter
            val t = o.optLong("t", 0L)
            if (t > 0) history.add(Triple(o.optString("u"), o.optString("a"), t))
        }
    }

    private fun readArray(name: String): JSONArray = try {
        JSONArray(File(dir, name).readText())
    } catch (e: Exception) {
        JSONArray()
    }

    private fun write(name: String, a: JSONArray) {
        try {
            File(dir, name).writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun addMemory(fact: String) {
        val f = fact.trim()
        if (f.isEmpty() || memory.value.any { it.equals(f, true) }) return
        memory.value = (memory.value + f).takeLast(80)
        write("memory.json", JSONArray(memory.value))
    }

    @Synchronized
    fun removeMemory(index: Int) {
        memory.value = memory.value.filterIndexed { i, _ -> i != index }
        write("memory.json", JSONArray(memory.value))
    }

    @Synchronized
    fun addNote(text: String) {
        if (text.isBlank()) return
        notes.value = listOf(Note(text.trim(), System.currentTimeMillis())) + notes.value
        saveNotes()
    }

    @Synchronized
    fun removeNote(index: Int) {
        notes.value = notes.value.filterIndexed { i, _ -> i != index }
        saveNotes()
    }

    private fun saveNotes() {
        val a = JSONArray()
        notes.value.forEach { a.put(JSONObject().put("text", it.text).put("time", it.time)) }
        write("notes.json", a)
    }

    private fun saveLists() {
        val o = JSONObject()
        lists.value.forEach { (k, v) ->
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("t", it.text).put("d", it.done)) }
            o.put(k, a)
        }
        try {
            File(dir, "lists.json").writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    /** Resolve a spoken list name to an existing list where possible. */
    private fun stem(w: String): String = when {
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    private fun listKey(name: String): String {
        val n = name.trim().lowercase().removePrefix("my ").removeSuffix(" list").ifBlank { "list" }
        val keys = lists.value.keys
        return keys.firstOrNull { it == n }
            ?: keys.firstOrNull { stem(it) == stem(n) }
            ?: keys.firstOrNull { it.contains(n) || n.contains(it) }
            ?: n
    }

    @Synchronized
    fun listAdd(name: String, item: String): String {
        val k = listKey(name)
        lists.value = lists.value + (k to (lists.value[k].orEmpty() + Item(item.trim(), false)))
        saveLists()
        return k
    }

    @Synchronized
    fun listNext(name: String): String {
        val k = listKey(name)
        val items = lists.value[k] ?: return "There's no list called $k."
        val next = items.firstOrNull { !it.done } ?: return "Everything on $k is done."
        return "Next on $k: ${next.text}. ${items.count { !it.done } - 1} more after that."
    }

    /** Checks off the first open item and reports the next one. */
    @Synchronized
    fun listDone(name: String): String {
        val k = listKey(name)
        val items = lists.value[k] ?: return "There's no list called $k."
        val i = items.indexOfFirst { !it.done }
        if (i < 0) return "Everything on $k is already done."
        lists.value = lists.value + (k to items.mapIndexed { j, x -> if (j == i) x.copy(done = true) else x })
        saveLists()
        return "Checked off ${items[i].text}. " + listNext(k)
    }

    @Synchronized
    fun listShow(name: String): String {
        if (name.isBlank()) return lists.value.keys.joinToString(", ").ifBlank { "No lists yet." }
        val k = listKey(name)
        val items = lists.value[k] ?: return "There's no list called $k. Lists: ${lists.value.keys.joinToString(", ")}"
        return items.joinToString("; ") { (if (it.done) "done: " else "open: ") + it.text }.ifBlank { "$k is empty." }
    }

    @Synchronized
    fun listToggle(key: String, index: Int) {
        val items = lists.value[key] ?: return
        lists.value = lists.value + (key to items.mapIndexed { j, x -> if (j == index) x.copy(done = !x.done) else x })
        saveLists()
    }

    @Synchronized
    fun listClear(name: String): String {
        val k = listKey(name)
        lists.value = lists.value - k
        saveLists()
        return "Cleared $k."
    }

    /** Record something Atlas did on its own authority, for the daily digest. */
    @Synchronized
    fun logAction(text: String) {
        actions.value = (listOf(Note(text, System.currentTimeMillis())) + actions.value).take(100)
        val a = JSONArray()
        actions.value.forEach { a.put(JSONObject().put("text", it.text).put("time", it.time)) }
        write("actions.json", a)
        appendLog("action", text)
    }

    fun actionsToday(): List<String> {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        return actions.value.filter { it.time >= start }.map { it.text }.reversed()
    }

    private fun day(t: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))

    /** Full conversation log, one markdown file per day. */
    @Synchronized
    fun appendLog(speaker: String, text: String) {
        try {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
            File(dir, "log/${day()}.md").appendText("- $time **$speaker**: ${text.replace('\n', ' ')}\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun todayLog(): String = try {
        File(dir, "log/${day()}.md").readText()
    } catch (e: Exception) {
        ""
    }

    /** Keyword search over the last 90 days of conversation logs. */
    @Synchronized
    fun searchLog(query: String, max: Int = 8): List<String> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (terms.isEmpty()) return emptyList()
        val files = (File(dir, "log").listFiles() ?: emptyArray()).sortedByDescending { it.name }.take(90)
        val hits = ArrayList<Pair<Int, String>>()
        for (f in files) {
            val date = f.name.removeSuffix(".md")
            try {
                f.forEachLine { line ->
                    val l = line.lowercase()
                    val score = terms.count { l.contains(it) }
                    if (score > 0) hits.add(score to "$date ${line.removePrefix("- ").take(260)}")
                }
            } catch (_: Exception) {
            }
        }
        return hits.sortedByDescending { it.first }.take(max).map { it.second }
    }

    @Synchronized
    fun addExchange(user: String, assistant: String, at: Long = System.currentTimeMillis()) {
        history.add(Triple(user, assistant, at))
        while (history.size > 10) history.removeAt(0)
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("u", it.first).put("a", it.second).put("t", it.third)) }
        write("history.json", a)
    }

    /** Exchanges from the last 12 hours, oldest first, with their times. Older ones live in the log. */
    @Synchronized
    fun recentTimed(now: Long = System.currentTimeMillis()): List<Triple<String, String, Long>> =
        history.filter { now - it.third < 12 * 60 * 60_000L }

    @Synchronized
    fun recentExchanges(): List<Pair<String, String>> = recentTimed().map { it.first to it.second }

    @Synchronized
    fun clearHistory() {
        history.clear()
        write("history.json", JSONArray())
    }
}

/** Live state shared between the service, the assistant, and the UI. */
object Glasses {
    val connected = MutableStateFlow(false)
    val battery = MutableStateFlow(-1)
    val connectedSince = MutableStateFlow(0L)
    val micArmed = MutableStateFlow(false)
    /** idle, listening, thinking, speaking */
    /** The microphone the last listen used, e.g. "Pixel mic" or "Galaxy Buds". */
    val inputSource = MutableStateFlow("")
    val atlasState = MutableStateFlow("idle")
    val lastSaid = MutableStateFlow("")
    val lastHeard = MutableStateFlow("")
    /** The wearable Atlas is speaking through: "glasses", "buddie", or empty. The most recently connected wins. */
    val active = MutableStateFlow("")

    /** What he's wearing right now: "glasses", "Buddie earbuds", both, or empty. */
    val wearing = MutableStateFlow("")
    /** Status of the Buddie always-listening stream, for the UI and status checks. */
    val buddieStream = MutableStateFlow("off")
    val meeting = MutableStateFlow(false)

    private val ambient = ArrayDeque<String>()

    @Synchronized
    fun noteAmbient(line: String) {
        ambient.addLast(line)
        while (ambient.size > 15) ambient.removeFirst()
    }

    @Synchronized
    fun recentAmbient(): List<String> = ambient.toList()

    private val recent = ArrayDeque<String>()
    private val held = ArrayList<String>()
    val heldCount = MutableStateFlow(0)

    /** Notifications triage decided weren't worth interrupting for. */
    @Synchronized
    fun hold(line: String) {
        held.add(line)
        while (held.size > 30) held.removeAt(0)
        heldCount.value = held.size
        heldItems.value = held.toList()
    }

    /** The held lines themselves, for the cards on Home. */
    val heldItems = MutableStateFlow<List<String>>(emptyList())

    @Synchronized
    fun takeHeld(): List<String> {
        val h = held.toList()
        held.clear()
        heldCount.value = 0
        heldItems.value = emptyList()
        return h
    }

    @Synchronized
    fun noteNotification(line: String) {
        recent.addLast(line)
        while (recent.size > 12) recent.removeFirst()
    }

    @Synchronized
    fun recentNotifications(): List<String> = recent.toList()

    fun wornMinutes(): Long =
        if (connected.value && connectedSince.value > 0) (System.currentTimeMillis() - connectedSince.value) / 60000 else 0
}
