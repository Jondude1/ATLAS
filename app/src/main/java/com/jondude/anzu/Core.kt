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

object Prefs {
    lateinit var sp: SharedPreferences

    val DEFAULT_APPS = setOf(
        "com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger",
        "com.discord", "org.thoughtcrime.securesms", "com.google.android.gm",
        "com.google.android.dialer", "com.facebook.orca", "com.Slack",
        "com.microsoft.teams", "com.google.android.calendar"
    )

    fun init(c: Context) {
        sp = c.getSharedPreferences("corin", Context.MODE_PRIVATE)
    }

    var apiKey by StrPref("apiKey", "")
    var model by StrPref("model", "claude-haiku-5-5")
    var name by StrPref("name", "Corin")
    /** Core identity Corin wrote for itself on first run. Fixed afterwards. */
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
    var obeliskUrl by StrPref("obeliskUrl", "")
    var ollamaUrl by StrPref("ollamaUrl", "")
    var ollamaModel by StrPref("ollamaModel", "mistral-nemo")
    var localSmallTalk by BoolPref("localSmallTalk", false)
    var amberWords by StrPref("amberWords", "unlock, garage, open the door, alarm, thermostat, oven, heater")
    var redWords by StrPref("redWords", "disarm")
    /** 0 = never listen, 1 = tap to talk, 2 = conversation window after Corin speaks */
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
    var commuteAlerts by BoolPref("commuteAlerts", true)
    var houseSpeaker by BoolPref("houseSpeaker", false)
    var haSpeaker by StrPref("haSpeaker", "")
    var haTtsService by StrPref("haTtsService", "tts.google_translate_say")
    var aboutMe by StrPref("aboutMe", "")
    var haUrl by StrPref("haUrl", "")
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

    var readNotifications by BoolPref("readNotifications", true)
    var senderOnly by BoolPref("senderOnly", false)
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

/** Corin's long-term memory, Jon's notes, and the rolling conversation. */
object Store {
    private lateinit var dir: File
    val memory = MutableStateFlow<List<String>>(emptyList())
    val notes = MutableStateFlow<List<Note>>(emptyList())
    val actions = MutableStateFlow<List<Note>>(emptyList())
    val lists = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    private val history = ArrayList<Pair<String, String>>()

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
            history.add(o.optString("u") to o.optString("a"))
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

    /** Record something Corin did on its own authority, for the daily digest. */
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
    fun addExchange(user: String, assistant: String) {
        history.add(user to assistant)
        while (history.size > 8) history.removeAt(0)
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("u", it.first).put("a", it.second)) }
        write("history.json", a)
    }

    @Synchronized
    fun recentExchanges(): List<Pair<String, String>> = history.toList()

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
    val corinState = MutableStateFlow("idle")
    val lastSaid = MutableStateFlow("")
    val lastHeard = MutableStateFlow("")
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
    }

    @Synchronized
    fun takeHeld(): List<String> {
        val h = held.toList()
        held.clear()
        heldCount.value = 0
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
