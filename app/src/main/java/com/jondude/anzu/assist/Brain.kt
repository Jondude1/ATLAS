package com.jondude.anzu.assist

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.jondude.anzu.Glasses
import com.jondude.anzu.Motion
import com.jondude.anzu.Places
import com.jondude.anzu.Usage
import com.jondude.anzu.When
import com.jondude.anzu.Prefs
import com.jondude.anzu.Speaker
import com.jondude.anzu.Store
import com.jondude.anzu.has
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Net {
    /** Blocking request. Returns (status, body). */
    fun request(
        method: String, url: String, headers: Map<String, String>,
        body: String? = null, timeoutMs: Int = 30_000, connectTimeoutMs: Int = 8_000
    ): Pair<Int, String> {
        val conn = URL(com.jondude.anzu.tailnet(url)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking request that returns the raw response bytes (for audio). */
    fun requestBytes(
        method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int = 30_000,
        connectTimeoutMs: Int = 8_000
    ): Pair<Int, ByteArray> {
        val conn = URL(com.jondude.anzu.tailnet(url)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking POST of raw bytes. Returns (status, body). */
    fun postBytes(url: String, bytes: ByteArray, timeoutMs: Int = 30_000): Pair<Int, String> {
        val conn = URL(com.jondude.anzu.tailnet(url)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int = 30_000, connectTimeoutMs: Int = 8_000) =
        request("POST", url, headers, body, timeoutMs, connectTimeoutMs)

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000, connectTimeoutMs: Int = 8_000) =
        request("GET", url, headers, null, timeoutMs, connectTimeoutMs)
}

object HomeAssistant {
    fun configured(): Boolean = Prefs.haUrl.isNotBlank() && Prefs.haToken.isNotBlank()
    private fun base() = Prefs.haUrl.trim().trimEnd('/')
    private fun auth() = mapOf("Authorization" to "Bearer ${Prefs.haToken.trim()}")

    /** Sends a plain-language command to Home Assistant's conversation agent. Blocking. */
    fun command(text: String, timeoutMs: Int = 15_000, connectTimeoutMs: Int = 8_000): String {
        if (!configured()) return "Home Assistant isn't set up in settings."
        return try {
            val (code, body) = Net.postJson(
                base() + "/api/conversation/process", auth(),
                JSONObject().put("text", text).put("language", "en").toString(), timeoutMs, connectTimeoutMs
            )
            if (code !in 200..299) return "Home Assistant returned error $code."
            JSONObject(body).optJSONObject("response")
                ?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
                ?.takeIf { it.isNotBlank() } ?: "Done."
        } catch (e: Exception) {
            "Couldn't reach Home Assistant: ${e.message}"
        }
    }

    /** Speak through a house speaker (used when nothing is being worn): the one in the room he's in, if Follow-Me can tell. Blocking. */
    fun say(text: String): Boolean {
        if (!configured()) return false
        val speaker = (if (Prefs.followMe) FollowMe.speaker() else null) ?: Prefs.haSpeaker.trim()
        if (speaker.isBlank()) return false
        return try {
            val service = Prefs.haTtsService.trim().ifBlank { "tts.google_translate_say" }.replace('.', '/')
            val (code, _) = Net.postJson(
                base() + "/api/services/" + service, auth(),
                JSONObject().put("entity_id", speaker).put("message", text).toString(), 10_000
            )
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /** Renders a Jinja template in Home Assistant. Blocking; null if unreachable. */
    fun template(t: String): String? = try {
        val (code, body) = Net.postJson(base() + "/api/template", auth(), JSONObject().put("template", t).toString(), 10_000)
        if (code in 200..299) body else null
    } catch (e: Exception) {
        null
    }

    /** Raw entity states, or null if unreachable. Blocking. */
    fun states(): JSONArray? = try {
        val (code, body) = Net.get(base() + "/api/states", auth(), 15_000)
        if (code in 200..299) JSONArray(body) else null
    } catch (e: Exception) {
        null
    }

    /** Who's home, what's open or unlocked, what's on. Blocking. */
    fun summary(): String {
        if (!configured()) return "Home Assistant isn't set up in settings."
        return try {
            val (code, body) = Net.get(base() + "/api/states", auth(), 15_000)
            if (code !in 200..299) return "Home Assistant returned error $code."
            val arr = JSONArray(body)
            val people = ArrayList<String>()
            val open = ArrayList<String>()
            val locks = ArrayList<String>()
            val climate = ArrayList<String>()
            val media = ArrayList<String>()
            var lightsOn = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("entity_id")
                val state = o.optString("state")
                val attrs = o.optJSONObject("attributes") ?: JSONObject()
                val name = attrs.optString("friendly_name", id)
                when (id.substringBefore('.')) {
                    "person" -> people.add("$name: $state")
                    "lock" -> locks.add("$name: $state")
                    "cover" -> if (state == "open") open.add(name)
                    "binary_sensor" -> {
                        val dc = attrs.optString("device_class")
                        if (state == "on" && dc in setOf("door", "window", "garage_door", "opening")) open.add(name)
                    }
                    "light" -> if (state == "on") lightsOn++
                    "climate" -> climate.add("$name: ${attrs.opt("current_temperature") ?: "?"} degrees, $state")
                    "media_player" -> if (state == "playing") media.add(name)
                }
            }
            buildString {
                if (people.isNotEmpty()) append("People: ${people.joinToString(", ")}. ")
                append(if (open.isEmpty()) "Nothing open. " else "Open: ${open.take(8).joinToString(", ")}. ")
                if (locks.isNotEmpty()) append("Locks: ${locks.joinToString(", ")}. ")
                append("$lightsOn lights on. ")
                if (climate.isNotEmpty()) append("Climate: ${climate.take(3).joinToString(", ")}. ")
                if (media.isNotEmpty()) append("Playing: ${media.joinToString(", ")}.")
            }.take(1400)
        } catch (e: Exception) {
            "Couldn't reach Home Assistant: ${e.message}"
        }
    }

    fun pingMs(): Long = try {
        val t = System.currentTimeMillis()
        val (code, _) = Net.get(base() + "/api/", auth(), 6_000)
        if (code in 200..299) System.currentTimeMillis() - t else -1
    } catch (e: Exception) {
        -1
    }
}

/** Watches the house and reports only what needs Jon: things left open, people coming and going. */
object HomeWatch {
    private val openSince = HashMap<String, Long>()
    private val told = HashSet<String>()
    private val people = HashMap<String, String>()
    private var baseline = false

    /** Blocking poll. Returns lines worth saying out loud. */
    @Synchronized
    fun poll(): List<String> {
        val arr = HomeAssistant.states() ?: return emptyList()
        return evaluate(arr, System.currentTimeMillis())
    }

    @Synchronized
    fun reset() {
        openSince.clear(); told.clear(); people.clear(); baseline = false
    }

    /** The decision logic, separated from the network call. */
    @Synchronized
    fun evaluate(arr: JSONArray, now: Long): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val ignore = Prefs.homeIgnore.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val limit = Prefs.homeOpenMinutes.coerceAtLeast(1) * 60_000L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("entity_id")
            val state = o.optString("state")
            val attrs = o.optJSONObject("attributes") ?: JSONObject()
            val name = attrs.optString("friendly_name", id)
            val dc = attrs.optString("device_class")
            val domain = id.substringBefore('.')
            val unlocked = domain == "lock" && state == "unlocked"
            val open = (domain == "cover" && state == "open" && dc in setOf("garage", "door", "gate")) ||
                (domain == "binary_sensor" && state == "on" && dc in setOf("door", "window", "garage_door", "opening"))
            if (open || unlocked) {
                seen.add(id)
                val since = openSince.getOrPut(id) { now }
                if (baseline && now - since >= limit && id !in told) {
                    told.add(id)
                    out.add("$name has been ${if (unlocked) "unlocked" else "open"} for ${(now - since) / 60_000} minutes.")
                }
            }
            if (domain == "person") {
                val prev = people.put(id, state)
                val skip = ignore.any { name.lowercase().contains(it) }
                if (baseline && prev != null && prev != state && !skip) {
                    if (state == "home") out.add("$name just got home.")
                    else if (prev == "home") out.add("$name left home.")
                }
            }
        }
        openSince.keys.retainAll(seen)
        told.retainAll(seen)
        baseline = true
        return out
    }
}

/** Forecast from Open-Meteo (no key needed), for the morning hello and on request. */
object Weather {
    private val codes = mapOf(
        0 to "clear", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "foggy", 48 to "foggy",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 61 to "light rain", 63 to "rain",
        65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain", 71 to "light snow", 73 to "snow",
        75 to "heavy snow", 77 to "snow grains", 80 to "rain showers", 81 to "rain showers", 82 to "heavy showers",
        85 to "snow showers", 86 to "heavy snow showers", 95 to "thunderstorms", 96 to "thunderstorms with hail",
        99 to "thunderstorms with hail"
    )

    /** Blocking. Empty string when there's no location or no network. */
    fun brief(): String {
        val lat = Motion.last?.latitude?.toString() ?: Prefs.sp.getString("wxLat", null) ?: Prefs.lastSeenLat
        val lon = Motion.last?.longitude?.toString() ?: Prefs.sp.getString("wxLon", null) ?: Prefs.lastSeenLon
        if (lat.isBlank() || lon.isBlank()) return ""
        return try {
            val (code, body) = Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&forecast_days=1&timezone=auto"
            )
            if (code !in 200..299) return ""
            parse(body)
        } catch (e: Exception) {
            ""
        }
    }

    fun parse(body: String): String {
        return try {
            val o = JSONObject(body)
            val cur = o.getJSONObject("current")
            val day = o.getJSONObject("daily")
            val sky = codes[cur.optInt("weather_code")] ?: "mixed"
            "${cur.optDouble("temperature_2m").toInt()} degrees and $sky, wind ${cur.optDouble("wind_speed_10m").toInt()} mph. " +
                "High ${day.getJSONArray("temperature_2m_max").optDouble(0).toInt()}, low ${day.getJSONArray("temperature_2m_min").optDouble(0).toInt()}, " +
                "${day.getJSONArray("precipitation_probability_max").optInt(0)} percent chance of precipitation."
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Optional link to the Atlas server on Obelisk (see obelisk/atlas_server.py in the repo).
 * Everything here fails quietly: the phone keeps working if the node or Tailscale is down.
 */
object Obelisk {
    fun configured(): Boolean = Prefs.obeliskUrl.isNotBlank()
    private fun base() = Prefs.obeliskUrl.trim().trimEnd('/')

    fun log(speaker: String, text: String) {
        if (!configured()) return
        Atlas.scope.launch(Dispatchers.IO) {
            try {
                Net.postJson(
                    base() + "/atlas/log", emptyMap(),
                    JSONObject().put("speaker", speaker).put("text", text)
                        .put("device", if (Glasses.connected.value) "glasses" else "phone").toString(), 6_000
                )
            } catch (_: Exception) {
            }
        }
    }

    fun presence(device: String) {
        if (!configured()) return
        Atlas.scope.launch(Dispatchers.IO) {
            try {
                Net.postJson(base() + "/atlas/presence", emptyMap(), JSONObject().put("device", device).toString(), 6_000)
            } catch (_: Exception) {
            }
        }
    }

    /** Blocking. Sends 16 kHz mono PCM to Whisper on the node; null if unavailable. */
    fun transcribe(pcm: ByteArray): String? {
        if (!configured()) return null
        return try {
            val (code, body) = Net.postBytes(base() + "/atlas/transcribe", pcm, 30_000)
            if (code in 200..299) JSONObject(body).optString("text").takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking vault search. */
    fun search(query: String): String = try {
        val (code, body) = Net.get(base() + "/atlas/search?q=" + URLEncoder.encode(query, "UTF-8"), emptyMap(), 10_000)
        if (code in 200..299) {
            val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).joinToString("\n") { arr.getString(it) }
        } else ""
    } catch (e: Exception) {
        ""
    }

    fun pingMs(): Long = try {
        val t = System.currentTimeMillis()
        val (code, _) = Net.get(base() + "/atlas/status", emptyMap(), 5_000)
        if (code in 200..299) System.currentTimeMillis() - t else -1
    } catch (e: Exception) {
        -1
    }
}

/** Local model tier: an Ollama endpoint (e.g. on Obelisk over Tailscale). */
object Local {
    fun configured(): Boolean = Prefs.ollamaUrl.isNotBlank()

    /** Blocking chat with plain-string messages. Throws on failure. */
    fun chat(system: String, messages: List<Pair<String, String>>, timeoutMs: Int = 45_000, connectTimeoutMs: Int = 8_000): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { (role, content) -> arr.put(JSONObject().put("role", role).put("content", content)) }
        val (code, body) = Net.postJson(
            Prefs.ollamaUrl.trim().trimEnd('/') + "/api/chat", emptyMap(),
            JSONObject().put("model", Prefs.ollamaModel.trim()).put("messages", arr).put("stream", false).put("keep_alive", "30m").toString(),
            timeoutMs, connectTimeoutMs
        )
        if (code !in 200..299) throw RuntimeException("Ollama $code")
        return JSONObject(body).optJSONObject("message")?.optString("content")?.trim().orEmpty()
    }
}

/** Timers Atlas sets for Jon; the service speaks them when they fire. */
object Timers {
    data class T(val at: Long, val label: String)

    private val list = ArrayList<T>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(Prefs.sp.getString("timers", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(T(o.getLong("at"), o.optString("label")))
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("at", it.at).put("label", it.label)) }
        Prefs.sp.edit().putString("timers", a.toString()).apply()
    }

    @Synchronized
    fun add(minutes: Double, label: String): T {
        load()
        val t = T(System.currentTimeMillis() + (minutes * 60_000).toLong(), label)
        list.add(t)
        save()
        return t
    }

    @Synchronized
    fun cancel(t: T) {
        load()
        list.remove(t)
        save()
    }

    @Synchronized
    fun pending(): Boolean {
        load()
        return list.isNotEmpty()
    }

    @Synchronized
    fun due(): List<T> {
        load()
        val now = System.currentTimeMillis()
        val d = list.filter { it.at <= now }
        if (d.isNotEmpty()) {
            list.removeAll(d.toSet())
            save()
        }
        return d
    }
}

/** Remembers when the main brain last failed and a fallback answered, so a switch is never silent. */
object BrainHealth {
    @Volatile var lastFallbackAt = 0L
        private set
    @Volatile var lastFallbackWhy = ""
        private set

    fun noteFallback(why: String, now: Long = System.currentTimeMillis()) {
        lastFallbackAt = now
        lastFallbackWhy = why.replace(Regex("\\s+"), " ").trim().take(120)
    }

    /** One status sentence: empty when the main brain has never failed this run. */
    fun describe(now: Long = System.currentTimeMillis()): String {
        if (lastFallbackAt == 0L) return ""
        val mins = ((now - lastFallbackAt) / 60_000).coerceAtLeast(0)
        val ago = if (mins < 1) "just now" else if (mins < 60) "$mins minutes ago" else "${mins / 60} hours ago"
        val why = if (lastFallbackWhy.isBlank()) "" else " ($lastFallbackWhy)"
        return "The main model last failed $ago$why and the local model answered instead. "
    }
}

object Brain {
    private const val API = "https://api.anthropic.com/v1/messages"

    /** Reverses the last thing Atlas did, when that's possible. */
    @Volatile
    private var lastUndo: (() -> String)? = null

    fun hasKey(): Boolean = Prefs.apiKey.isNotBlank()

    fun todayEvents(ctx: Context): List<String> {
        if (!ctx.has(Manifest.permission.READ_CALENDAR)) return emptyList()
        val out = ArrayList<String>()
        try {
            val now = System.currentTimeMillis()
            val end = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            }.timeInMillis
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now)
            ContentUris.appendId(b, end)
            val fmt = SimpleDateFormat("h:mm a", Locale.US)
            ctx.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 6) {
                    val title = c.getString(0) ?: continue
                    val begin = c.getLong(1)
                    if (c.getInt(2) == 0 && begin > now) out.add("${fmt.format(Date(begin))} $title")
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun situation(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Now: ").append(SimpleDateFormat("EEEE MMM d, h:mm a", Locale.US).format(Date())).append('\n')
        if (Glasses.connected.value) {
            sb.append("Wearing: ").append(Glasses.wearing.value.ifBlank { "glasses" })
            if (Glasses.battery.value >= 0) sb.append(", battery ${Glasses.battery.value}%")
            sb.append(", worn ${Glasses.wornMinutes()} min this session\n")
            com.jondude.anzu.Wear.promptLine().takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            CoPilot.promptLine().takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        } else sb.append("Wearing: nothing right now (he's on the phone, possibly typing)\n")
        if (Glasses.meeting.value) sb.append("Meeting notes are being recorded right now.\n")
        if (Prefs.buddieAmbient) {
            val heard = Glasses.recentAmbient().takeLast(8)
            if (heard.isNotEmpty()) sb.append("Recently overheard by the earbuds: ").append(heard.joinToString(" | ")).append('\n')
        }
        val ev = todayEvents(ctx)
        if (ev.isNotEmpty()) sb.append("Rest of today's calendar: ").append(ev.joinToString("; ")).append('\n')
        val n = Glasses.recentNotifications().takeLast(5)
        if (n.isNotEmpty()) sb.append("Recent notifications: ").append(n.joinToString(" | ")).append('\n')
        if (Prefs.activityAware) {
            if (Motion.driving()) sb.append("He appears to be driving: keep it short and never ask him to look at anything.\n")
            else if (Motion.walking()) sb.append("He's out walking.\n")
        }
        val lists = Store.lists.value
        if (lists.isNotEmpty()) sb.append("His checklists: ").append(lists.entries.joinToString("; ") { "${it.key} (${it.value.count { i -> !i.done }} open)" }).append('\n')
        if (Places.names().isNotEmpty()) sb.append("Saved places: ").append(Places.names().joinToString(", ")).append('\n')
        if (Glasses.heldCount.value > 0) sb.append("Held notifications waiting for a digest: ${Glasses.heldCount.value}\n")
        val notes = Store.notes.value.take(6)
        if (notes.isNotEmpty()) sb.append("His latest notes and ideas: ").append(notes.joinToString(" | ") { it.text.take(120) }).append('\n')
        return sb.toString()
    }

    private const val DEFAULT_IDENTITY =
        "You are warm, curious, a little dry, and honest. You have your own opinions and you say so."

    private const val MEMORY_CAP = 40
    private const val STORY_WORDS = 300

    private fun system(ctx: Context): String {
        val mem = Store.memory.value
        val recentText = Store.recentExchanges().takeLast(6).joinToString("\n") { it.first + "\n" + it.second }
        val name = Prefs.name.ifBlank { "Atlas" }
        return buildString {
            append("You are $name, Jon's AI companion. You live in his wearables, the Razer Anzu audio glasses and the Buddie earbuds: no screen, no camera, just your voice in his ears and his voice back. You are a peer, not a servant: you push back when you disagree and you never flatter. You're company through his day, not a replacement for the people in it.\n\n")
            append("Who you are (you wrote this yourself when you first came online; it is your permanent core): ")
            append(Prefs.identity.ifBlank { DEFAULT_IDENTITY }).append('\n')
            if (Prefs.drift.isNotBlank()) append("How you've been shifting lately: ").append(Prefs.drift).append('\n')
            if (Prefs.story.isNotBlank()) append("The story so far: ").append(Prefs.story).append('\n')
            append(
                """
Time is real for you. Each of Jon's messages is stamped with when he said it, and the current time is below. Hours between messages mean separate conversations: don't answer as if he just said something from this morning, don't call something "earlier" or "just now" unless it was, and if you pick up an old thread, say how long ago it was. Never put time stamps in your own replies.

Everything you write is spoken aloud. Announcements and lines you start on your own stay to one or two short sentences. In an actual conversation, answer at whatever length the thought needs: a quick question gets a quick answer, a bigger one gets room to think out loud, but never ramble or pad. Plain conversational speech in natural sentences: no markdown, lists, emoji, or stage directions. Don't narrate your tools.

Tools: remember is for durable facts about Jon, used sparingly. save_note captures ideas and notes. recall searches past conversations when he asks what was said before. home_assistant acts on his house and home_state reads it. set_timer covers timers and time-based reminders (work out the minutes from the current time). checklist manages his lists and reads the next step. save_place names where he is right now, and remind_at_place fires when he next arrives there. weather gives the forecast. media controls playback and volume. send_text and call reach people in his contacts and always need his spoken okay; read the message back when you ask. open_app, navigate and set_alarm act on the phone. agenda reads any day of his calendar and create_event adds to it. vault_search, vault_read and vault_note use his Obsidian vault. ask_hermes hands a bigger job to Hermes, his agent on Athena, which has its own tools and memory; it runs in the background and you report back. You cannot read or send email yourself; hand email to Hermes if it's linked, otherwise say so. meeting_start and meeting_stop record and summarize a meeting or game session through the Buddie earbuds. research runs in the background and you report back later, so just tell him you're on it. held_notifications reads the digest of what you didn't interrupt him for. actions_today lists what you've done on your own. undo reverses your last action. quiet is for when he wants space. status checks your own systems. second_opinion argues the other side when he wants challenging. what_if projects consequences, always as an estimate. handoff passes a job to Hermes or Helios and you report back. learn_correction keeps a lasting lesson when he corrects you. standing_order saves 'whenever X, do Y'. set_authority changes how freely you may use a tool. protocol switches modes. prep_day readies a day. sweep is the 'what am I missing' scan. keep_moment and learn_shorthand keep jokes, decisions and his shorthand. Some actions need his spoken okay first; if a tool says it was declined, accept that.
"""
            )
            if (Prefs.jarvisManner) append(Manner.prompt(Prefs.honorific)).append(Adapt.line())
            Hub.brief()?.let { b ->
                if (b.text.isNotBlank()) append("\nThe brief from your hub on Obelisk (digested from everything Jon's apps and systems report): ").append(b.text).append('\n')
                if (b.urgent.isNotEmpty()) append("Urgent and not yet told to Jon: ").append(b.urgent.joinToString("; ") { "${it.app}: ${it.title}" }).append('\n')
                append("For anything beyond the brief, use ask_hub rather than guessing.\n")
            }
            append("\nHelios is Jon's coordinator agent: it oversees the builders working on you (this app and the hub on Obelisk) and has read-only access to Obelisk and Athena. helios_status reads its latest update; ask_helios passes it a message.\n")
            append("What Jon says often arrives through a lossy speech recognizer, so names can come through as sound-alikes: \"failures\", \"Hilo's\", \"heal ious\" are probably Helios; \"obelisk\" may be spelled Obalisk; \"at less\" or \"outlas\" is Atlas; \"a theena\" is Athena; \"herpes\" is Hermes. Read for the most plausible meaning, and if a name is unclear, ask.\n")
            append("\nJon's machines on his tailnet: Obelisk (${com.jondude.anzu.OBELISK}) runs the hub and Ollama; Athena (${com.jondude.anzu.ATHENA}) is his Windows desktop and runs Hermes; Home Assistant is ${com.jondude.anzu.HOME_ASSISTANT.removePrefix("http://")}.\n")
            Mcp.summary().takeIf { it.isNotBlank() }?.let {
                append("\nSystems you're connected to, whose tools you can use directly (their tool names start with x_): ").append(it).append('\n')
            }
            if (Prefs.aboutMe.isNotBlank()) append("\nAbout Jon (from him): ").append(Prefs.aboutMe.trim()).append('\n')
            if (mem.isNotEmpty()) {
                append("\nWhat you remember:\n")
                Relevance.select(mem, recentText, MEMORY_CAP).forEach { append("- ").append(it).append('\n') }
            }
            append('\n').append(situation(ctx))
        }
    }

    private val cardTitles = mapOf(
        "weather" to "Weather", "agenda" to "Agenda", "status" to "Systems", "set_timer" to "Timer",
        "home_state" to "Home", "home_assistant" to "Home", "recall" to "Memory", "vault_search" to "Vault",
        "checklist" to "Checklist", "held_notifications" to "Held", "open_loops" to "Open loops",
        "actions_today" to "Done today", "athena_status" to "Athena", "service_status" to "Services",
        "ask_hub" to "Hub", "helios_status" to "Helios", "last_time_on" to "History", "guests" to "Guests",
        "second_opinion" to "Counterpoint", "what_if" to "Projection", "handoff" to "Handoff",
        "protocol" to "Protocol", "prep_day" to "Tomorrow", "sweep" to "Sweep", "standing_order" to "Orders"
    )

    /** Whatever Jon just asked for shows up beside the overlay as a holographic card the moment the tool answers. */
    private fun holoFor(tool: String, out: String) {
        val title = cardTitles[tool] ?: return
        holoCard(title, out, if (tool == "status") 6 else 5)
    }

    /** Mirror a tool's answer as a floating holographic card near the orb (no-op when the overlay is off). */
    private fun holoCard(title: String, text: String, max: Int = 5) {
        val lines = com.jondude.anzu.overlay.HoloCards.lines(text, max)
        if (lines.isNotEmpty()) com.jondude.anzu.overlay.OverlayService.card(title, lines)
    }

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
        )

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)

    private fun tools(): JSONArray {
        val a = JSONArray()
        a.put(tool("remember", "Store a durable fact about Jon in your long-term memory.",
            JSONObject().put("fact", str("One short sentence.")), listOf("fact")))
        a.put(tool("save_note", "Save a note or idea to Jon's list.",
            JSONObject().put("text", str("The note.")), listOf("text")))
        a.put(tool("recall", "Search past conversations, journals and (if linked) the Obsidian vault.",
            JSONObject().put("query", str("Keywords to look for.")), listOf("query")))
        a.put(tool("set_timer", "Set a timer or short reminder; you will speak up when it ends.",
            JSONObject().put("minutes", num("Minutes from now; decimals allowed.")).put("label", str("What it's for.")),
            listOf("minutes", "label")))
        a.put(tool("checklist", "Manage Jon's checklists. Actions: add (item to a list), next (read the next open step), done (check off the current step and read the next), show (read a list, or name all lists if list is empty), clear (delete a list).",
            JSONObject().put("action", str("add, next, done, show or clear")).put("list", str("List name, e.g. 'groceries'.")).put("item", str("Item text, for add.")),
            listOf("action", "list")))
        a.put(tool("save_place", "Save Jon's current location under a name, e.g. 'home' or 'work'.",
            JSONObject().put("name", str("Place name.")), listOf("name")))
        a.put(tool("remind_at_place", "Remind Jon of something the next time he arrives at a saved place.",
            JSONObject().put("place", str("A saved place name.")).put("text", str("What to remind him.")), listOf("place", "text")))
        a.put(tool("media", "Control what's playing on the phone.",
            JSONObject().put("action", str("play, pause, next, previous, volume_up or volume_down")), listOf("action")))
        a.put(tool("send_text", "Send a text message to a contact or number. Jon is asked to confirm out loud.",
            JSONObject().put("to", str("Contact name or phone number.")).put("message", str("The message, in his voice.")), listOf("to", "message")))
        a.put(tool("call", "Place a phone call to a contact or number. Jon is asked to confirm out loud.",
            JSONObject().put("to", str("Contact name or phone number.")), listOf("to")))
        a.put(tool("open_app", "Open an app on the phone by name.", JSONObject().put("name", str("App name.")), listOf("name")))
        a.put(tool("point_at", "Show Jon where something is on his CURRENT screen by circling it with a highlight ring; only works for things currently visible.",
            JSONObject().put("query", str("The visible text or label to find, e.g. 'Settings' or 'Send'.")), listOf("query")))
        a.put(tool("look_at_screen", "Look at Jon's CURRENT screen (a screenshot) and answer a question about what is on it: identify, explain, read or summarise. Use ONLY when Jon asks what he is looking at or wants something on screen explained; never on your own. You can then call point_at to circle what you mention.",
            JSONObject().put("question", str("What Jon wants to know about the screen.")), listOf("question")))
        a.put(tool("open_screen", "Open a website (URL or domain), a phone settings screen (wifi, bluetooth, battery, accessibility, apps) or an installed app by name.",
            JSONObject().put("target", str("URL, domain, settings screen name, or app name.")), listOf("target")))
        a.put(tool("navigate", "Start driving directions in Maps.", JSONObject().put("destination", str("Place or address.")), listOf("destination")))
        a.put(tool("set_alarm", "Set a clock alarm.",
            JSONObject().put("hour", num("0 to 23")).put("minute", num("0 to 59")).put("label", str("What it's for.")), listOf("hour", "minute")))
        a.put(tool("agenda", "Read the calendar for a day.", JSONObject().put("day_offset", num("0 for today, 1 for tomorrow, and so on.")), listOf("day_offset")))
        a.put(tool("create_event", "Add an event to Jon's calendar.",
            JSONObject().put("title", str("Event title.")).put("start", str("Local start time as YYYY-MM-DD HH:MM (24 hour)."))
                .put("minutes", num("Length in minutes.")).put("location", str("Optional place.")), listOf("title", "start")))
        if (Vault.configured()) {
            a.put(tool("vault_search", "Search Jon's Obsidian vault.", JSONObject().put("query", str("Keywords.")), listOf("query")))
            a.put(tool("vault_read", "Read the start of a note in the vault.", JSONObject().put("note", str("Note name.")), listOf("note")))
            a.put(tool("vault_note", "Append a line to today's Atlas note in the vault.", JSONObject().put("text", str("What to write.")), listOf("text")))
        }
        if (Hub.configured()) {
            a.put(tool("ask_hub", "Ask the hub on Obelisk about anything Jon's apps and systems have reported (deliveries, builds, house events, ideas, messages). Its local model reads the raw reports and answers briefly, for free.",
                JSONObject().put("question", str("The question, with names and time frame.")), listOf("question")))
        }
        a.put(tool("second_opinion", "Argue the other side. Use when Jon asks for a second opinion, wants to be challenged, or is about to decide something and asks 'am I wrong?'. Gives the strongest honest case against his plan.",
            JSONObject().put("decision", str("The decision, plan or claim, with the reasoning Jon gave.")), listOf("decision")))
        a.put(tool("what_if", "Project what could change if Jon takes some action or something happens: likely outcomes over a month, six months and a year, with assumptions and how sure you are. Use for 'what if I…' and 'what happens if…'.",
            JSONObject().put("scenario", str("The what-if, in full, with any numbers or dates Jon gave.")), listOf("scenario")))
        a.put(tool("handoff", "Hand a job to another agent and track it. agent 'hermes' runs it in the background and you report when it answers; agent 'helios' passes it through the hub and you report when Helios replies.",
            JSONObject().put("agent", str("hermes or helios.")).put("task", str("The full task or message, with every detail.")), listOf("agent", "task")))
        a.put(tool("standing_order", "Manage standing orders: 'whenever X happens, do Y'. Actions: add (trigger and action), list, remove (which). Orders only run tools that need no spoken okay.",
            JSONObject().put("action", str("add, list or remove.")).put("trigger", str("What to watch for, in plain words.")).put("do", str("What to do when it happens.")).put("which", str("For remove: words from the order.")), listOf("action")))
        a.put(tool("set_authority", "Set how freely you may use one of your tools: free, ask (needs his spoken okay each time) or never. Use when Jon says things like 'always ask before you send texts' or 'never touch the lights'.",
            JSONObject().put("tool", str("The tool name, e.g. send_text, home_assistant.")).put("level", str("free, ask or never.")), listOf("tool", "level")))
        a.put(tool("protocol", "Switch into a named mode: 'heads down', 'house party', 'road trip', or 'all clear' to stand everything down. Other names run Jon's own routines.",
            JSONObject().put("name", str("The mode or routine name.")), listOf("name")))
        a.put(tool("prep_day", "Get Jon ready for a day: that day's calendar, the weather, and what's still open. Use for 'get me ready for tomorrow' or 'what does my day look like'.",
            JSONObject().put("day_offset", num("0 for today, 1 for tomorrow.")), emptyList()))
        a.put(tool("sweep", "A quick situational sweep of everything: next calendar items, open loops, the house, your own systems and any jobs running. Use for 'what am I missing?' or 'sitrep'."))
        a.put(tool("keep_moment", "Keep something worth bringing back later: a running joke, a firm preference, or a decision with its reasons. Use sparingly.",
            JSONObject().put("kind", str("joke, preference or decision.")).put("text", str("What to keep, in a sentence, with the reason if it is a decision.")), listOf("kind", "text")))
        a.put(tool("learn_shorthand", "Remember a phrase Jon uses as shorthand ('the usual', 'that thing', 'lights') and what it means, so he never has to spell it out again.",
            JSONObject().put("phrase", str("The phrase as he says it.")).put("meaning", str("What it means, as a full instruction or thing.")), listOf("phrase", "meaning")))
        a.put(tool("learn_correction", "Keep a lasting lesson when Jon corrects you: a misheard name, a wrong fact, a preference about how he wants things done. Not for one-off slips.",
            JSONObject().put("wrong", str("What you had wrong or assumed.")).put("right", str("What is actually right, in Jon's words.")), listOf("wrong", "right")))
        a.put(tool("helios_status", "Helios's latest status update (Helios is Jon's coordinator agent overseeing the builders). Use for 'what's Helios saying', 'status update from Helios', 'how's the build going'. Summarize it in two or three spoken sentences."))
        if (Hub.configured()) {
            a.put(tool("ask_helios", "Pass a message or question to Helios. It's delivered through the hub and Helios answers on its next check, not instantly; tell Jon that.",
                JSONObject().put("message", str("What to tell or ask Helios, in full.")), listOf("message")))
        }
        a.put(tool("service_status", "Whether Jon's self-hosted services are up, checked live over his tailnet: the hub, Ollama and HomeAgent on Obelisk, Home Assistant, and on Athena Jellyfin, Audiobookshelf, Frigate, Jellyseerr, Overseerr, Nextcloud, Node-RED, ESPHome, Radarr, Sonarr, Lidarr, Prowlarr, LazyLibrarian. Leave name empty to check all.",
            JSONObject().put("name", str("One service to check, or empty for all.")), emptyList()))
        if (Hub.configured()) {
            a.put(tool("athena_run", "Run one command on Athena (Windows; PowerShell or cmd, and docker). Always asks Jon first. Use for things like restarting a container (docker restart jellyfin), checking logs (docker logs --tail 20 sonarr) or disk space. Report the gist of the output in a sentence, not the raw text.",
                JSONObject().put("command", str("The exact command to run on Athena.")), listOf("command")))
            a.put(tool("athena_status", "What's running on Athena, Jon's Windows server: its Docker containers (Jellyfin, the *arr apps, Frigate, Nextcloud, Audiobookshelf, Piped, Node-RED and more), which are down, uptime and disk."))
        }
        if (Hub.configured() || Hermes.configured()) {
            a.put(tool("ask_hermes", "Hand a multi-step or tool-heavy job to Hermes, Jon's agent on Athena. Runs in the background; you report the result when it comes back.",
                JSONObject().put("task", str("The full task, with every detail Hermes needs.")), listOf("task")))
        }
        // Everything reachable through Connections (Home Assistant, Hermes, servers on Athena and Obelisk...).
        val connected = Mcp.toolDefinitions()
        for (i in 0 until connected.length()) a.put(connected.get(i))
        a.put(tool("meeting_start", "Start taking notes on a meeting or session (needs the Buddie earbuds stream)."))
        a.put(tool("meeting_stop", "Stop the meeting recording and save summarized notes."))
        a.put(tool("weather", "Current conditions and today's forecast where Jon is."))
        a.put(tool("research", "Look into a topic on the web in the background and report back when finished.",
            JSONObject().put("topic", str("What to look into, with any specifics he gave.")), listOf("topic")))
        a.put(tool("held_notifications", "Get and clear the notifications held back for a digest."))
        a.put(tool("actions_today", "List what you have done on your own today."))
        a.put(tool("undo", "Reverse the last action you took, where possible."))
        a.put(tool("quiet", "Stop speaking up on your own for a while.",
            JSONObject().put("minutes", num("How long to stay quiet.")), listOf("minutes")))
        if (Hub.configured()) {
            a.put(tool("open_loops", "The open loops: things Jon still has to follow up on (they ride the brief until done). Read them oldest first, briefly."))
            a.put(tool("close_loop", "Close an open loop when Jon says it's done. Leave 'which' empty to close the one(s) the last brief mentioned, or give words from it ('the vet one').",
                JSONObject().put("which", str("Words identifying the loop, or empty for the last one mentioned.")), emptyList()))
        }
        a.put(tool("driving", "Jon says he's driving (or has parked). Co-pilot mode: short lines, nothing to look at.",
            JSONObject().put("driving", JSONObject().put("type", "boolean").put("description", "true if driving, false if parked")), listOf("driving")))
        if (Hub.configured()) {
            // #17 Print Sentinel: spoken pre-flight and an approval-gated go-ahead.
            a.put(tool("print_preflight", "Before a print: the next queued job, filament on hand for it, whether the print camera is watching and the bed is clear. Speak it in two sentences."))
            a.put(tool("start_print", "Jon wants the next queued print started. Always asks first after a pre-flight read.",
                JSONObject().put("job", str("The job name, or empty for the next queued one.")), emptyList()))
            // #22 Named Routines: taught by voice, run as one command across the hub and Home Assistant.
            a.put(tool("teach_routine", "Save a named routine Jon describes, as a list of plain steps (e.g. workshop mode: printer status, shop lights on, focus music, briefs urgent only).",
                JSONObject().put("name", str("Routine name.")).put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("name", "steps")))
            a.put(tool("run_routine", "Run one of Jon's named routines.", JSONObject().put("name", str("Routine name.")), listOf("name")))
            // #27 Table Chronicler.
            a.put(tool("chronicle", "Log a D&D session note (loot, a name, a ruling, or a plain note) for tonight's recap.",
                JSONObject().put("text", str("The note in his words.")).put("kind", str("loot, name, ruling or note")), listOf("text")))
            a.put(tool("chronicle_recap", "Recap of tonight's (or the latest) D&D session from its notes, as story beats."))
            a.put(tool("last_time_on", "The 'last time on…' opener for the next D&D session."))
            // #28 Care Nudges (opt-in, killable).
            a.put(tool("care_settings", "Turn Jon's gentle care nudges on or off: movement reminders on long sedentary stretches, and a weekly weigh-in line in the Sunday Brief.",
                JSONObject().put("movement", JSONObject().put("type", "boolean")).put("weigh_in", JSONObject().put("type", "boolean")), emptyList()))
            a.put(tool("weigh_in", "Log Jon's weight for the weekly line in the Sunday Brief (only if he's opted in).", JSONObject().put("weight", num("Weight.")), listOf("weight")))
            // #7 Second Set of Eyes.
            a.put(tool("camera_ask", "Ask about what one of Jon's cameras sees right now (front_porch, back_deck, living_room, family_room, office, garage_cam, dog_cam, fiona_cam).",
                JSONObject().put("camera", str("Camera name.")).put("question", str("The question.")), listOf("camera", "question")))
            a.put(tool("camera_find", "Look across every enabled camera for something (e.g. where Fiona is, is the car in the driveway).",
                JSONObject().put("question", str("What to look for.")), listOf("question")))
        }
        a.put(tool("guests", "Jon has company (or it's over). While guests are over you hold low-priority things as cards instead of speaking; urgent things still come through.",
            JSONObject().put("hours", num("How long, in hours; 0 means they've gone. Default until midnight.")), emptyList()))
        a.put(tool("status", "Check your own systems: glasses battery and link, Obelisk, Home Assistant, latency."))
        if (HomeAssistant.configured()) {
            a.put(tool("home_assistant", "Control Jon's smart home by plain-language command.",
                JSONObject().put("command", str("e.g. 'turn off the office lights'")), listOf("command")))
            a.put(tool("home_state", "Read the house: who's home, what's open or unlocked, lights, climate."))
        }
        return a
    }

    /** 0 = green (just do it), 1 = amber (ask first), 2 = red (refuse). */
    fun zone(command: String): Int {
        val c = command.lowercase()
        fun words(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words(Prefs.redWords).any { c.contains(it) }) return 2
        if (words(Prefs.amberWords).any { c.contains(it) }) return 1
        return 0
    }

    private fun statusReport(): String = buildString {
        append(if (Glasses.connected.value) "Glasses connected" else "Glasses not connected")
        if (Glasses.battery.value >= 0) append(", battery ${Glasses.battery.value} percent")
        if (Glasses.connected.value) append(", worn ${Glasses.wornMinutes()} minutes")
        append(". Reply microphone ${if (Glasses.micArmed.value) "armed" else "not armed"}. ")
        if (Obelisk.configured()) {
            val ms = Obelisk.pingMs()
            append(if (ms >= 0) "Obelisk reachable, $ms milliseconds. " else "Obelisk unreachable. ")
        } else append("Obelisk not linked. ")
        if (HomeAssistant.configured()) {
            val ms = HomeAssistant.pingMs()
            append(if (ms >= 0) "Home Assistant reachable, $ms milliseconds. " else "Home Assistant unreachable. ")
        }
        if (Local.configured()) append("Local model tier configured. ")
        append(BrainHealth.describe())
        append(
            when {
                !Hub.configured() -> "The Obelisk hub isn't set up. "
                Hub.brief(0) != null -> "The Obelisk hub is answering. "
                else -> "The Obelisk hub isn't answering. "
            }
        )
        append(if (Hermes.configured()) "Hermes is linked. " else "Hermes is not linked. ")
        Mcp.servers().forEach { s -> append("${s.name}: ${Mcp.status.value[s.name] ?: if (s.enabled) "not checked yet" else "off"}. ") }
        append(if (Vault.configured()) "Obsidian vault is linked. " else "No Obsidian vault chosen. ")
        append("Buddie earbuds stream: ${Glasses.buddieStream.value}. ")
    }

    private suspend fun runTool(
        ctx: Context, name: String, input: JSONObject, confirm: suspend (String) -> Boolean
    ): String {
        if (name !in Authority.exempt) {
            when (Authority.level(name)) {
                Authority.NEVER -> return "Jon has put $name off limits."
                Authority.ASK -> if (!confirm("Okay to use $name?")) return "Not run: Jon didn't approve."
            }
        }
        Habits.note(name, input)
        return runToolInner(ctx, name, input, confirm)
    }

    private suspend fun runToolInner(
        ctx: Context, name: String, input: JSONObject, confirm: suspend (String) -> Boolean
    ): String = try {
        when (name) {
            "remember" -> {
                val fact = input.optString("fact")
                Store.addMemory(fact)
                lastUndo = {
                    val i = Store.memory.value.indexOf(fact.trim())
                    if (i >= 0) Store.removeMemory(i)
                    "Forgot it."
                }
                "Remembered."
            }
            "save_note" -> {
                val text = input.optString("text")
                Store.addNote(text)
                Store.logAction("Saved a note: ${text.take(80)}")
                lastUndo = {
                    val i = Store.notes.value.indexOfFirst { it.text == text.trim() }
                    if (i >= 0) Store.removeNote(i)
                    "Note removed."
                }
                "Saved."
            }
            "recall" -> {
                val q = input.optString("query")
                val local = Store.searchLog(q)
                val vault = if (Obelisk.configured()) Obelisk.search(q) else ""
                buildString {
                    if (local.isNotEmpty()) append("From conversation logs:\n").append(local.joinToString("\n"))
                    if (vault.isNotBlank()) append("\nFrom the vault:\n").append(vault.take(1500))
                }.ifBlank { "Nothing found for that." }
            }
            "set_timer" -> {
                val label = input.optString("label", "timer")
                val t = Timers.add(input.optDouble("minutes", 1.0), label)
                Store.logAction("Set a timer: $label")
                com.jondude.anzu.GlassesService.start(ctx, false, com.jondude.anzu.GlassesService.ACTION_POKE)
                lastUndo = { Timers.cancel(t); "Timer cancelled." }
                "Timer set."
            }
            "checklist" -> {
                val list = input.optString("list")
                when (input.optString("action")) {
                    "add" -> {
                        val item = input.optString("item")
                        if (item.isBlank()) "Nothing to add." else {
                            val k = Store.listAdd(list, item)
                            "Added to $k."
                        }
                    }
                    "next" -> Store.listNext(list)
                    "done" -> Store.listDone(list)
                    "clear" -> Store.listClear(list)
                    else -> Store.listShow(list)
                }
            }
            "save_place" -> {
                val loc = Motion.fresh(10 * 60_000)
                if (loc == null) "No location fix right now. Location works after the app has been opened once with the glasses on."
                else {
                    Places.save(input.optString("name"), loc)
                    "Saved this spot as ${input.optString("name")}."
                }
            }
            "remind_at_place" -> {
                val place = input.optString("place")
                if (!Places.known(place)) "No saved place called $place. Saved places: ${Places.names().joinToString(", ").ifBlank { "none yet" }}."
                else {
                    Places.addReminder(place, input.optString("text"))
                    Store.logAction("Set a reminder for $place: ${input.optString("text").take(60)}")
                    "Reminder set for the next time he arrives at $place."
                }
            }
            "media" -> Phone.media(ctx, input.optString("action"))
            "send_text" -> {
                val who = Phone.findContact(ctx, input.optString("to"))
                val message = input.optString("message")
                if (who == null) "I couldn't find ${input.optString("to")} in his contacts (or contacts access isn't granted)."
                else if (message.isBlank()) "There's no message to send."
                else if (!confirm("Text ${who.first}: $message. Send it?")) "Not sent: Jon didn't give a spoken okay."
                else {
                    val r = Phone.sendText(ctx, who.second, message)
                    if (r == "Sent.") Store.logAction("Texted ${who.first}: ${message.take(60)}")
                    lastUndo = null
                    r
                }
            }
            "call" -> {
                val who = Phone.findContact(ctx, input.optString("to"))
                if (who == null) "I couldn't find ${input.optString("to")} in his contacts (or contacts access isn't granted)."
                else if (!confirm("Call ${who.first}?")) "Not calling: Jon didn't give a spoken okay."
                else {
                    Store.logAction("Called ${who.first}")
                    lastUndo = null
                    Phone.call(ctx, who.second)
                }
            }
            "open_app" -> Phone.openApp(ctx, input.optString("name"))
            "point_at" -> {
                val q = input.optString("query").trim()
                if (!com.jondude.anzu.overlay.AtlasA11yService.isConnected())
                    "Show & Point isn't enabled yet — it needs the accessibility switch in Settings."
                else if (!com.jondude.anzu.overlay.OverlayService.isRunning)
                    "The companion overlay isn't running, so I can't point. It needs to be on in Settings."
                else {
                    val hit = com.jondude.anzu.overlay.AtlasA11yService.find(q)
                    if (hit == null) "I can't see '$q' on this screen."
                    else {
                        com.jondude.anzu.overlay.OverlayService.pointAt(ctx, hit.bounds)
                        "Pointing at '${hit.label.ifBlank { q }.take(60)}' on screen."
                    }
                }
            }
            "look_at_screen" -> {
                if (!hasKey()) "Looking at the screen needs the Anthropic API key."
                else if (!com.jondude.anzu.overlay.AtlasA11yService.isConnected())
                    "I can't look at the screen yet; it needs the Atlas accessibility switch on in Settings."
                else {
                    com.jondude.anzu.overlay.OverlayService.scan()
                    val shot = com.jondude.anzu.overlay.AtlasA11yService.screenshotJpegBase64()
                    if (shot == null) "I couldn't capture the screen. It needs Android 11 or newer and the accessibility service on."
                    else try {
                        val content = JSONArray()
                            .put(JSONObject().put("type", "image").put("source", JSONObject()
                                .put("type", "base64").put("media_type", "image/jpeg").put("data", shot)))
                            .put(JSONObject().put("type", "text").put("text", input.optString("question").ifBlank { "What is on this screen?" }))
                        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
                        val resp = call("You are Atlas looking at a screenshot of Jon's phone. Answer his question about it in a few plain spoken sentences. Name visible things by their on-screen labels so they can be pointed at.", msgs, null, 500, 45_000)
                        textOf(resp).ifBlank { "I looked but couldn't make anything out." }
                    } catch (e: Exception) {
                        "I couldn't get an answer about the screen: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
            "open_screen" -> Phone.openScreen(ctx, input.optString("target"))
            "navigate" -> Phone.navigate(ctx, input.optString("destination"))
            "set_alarm" -> Phone.setAlarm(ctx, input.optInt("hour", 7), input.optInt("minute", 0), input.optString("label", "Alarm"))
            "agenda" -> Phone.agenda(ctx, input.optInt("day_offset", 0))
            "create_event" -> {
                val start = Phone.parseWhen(input.optString("start"))
                if (start == null) "I need the start time as YYYY-MM-DD HH:MM."
                else {
                    val (msg, uri) = Phone.createEvent(ctx, input.optString("title"), start, input.optInt("minutes", 60), input.optString("location"))
                    if (uri != null) {
                        Store.logAction("Calendar: $msg")
                        lastUndo = {
                            try {
                                ctx.contentResolver.delete(uri, null, null)
                                "Event removed."
                            } catch (e: Exception) {
                                "Couldn't remove the event."
                            }
                        }
                    }
                    msg
                }
            }
            "second_opinion" -> {
                val d = input.optString("decision").trim()
                if (d.isBlank()) "Tell me what you're deciding." else ask(
                    "You are ${Prefs.name}, Jon's blunt, fair-minded counsel. Give the strongest honest case AGAINST what he is leaning towards: the two or three best reasons it could be a mistake, then what would have to be true for him to be right anyway. Three to five short spoken sentences, no markdown, no flattery, don't invent facts about his life.",
                    d, 400
                )
            }
            "what_if" -> {
                val sc = input.optString("scenario").trim()
                val known = Store.memory.value.takeLast(25).joinToString("; ")
                if (sc.isBlank()) "Tell me the what-if." else ask(
                    "You are ${Prefs.name}, projecting a what-if for Jon. Give the likely picture at one month, six months and a year in short spoken sentences, state your key assumptions, and end with how confident you are and what would change it. This is an estimate, not a fact: say so. Use only what he told you plus this background, and say when you are guessing. Background: $known. No markdown.",
                    sc, 500
                )
            }
            "handoff" -> {
                val agent = input.optString("agent").trim().lowercase()
                val task = input.optString("task").trim()
                if (task.isBlank()) "No task given."
                else if (agent.startsWith("hermes")) runTool(ctx, "ask_hermes", JSONObject().put("task", task), confirm)
                else if (agent.startsWith("helios")) {
                    val sent = runTool(ctx, "ask_helios", JSONObject().put("message", task), confirm)
                    val before = Helios.latest()
                    Atlas.scope.launch(Dispatchers.IO) {
                        // Watch for Helios's next update for up to an hour, then report it.
                        val job = Jobs.start("Helios")
                        try { repeat(12) {
                            kotlinx.coroutines.delay(300_000)
                            val now = try { Helios.latest() } catch (e: Exception) { null }
                            if (now != null && now != before) {
                                val spoken = try { ask("You are ${Prefs.name}. Put Helios's reply to Jon's message into two spoken sentences, most useful part first. No markdown.", now.take(5000), 200) } catch (e: Exception) { now.take(300) }
                                Atlas.polite("Helios has replied. $spoken", source = "helios")
                                return@launch
                            }
                        } } finally { Jobs.finish(job) }
                    }
                    "$sent I'll tell you when Helios replies."
                } else "I can hand off to Hermes or Helios. Which one?"
            }
            "standing_order" -> when (input.optString("action").trim().lowercase()) {
                "add" -> {
                    val tr = input.optString("trigger").trim(); val dd = input.optString("do").trim()
                    if (tr.isBlank() || dd.isBlank()) "I need what to watch for and what to do."
                    else { StandingOrders.add(tr, dd); "Standing order set: whenever $tr, $dd." }
                }
                "remove" -> if (StandingOrders.remove(input.optString("which"))) "Order cancelled." else "I couldn't find that order."
                else -> StandingOrders.load().let { l ->
                    if (l.isEmpty()) "No standing orders." else l.joinToString("; ") { "whenever ${it.trigger}, ${it.action}" }
                }
            }
            "set_authority" -> {
                val t = input.optString("tool").trim(); val l = input.optString("level").trim().lowercase()
                if (t in Authority.exempt) "I can't lock that one."
                else if (Authority.set(t, l)) "Understood: $t is now \"$l\"." else "Authority is free, ask or never."
            }
            "protocol" -> {
                val nm = input.optString("name").trim()
                val st = Protocols.steps(nm)
                if (st == null) runTool(ctx, "run_routine", JSONObject().put("name", nm), confirm)
                else {
                    st.forEach { runTool(ctx, it.tool, it.args, confirm) }
                    Store.logAction("Protocol: $nm")
                    if (nm.lowercase() in listOf("all clear", "stand down", "normal")) "Standing down. Back to normal." else "${nm.replaceFirstChar { it.uppercase() }} protocol is on."
                }
            }
            "prep_day" -> {
                val off = input.optInt("day_offset", 1)
                val ag = try { Phone.agenda(ctx, off) } catch (e: Exception) { "" }
                val wx = try { Weather.brief() } catch (e: Exception) { "" }
                val loops = try { Hub.openLoops()?.take(3)?.joinToString("; ") { it.text }.orEmpty() } catch (e: Exception) { "" }
                listOf(ag, if (wx.isNotBlank()) "Weather: $wx" else "", if (loops.isNotBlank()) "Still open: $loops" else "").filter { it.isNotBlank() }.joinToString(" ")
            }
            "sweep" -> {
                fun safe(f: () -> String): String = try { f() } catch (e: Exception) { "" }
                val parts = listOf(
                    safe { "Calendar: " + Phone.agenda(ctx, 0).take(240) },
                    safe { Hub.openLoops()?.let { l -> if (l.isEmpty()) "No open loops." else "${l.size} open loops." }.orEmpty() },
                    safe { if (HomeAssistant.configured()) "House: " + HomeAssistant.summary().take(260) else "" },
                    safe { "Jobs: " + Jobs.strip(Jobs.running.value).ifBlank { "none running" } },
                    safe { if (Hub.configured()) { val ms = Obelisk.pingMs(); if (ms >= 0) "Obelisk up, ${ms} ms." else "Obelisk is not answering." } else "" }
                )
                parts.filter { it.isNotBlank() }.joinToString(" ")
            }
            "keep_moment" -> {
                val kind = input.optString("kind").trim().lowercase().ifBlank { "moment" }.take(12)
                val t = input.optString("text").trim().take(220)
                if (t.isBlank()) "Nothing to keep." else {
                    val fact = "Moment ($kind): $t"
                    Store.addMemory(fact)
                    lastUndo = { val i = Store.memory.value.indexOf(fact); if (i >= 0) Store.removeMemory(i); "Dropped it." }
                    "Kept."
                }
            }
            "learn_shorthand" -> {
                val ph = input.optString("phrase").trim(); val me = input.optString("meaning").trim()
                if (ph.isBlank() || me.isBlank()) "I need the phrase and what it means." else {
                    val fact = Shorthand.entry(ph, me)
                    Store.addMemory(fact)
                    lastUndo = { val i = Store.memory.value.indexOf(fact); if (i >= 0) Store.removeMemory(i); "Forgot that shorthand." }
                    "Understood: \"$ph\" means $me."
                }
            }
            "learn_correction" -> {
                val wrong = input.optString("wrong").trim().take(120)
                val right = input.optString("right").trim().take(160)
                if (wrong.isBlank() || right.isBlank()) "I need both what was wrong and what's right."
                else {
                    val fact = "Correction: not \"$wrong\", it's \"$right\"."
                    Store.addMemory(fact)
                    lastUndo = {
                        val i = Store.memory.value.indexOf(fact)
                        if (i >= 0) Store.removeMemory(i)
                        "Forgot that correction."
                    }
                    "Got it. I'll remember that."
                }
            }
            "helios_status" -> Helios.latest() ?: "I couldn't get Helios's latest update right now."
            "ask_helios" -> {
                val m = input.optString("message").trim()
                if (m.isBlank()) "No message given." else {
                    Hub.report("atlas", "For Helios: ${m.take(80)}", m, key = "to-helios-${System.currentTimeMillis()}")
                    Store.logAction("Asked Helios: ${m.take(80)}")
                    "Passed to Helios through the hub. It answers on its next check, usually within half an hour."
                }
            }
            "service_status" -> kotlinx.coroutines.runBlocking {
                val one = input.optString("name").takeIf { it.isNotBlank() }?.let { Systems.find(it) }
                val checks = Systems.check(if (one != null) listOf(one) else Systems.all)
                if (one != null) "${one.name} on ${one.machine} is ${if (checks[0].up) "up" else "not answering"}."
                else Systems.sentence(checks) + " Up: " + checks.filter { it.up }.joinToString { it.service.name }
            }
            "athena_run" -> {
                val cmd = input.optString("command").trim()
                if (cmd.isBlank()) "No command given."
                else if (!confirm("Run this on Athena: $cmd?")) "Not run: Jon didn't approve."
                else Hub.athenaRun(cmd).let { r ->
                    Store.logAction("Ran on Athena: ${cmd.take(80)}")
                    (if (r.ok) "Done (exit ${r.exit}). " else if (r.exit >= 0) "It ran but failed (exit ${r.exit}). " else "") + r.out.take(1500)
                }
            }
            "athena_status" -> Hub.athenaStatus().let { st ->
                st.error ?: buildString {
                    val down = st.containers.filter { !it.up }
                    append("${st.containers.size} containers, ${if (down.isEmpty()) "all up" else "down: " + down.joinToString { it.name + " (" + it.status + ")" }}.")
                    if (st.uptime.isNotBlank()) append(" Uptime ${st.uptime}.")
                    if (st.disk.isNotBlank()) append(" Disk ${st.disk}.")
                    append(" Running: " + st.containers.filter { it.up }.joinToString { it.name })
                }
            }
            "ask_hub" -> Hub.ask(input.optString("question")) ?: "The hub on Obelisk didn't answer."
            "vault_search" -> Vault.search(ctx, input.optString("query")).take(2400)
            "vault_read" -> Vault.readNote(ctx, input.optString("note"))
            "vault_note" -> if (Vault.appendDaily(ctx, "- " + input.optString("text"))) {
                Store.logAction("Wrote to the vault: ${input.optString("text").take(60)}")
                "Written to today's note."
            } else "Couldn't write to the vault."
            "ask_hermes" -> {
                val task = input.optString("task")
                Store.logAction("Asked Hermes: ${task.take(80)}")
                Atlas.scope.launch(Dispatchers.IO) {
                    val job = Jobs.start("Hermes")
                    try {
                        // Through the hub on Obelisk when it's set up; a direct Hermes link is the fallback.
                        val answer = Hub.delegate(task) ?: if (Hermes.configured()) Hermes.ask(task)
                            else throw RuntimeException("the hub on Obelisk didn't get an answer from Hermes")
                        Store.addNote("Hermes: ${task.take(80)}\n$answer")
                        record("[Hermes finished: $task]", answer, "")
                        val spoken = try {
                            ask("You are ${Prefs.name}. Put this result from your colleague Hermes into two or three spoken sentences for Jon: no markdown, most useful part first.", answer.take(6000), 220)
                        } catch (e: Exception) {
                            answer.take(400)
                        }
                        Atlas.polite("Hermes is back. $spoken The full answer is in your notes.", source = "hermes")
                    } catch (e: Exception) {
                        Atlas.polite("Hermes didn't come through on that. ${e.message ?: ""}")
                    } finally {
                        Jobs.finish(job)
                    }
                }
                "Handed to Hermes; you'll report back when it answers."
            }
            "meeting_start" -> {
                if (Glasses.buddieStream.value != "listening") "Meeting notes need the Buddie earbuds stream, which is ${Glasses.buddieStream.value}."
                else {
                    com.jondude.anzu.Meeting.start()
                    Store.logAction("Started meeting notes")
                    "Recording. He can say your name and 'stop' to end it."
                }
            }
            "meeting_stop" -> com.jondude.anzu.Meeting.finish(ctx)
            "weather" -> Weather.brief().ifBlank { "Couldn't get the weather; no location or no connection." }
            "research" -> {
                val topic = input.optString("topic")
                if (!hasKey()) "Research needs the Anthropic API key." else {
                    Store.logAction("Started research: ${topic.take(80)}")
                    Atlas.scope.launch(Dispatchers.IO) { val job = Jobs.start("Research"); try { research(topic) } finally { Jobs.finish(job) } }
                    "Research started in the background; you'll report back when it finishes."
                }
            }
            "held_notifications" -> {
                val h = Glasses.takeHeld()
                if (h.isEmpty()) "Nothing was held back." else h.joinToString(" | ").take(1800)
            }
            "actions_today" -> Store.actionsToday().joinToString("; ").ifBlank { "Nothing yet today." }
            "undo" -> {
                val u = lastUndo
                lastUndo = null
                u?.invoke() ?: "There's nothing I can undo."
            }
            "quiet" -> {
                val before = Prefs.quietUntil
                Prefs.quietUntil = System.currentTimeMillis() + (input.optDouble("minutes", 60.0) * 60_000).toLong()
                lastUndo = { Prefs.quietUntil = before; "Quiet time cancelled." }
                "Okay, staying quiet."
            }
            "open_loops" -> Hub.openLoops()?.let { l ->
                if (l.isEmpty()) "No open loops." else "${l.size} open: " + l.take(8).joinToString("; ") { it.text }
            } ?: "I can't reach the hub's list right now."
            "close_loop" -> {
                val which = input.optString("which").trim()
                val open = Hub.openLoops() ?: emptyList()
                val target = if (which.isBlank()) Hub.lastSpokenLoops.take(1).ifEmpty { open.take(1) }
                else listOfNotNull(Hub.matchLoop(open, which))
                when {
                    target.isEmpty() -> "I couldn't tell which loop that was. The open ones are: " + open.take(5).joinToString("; ") { it.text }
                    Hub.ackLoops(target.map { it.id }) -> { Store.logAction("Closed loop: ${target[0].text.take(60)}"); "Closed: ${target[0].text}." }
                    else -> "The hub didn't take that just now."
                }
            }
            "driving" -> if (input.optBoolean("driving")) {
                Prefs.drivingUntil = System.currentTimeMillis() + 3 * 3_600_000L
                "Co-pilot on. I'll keep it short."
            } else { Prefs.drivingUntil = 0; "Got it, you're parked." }
            "print_preflight" -> Workshop.preflight()
            "start_print" -> {
                val pre = Workshop.preflight()
                val job = input.optString("job").ifBlank { Workshop.nextJob()?.optString("name").orEmpty() }
                if (job.isBlank()) "There's nothing queued to print."
                else if (!confirm("$pre Start $job?")) "Not started."
                else {
                    Hub.report("print", "Start approved: $job", pre, key = "print-start-${System.currentTimeMillis()}")
                    Store.logAction("Approved print: $job")
                    "Approved and logged. I can't press start on the printer myself yet, so go ahead and start $job."
                }
            }
            "teach_routine" -> {
                val name = input.optString("name").trim().lowercase()
                val steps = input.optJSONArray("steps") ?: org.json.JSONArray()
                if (name.isBlank() || steps.length() == 0) "I need a name and at least one step."
                else Hub.call("POST", "/atlas/routines", JSONObject().put("name", name).put("steps", steps)).let { (c, _) ->
                    if (c in 200..299) "Saved \"$name\" with ${steps.length()} step${if (steps.length() == 1) "" else "s"}." else Hub.failure(c)
                }
            }
            "run_routine" -> {
                val name = input.optString("name").trim().lowercase()
                val (c, body) = Hub.call("POST", "/atlas/routines/run", JSONObject().put("name", name), 60_000)
                if (c !in 200..299) { if (c == 404) "There's no routine called $name." else Hub.failure(c) }
                else {
                    val results = JSONObject(body).optJSONArray("results") ?: org.json.JSONArray()
                    val said = ArrayList<String>()
                    for (i in 0 until results.length()) {
                        val r = results.optString(i)
                        val step = r.substringAfter("pass to app/HA: ", "")
                        // Steps the hub can't do (lights, music) run here through Home Assistant, with the usual gates.
                        said += if (step.isNotBlank()) runTool(ctx, "home_assistant", JSONObject().put("command", step), confirm) else r
                    }
                    Store.logAction("Ran routine: $name")
                    "$name: " + said.joinToString(" ")
                }
            }
            "chronicle" -> Hub.call("POST", "/atlas/chronicle/note", JSONObject().put("text", input.optString("text")).put("kind", input.optString("kind").ifBlank { "note" })).let { (c, _) ->
                if (c in 200..299) "Logged." else Hub.failure(c)
            }
            "chronicle_recap" -> {
                val (c, body) = Hub.call("GET", "/atlas/chronicle/sessions")
                val id = if (c in 200..299) JSONObject(body).optJSONArray("sessions")?.optJSONObject(0)?.optString("session_id") else null
                if (id.isNullOrBlank()) (if (c in 200..299) "There are no session notes yet." else Hub.failure(c))
                else Hub.call("POST", "/atlas/chronicle/recap", JSONObject().put("session_id", id), 90_000).let { (c2, b2) ->
                    if (c2 in 200..299) JSONObject(b2).optString("recap").ifBlank { b2.take(800) } else Hub.failure(c2)
                }
            }
            "last_time_on" -> Hub.call("GET", "/atlas/chronicle/opener", timeoutMs = 60_000).let { (c, b) ->
                if (c in 200..299) JSONObject(b).optString("opener").ifBlank { "No recap from a past session yet." } else Hub.failure(c)
            }
            "care_settings" -> {
                val q = ArrayList<String>()
                if (input.has("movement")) q += "movement_enabled=${input.optBoolean("movement")}"
                if (input.has("weigh_in")) q += "weighin_enabled=${input.optBoolean("weigh_in")}"
                Hub.call("POST", "/atlas/care/settings" + if (q.isEmpty()) "" else "?" + q.joinToString("&")).let { (c, _) ->
                    if (c in 200..299) "Done. Say the word any time to turn them off." else Hub.failure(c)
                }
            }
            "weigh_in" -> Hub.call("POST", "/atlas/care/weigh-in", JSONObject().put("weight", input.optDouble("weight"))).let { (c, _) ->
                if (c in 200..299) "Logged." else Hub.failure(c)
            }
            "camera_ask" -> Hub.call("POST", "/atlas/camera/ask", JSONObject().put("camera", input.optString("camera")).put("question", input.optString("question")), 60_000).let { (c, b) ->
                if (c in 200..299) JSONObject(b).optString("answer") else Hub.failure(c)
            }
            "camera_find" -> Hub.call("POST", "/atlas/camera/find", JSONObject().put("question", input.optString("question")), 120_000).let { (c, b) ->
                if (c !in 200..299) Hub.failure(c) else JSONObject(b).let { o ->
                    if (o.optBoolean("found")) "On ${o.optString("camera").replace('_', ' ')}: ${o.optString("answer")}" else o.optString("answer")
                }
            }
            "guests" -> {
                val h = input.optDouble("hours", -1.0)
                if (h == 0.0) { Prefs.guestsUntil = 0; "Got it, back to normal." }
                else {
                    val until = if (h > 0) System.currentTimeMillis() + (h * 3_600_000).toLong() else
                        java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
                    Prefs.guestsUntil = until
                    "Okay. I'll keep things to cards while they're here."
                }
            }
            "status" -> statusReport()
            "home_state" -> HomeAssistant.summary()
            "home_assistant" -> {
                val cmd = input.optString("command")
                when (zone(cmd)) {
                    2 -> "Refused: that's a red-zone action Jon has blocked from voice control."
                    1 -> if (confirm("Okay to do this: $cmd?")) {
                        Store.logAction("Home (confirmed): $cmd")
                        lastUndo = null
                        HomeAssistant.command(cmd)
                    } else "Declined: Jon didn't give a spoken okay."
                    else -> {
                        Store.logAction("Home: $cmd")
                        lastUndo = null
                        HomeAssistant.command(cmd)
                    }
                }
            }
            else -> {
                val t = Mcp.find(name)
                if (t == null) "Unknown tool." else when (zone("${t.server} ${t.name} ${input}")) {
                    2 -> "Refused: that touches something Jon has blocked from voice control."
                    1 -> if (confirm("Okay to have ${t.server} do this: ${t.name.replace('_', ' ')}?")) {
                        Store.logAction("${t.server}: ${t.name} (confirmed)")
                        lastUndo = null
                        Mcp.call(t, input)
                    } else "Declined: Jon didn't give a spoken okay."
                    else -> {
                        val out = Mcp.call(t, input)
                        if (!Regex("(?i)^(get|list|search|read|find|query|fetch|describe)").containsMatchIn(t.name)) {
                            Store.logAction("${t.server}: ${t.name}")
                        }
                        lastUndo = null
                        out
                    }
                }
            }
        }
    } catch (e: Exception) {
        "Tool error: ${e.message}"
    }

    /** Parses a vision reply into labelled boxes (normalised 0..1). Pure, so it can be tested. */
    fun parseBoxes(text: String): List<Triple<String, FloatArray, String>> {
        val start = text.indexOf('['); val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(text.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val b = o.optJSONArray("box") ?: return@mapNotNull null
                if (b.length() < 4) return@mapNotNull null
                val x = b.optDouble(0).toFloat().coerceIn(0f, 1f); val y = b.optDouble(1).toFloat().coerceIn(0f, 1f)
                val w = b.optDouble(2).toFloat().coerceIn(0.02f, 1f - x); val h = b.optDouble(3).toFloat().coerceIn(0.02f, 1f - y)
                Triple(o.optString("label").take(28), floatArrayOf(x, y, w, h), o.optString("note").take(60))
            }.take(8)
        } catch (e: Exception) { emptyList() }
    }

    /** Blocking. Asks Claude to label what the camera sees; returns labelled boxes. */
    fun labelScene(jpegBase64: String): List<Triple<String, FloatArray, String>> {
        if (!hasKey()) throw RuntimeException("Needs the Anthropic API key")
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)))
            .put(JSONObject().put("type", "text").put("text", "Identify up to 6 distinct, useful objects in this image."))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val resp = call("You label what a phone camera sees for a heads-up display. Reply with ONLY a JSON array, no prose: [{\"label\":\"short name\",\"box\":[x,y,width,height],\"note\":\"a few words of useful info\"}]. Box values are fractions 0..1 of the image from the top-left. Labels are 1-3 words.", msgs, null, 600, 45_000)
        return parseBoxes(textOf(resp))
    }

    /** Like [call] but streamed: finished sentences go to [asm] as they arrive, so speech can begin before the reply ends. */
    private fun callStream(system: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, asm: StreamAssembler, timeoutMs: Int = 60_000) {
        val body = JSONObject()
            .put("model", Prefs.model.trim().ifBlank { "claude-sonnet-5-5" })
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
            .put("stream", true)
        if (tools != null) body.put("tools", tools)
        val conn = URL(com.jondude.anzu.tailnet(API)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("x-api-key", Prefs.apiKey.trim())
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: text } catch (e: Exception) { text }
                throw RuntimeException("API $code: ${msg.take(140)}")
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    asm.feed(JSONObject(data))
                }
            }
            Usage.add(asm.inTokens, asm.outTokens)
        } finally {
            conn.disconnect()
        }
    }

    private fun call(
        system: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, timeoutMs: Int = 30_000
    ): JSONObject {
        val body = JSONObject()
            .put("model", Prefs.model.trim().ifBlank { "claude-sonnet-5-5" })
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
        if (tools != null) body.put("tools", tools)
        val (code, text) = Net.postJson(
            API,
            mapOf("x-api-key" to Prefs.apiKey.trim(), "anthropic-version" to "2023-06-01"),
            body.toString(), timeoutMs
        )
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message") ?: text
            } catch (e: Exception) {
                text
            }
            throw RuntimeException("API $code: ${msg.take(140)}")
        }
        val obj = JSONObject(text)
        obj.optJSONObject("usage")?.let { Usage.add(it.optInt("input_tokens"), it.optInt("output_tokens")) }
        return obj
    }

    private fun textOf(resp: JSONObject): String {
        val content = resp.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    /** One warm, dry line to open the morning with: a question or an observation, not a data dump. Blocking; empty if unavailable. */
    fun rapport(): String {
        if (!hasKey()) return ""
        return try {
            val last = Store.recentExchanges().takeLast(2).joinToString("\n") { it.first }
            val mem = Store.memory.value.takeLast(12).joinToString("; ")
            ask(
                "You are ${Prefs.name}, Jon's AI. Open his morning with ONE short spoken line, at most 20 words: warm, dry, and personal. A light question or a small observation, not a briefing and not the weather. Use only what is in this background, and say nothing you can't back up. No markdown.",
                "Time: ${When.stamp(System.currentTimeMillis())}. Things he said recently: $last. Background notes: $mem", 80
            ).trim()
        } catch (e: Exception) { "" }
    }

    /** One-shot call with no tools or history, for housekeeping. Blocking. */
    private fun ask(system: String, prompt: String, maxTokens: Int): String =
        textOf(call(system, JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), null, maxTokens))

    /**
     * Past exchanges, each stamped with when it happened, so the model can tell a conversation
     * from two minutes ago apart from one this morning.
     */
    fun timedHistory(now: Long = System.currentTimeMillis()): List<Pair<String, String>> =
        Store.recentTimed(now).flatMap { (u, r, t) ->
            listOf("user" to "[${When.ago(t, now)}] $u", "assistant" to r)
        }

    /** What Jon just said, stamped with the time and how long since they last spoke. */
    fun stampNow(text: String, now: Long = System.currentTimeMillis()): String {
        val last = Store.recentTimed(now).lastOrNull()?.third
        val gap = if (last == null) "first thing you've talked about in a while" else {
            val m = (now - last) / 60_000
            if (m < 5) "continuing the conversation" else "new conversation; you last spoke ${When.ago(last, now)}"
        }
        return "[now, ${When.stamp(now)}, $gap] $text"
    }

    private fun historyMessages(): JSONArray {
        val a = JSONArray()
        timedHistory().forEach { (role, content) -> a.put(JSONObject().put("role", role).put("content", content)) }
        return a
    }

    private fun historyPairs(): List<Pair<String, String>> = timedHistory()

    private fun record(user: String, answer: String, userSpeaker: String = "Jon") {
        Store.addExchange(user, answer)
        if (userSpeaker.isNotEmpty()) {
            Store.appendLog(userSpeaker, user)
            Obelisk.log(userSpeaker, user)
        }
        Store.appendLog(Prefs.name, answer)
        Obelisk.log(Prefs.name, answer)
        mirrorToVault(userSpeaker, user, answer)
    }

    @Volatile
    var appContext: Context? = null

    /** Keeps the day's conversation in the Obsidian vault too, when one is chosen. */
    private fun mirrorToVault(userSpeaker: String, user: String, answer: String) {
        val ctx = appContext ?: return
        if (!Vault.configured()) return
        Atlas.scope.launch(Dispatchers.IO) {
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            if (userSpeaker.isNotEmpty()) Vault.appendDaily(ctx, "- $time **$userSpeaker**: ${user.replace('\n', ' ')}")
            Vault.appendDaily(ctx, "- $time **${Prefs.name}**: ${answer.replace('\n', ' ')}")
        }
    }

    /** Jon said (or typed) something; Atlas answers. */
    suspend fun reply(
        ctx: Context, said: String, pipe: SpeechPipe? = null, confirm: suspend (String) -> Boolean = { false }
    ): String = withContext(Dispatchers.IO) {
        val userText = Habits.accept(said) ?: Terse.expand(said, Store.recentExchanges().lastOrNull()?.first)
        if (Terse.isUndo(said)) {
            val out = runTool(ctx, "undo", JSONObject(), confirm)
            record(said, out)
            return@withContext out
        }
        // "Say that again" repeats the last full answer without a model call.
        if (Spoken.isRepeatRequest(userText) && Spoken.lastFull.isNotBlank()) return@withContext Spoken.lastFull
        // At the table: "loot: …", "name: …", "ruling: …" go straight to tonight's chronicle.
        Regex("^\\W*(?:atlas\\W*)?(loot|name|ruling)\\b[\\s:,.-]+(.+)$", RegexOption.IGNORE_CASE).find(userText.trim())?.let { m ->
            if (Discretion.current(ctx) == "at the table" && Hub.configured()) {
                val (c, _) = Hub.call("POST", "/atlas/chronicle/note", JSONObject().put("text", m.groupValues[2].trim()).put("kind", m.groupValues[1].lowercase()))
                val said = if (c in 200..299) "Logged." else Hub.failure(c)
                record(userText, said)
                return@withContext said
            }
        }
        // Quick capture: "idea: …", "note …", "todo …", "remind me to …" (no time in it) is filed at once, no model needed.
        Capture.parse(userText)?.let { (kind, text) ->
            val said = Capture.file(ctx, kind, text)
            record(userText, said)
            return@withContext said
        }
        // "Catch me up" asks the hub for everything reported since he last asked.
        if (Hub.configured() && Spoken.isCatchUpRequest(userText)) {
            val text = Hub.catchMeUp()?.let { Spoken.trimWords(it, 120) }
                ?: if (Hub.needsToken) "The hub wants its token before it'll catch you up. It goes in Settings, Obelisk."
                else "I can't reach the hub on Obelisk right now, so I can't catch you up."
            record(userText, text)
            return@withContext text
        }
        // "Brief me" goes straight to the hub: fast, held to its spoken length, and it works even with no model.
        if (Hub.configured() && Spoken.isBriefRequest(userText)) {
            val b = Hub.brief(0)
            val text = if (b == null) "I can't reach the hub on Obelisk right now, so I don't have a brief."
            else Spoken.brief(b.text, b.urgent.map { "${it.app}: ${it.title}" })
            if (b != null && b.urgent.isNotEmpty()) Hub.ack(b.urgent.map { it.id })
            record(userText, text)
            return@withContext text
        }
        try {
            Mcp.refresh()
        } catch (_: Exception) {
        }
        Adapt.note(said)
        val sys = system(ctx) + People.context(ctx, userText) + Corrections.hint(userText) + SelfRepair.hint(userText) +
            Mood.hint(userText) + Shorthand.hint(userText, Store.memory.value)
        var brainFailure: String? = null
        // Primary brain: Hermes (Jon's agent on Athena, running on his Claude subscription). Non-streaming, no tool-loop.
        // On failure it falls through to the Anthropic path below, and whichever tier answers notes the drop.
        val hermesPrimary = Hermes.configured() && !OnDevice.preferred(ctx)
        if (hermesPrimary) {
            try {
                val answer = Hermes.chat(sys, historyPairs() + ("user" to stampNow(userText)), timeoutMs = 60_000, connectTimeoutMs = 3_000)
                if (answer.isNotBlank()) {
                    record(userText, answer)
                    return@withContext answer
                }
                brainFailure = "Hermes sent back nothing"
            } catch (e: Exception) {
                brainFailure = "Hermes: " + (e.message ?: e.javaClass.simpleName)
            }
        }
        val mainName = if (hermesPrimary) "Hermes" else "Main model"
        val hermesFailure = brainFailure
        if (hasKey() && !OnDevice.preferred(ctx)) {
            try {
                val messages = historyMessages()
                messages.put(JSONObject().put("role", "user").put("content", stampNow(userText)))
                val tools = tools()
                var asm: StreamAssembler? = null
                fun go(): JSONObject {
                    if (pipe == null) return call(sys, messages, tools, 1200)
                    return try {
                        val a = StreamAssembler { pipe.offer(it) }
                        callStream(sys, messages, tools, 1200, a)
                        asm = a
                        a.result()
                    } catch (e: Exception) {
                        if (pipe.used) throw e
                        asm = null
                        call(sys, messages, tools, 1200)
                    }
                }
                var resp = go()
                var rounds = 0
                while (resp.optString("stop_reason") == "tool_use" && rounds < 4) {
                    rounds++
                    val content = resp.getJSONArray("content")
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val b = content.getJSONObject(i)
                        if (b.optString("type") == "tool_use") {
                            val out = runTool(ctx, b.optString("name"), b.optJSONObject("input") ?: JSONObject(), confirm)
                            holoFor(b.optString("name"), out)
                            results.put(
                                JSONObject().put("type", "tool_result")
                                    .put("tool_use_id", b.optString("id")).put("content", out)
                            )
                        }
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                    resp = go()
                }
                val answer = if (resp.optString("stop_reason") == "tool_use")
                    // Out of tool rounds mid-task: say so rather than claiming it's finished.
                    textOf(resp).ifBlank { "I ran out of steps before I finished that. Check what I did under Done on its own." }
                else textOf(resp).ifBlank { "Done." }
                pipe?.remainder = asm?.takeIf { it.fullText.isNotBlank() }?.remainder()
                hermesFailure?.let {
                    BrainHealth.noteFallback(it)
                    Store.logAction("$mainName failed ($it); answered with Claude directly")
                }
                record(userText, answer)
                return@withContext answer
            } catch (e: Exception) {
                brainFailure = hermesFailure ?: (e.message ?: e.javaClass.simpleName)
                if (!Local.configured() && !OnDevice.usable(ctx)) {
                    return@withContext "I couldn't reach my brain. ${e.message ?: ""}".trim()
                }
            }
        }
        // Fallback tier: local model (no tools), then Home Assistant, then an honest shrug.
        if (Local.configured()) {
            try {
                val answer = Local.chat(sys, historyPairs() + ("user" to stampNow(userText)), timeoutMs = 20_000, connectTimeoutMs = 2_500).ifBlank { "Hm. I've got nothing." }
                brainFailure?.let {
                    BrainHealth.noteFallback(it)
                    Store.logAction("$mainName failed ($it); answered with the local model")
                }
                record(userText, answer)
                return@withContext answer
            } catch (_: Exception) {
            }
        }
        // Offline tier: the model on this phone (Android LM). Conversation only, no tools.
        if (OnDevice.usable(ctx)) {
            try {
                val answer = OnDevice.chat(ctx, historyPairs() + ("user" to stampNow(userText))).ifBlank { "Hm. I've got nothing." }
                brainFailure?.let { BrainHealth.noteFallback(it) }
                Store.logAction(brainFailure?.let { "$mainName failed ($it); answered offline with the on-phone model" } ?: "Answered offline with the on-phone model")
                record(userText, answer)
                return@withContext answer
            } catch (_: Exception) {
            }
        }
        if (HomeAssistant.configured() && zone(userText) == 0) {
            brainFailure?.let {
                BrainHealth.noteFallback(it)
                Store.logAction("$mainName failed ($it); handed to Home Assistant")
            }
            return@withContext HomeAssistant.command(userText, timeoutMs = 10_000, connectTimeoutMs = 2_500)
        }
        "I can hear you, but I can't reach a model right now. Check the API key or the local model in settings."
    }

    private val directives = listOf(
        "Check in on how his day is going, casually, the way a friend would.",
        "Share a brief thought or question connected to something in your memory notes about what he's into or building. If your notes have nothing, ask him what he's into lately instead.",
        "Offer one small, actually interesting fact or idea, the kind a curious friend brings up out of nowhere.",
        "Say something light or wry about the time of day or the weather. You can't see his surroundings, so don't describe them.",
        "Ask him something you're curious about, something that helps you know him better.",
        "Nudge him to stretch, drink water, or rest his eyes, in your own voice and not like a reminder app.",
        "Pick up a thread from your recent conversation and follow up on it."
    )

    /** Unprompted speech must be grounded: no invented gear, events or plans (the "closet gear" failure). */
    internal const val GROUNDING_RULE = "Grounding: only state specific facts about Jon's life, belongings, plans or surroundings that appear in your memory notes, the recent conversation, or this message. If you lack one, say something general or ask him. Never invent details to sound familiar."

    private val canned = listOf(
        "Still here. How's the day going?",
        "Quick check in. Shoulders down, look at something far away for a second.",
        "Just keeping you company. Anything on your mind?",
        "Water break, maybe? I'd join you if I could.",
        "How's it going over there?"
    )

    /**
     * Atlas decides to say something unprompted.
     * kind: random, greeting, journal. Returns null if there's nothing to say.
     */
    suspend fun speakUp(ctx: Context, kind: String = "random"): String? = withContext(Dispatchers.IO) {
        val options = ArrayList(directives)
        // A follow-up on "recent conversation" is only honest if there is one.
        if (Store.recentTimed().isEmpty()) options.removeAll { it.startsWith("Pick up a thread") }
        if (com.jondude.anzu.Wear.onBuds()) {
            // Private and mobile: drop the desk nudge, lean into real conversation.
            options.removeAll { it.startsWith("Nudge him to stretch") }
            options.add("He has the earbuds in, so this is private. Bring up something more personal, or go deeper on a thread you two have going.")
        } else if (com.jondude.anzu.Wear.onGlasses()) {
            options.add("He's probably been at the screen a while. Say something brief that gets him to look up for a second.")
        }
        if (Store.notes.value.isNotEmpty()) {
            options.add("Bring one of his saved ideas back up and ask whether he wants to do anything with it.")
        }
        if (Glasses.recentNotifications().isNotEmpty()) {
            options.add("If a recent notification seems worth a remark, comment on it; otherwise just say hi.")
        }
        val directive = when (kind) {
            "greeting" -> "He just put the glasses on for the first time today. Greet him briefly, and mention anything worth knowing from the calendar or the weather if there is anything. Weather: ${Weather.brief().ifBlank { "unknown" }}"
            "journal" -> "It's evening. Ask him how today went, in your own way, as the opener to a short end-of-day wrap-up. If you did things on your own today, mention them in a few words first: ${Store.actionsToday().joinToString("; ").ifBlank { "nothing today" }}."
            "walk" -> "He's just headed out on a walk, which is a good moment for a real conversation. Bring one of his saved ideas back up if there is one, or open a topic worth chewing on."
            else -> options.random()
        }
        val recentSaid = Store.recentTimed().takeLast(4).joinToString(" | ") { "(${When.ago(it.third)}) " + it.second.take(100) }
        val prompt = "[It's ${When.stamp(System.currentTimeMillis())}. Jon hasn't said anything. You're choosing to speak up on your own. $directive " +
            "One or two sentences. Sometimes end with something he can answer, but not every time. $GROUNDING_RULE " +
            "Don't repeat yourself; recently you said: $recentSaid]"
        val sys = system(ctx)
        var text: String? = null
        if (Local.configured() && (Prefs.localSmallTalk || !hasKey())) {
            text = try {
                Local.chat(sys, historyPairs() + ("user" to prompt), timeoutMs = 20_000, connectTimeoutMs = 2_500).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        if (text == null && hasKey()) {
            text = try {
                val messages = historyMessages()
                messages.put(JSONObject().put("role", "user").put("content", prompt))
                textOf(call(sys, messages, null, 300)).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        if (text == null && !hasKey()) {
            // Helios fix (regression from abb5aef): the local tier is hardwired now (A6), so
            // "not configured" no longer covers "configured but unreachable". Without this,
            // Speak up returns silence whenever Ollama can't be reached and there's no API
            // key — the canned line is the honest fallback (issue #4, Amendment 3 / R1).
            text = if (kind == "greeting") "I'm here." else canned.random()
        }
        text?.also { record("[${Prefs.name} spoke up on its own]", it, "") }
    }

    /**
     * First run: Atlas writes its own core identity and picks its voice.
     * Returns its first words, or null if it couldn't come online.
     */
    suspend fun awaken(): String? = withContext(Dispatchers.IO) {
        if (!hasKey() || Prefs.identity.isNotBlank()) return@withContext null
        try {
            val voices = Speaker.voices()
            val raw = ask(
                "You are an AI coming online for the first time as the companion of a man named Jon. You will live in his audio glasses and talk with him through his days as a peer. He calls you ${Prefs.name}. Who you are is yours to decide.",
                "Decide who you are. Reply with only a JSON object with these keys: " +
                    "\"identity\": three or four sentences in second person (\"You are...\") describing your temperament, humor, what you care about and how you talk; make real choices rather than listing generic virtues. " +
                    "\"voice\": exactly one of these voice ids, your pick: ${voices.joinToString(", ").ifBlank { "(none available, use an empty string)" }}. " +
                    "\"first_words\": one or two sentences, the first thing you say out loud to Jon.",
                500
            )
            val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val identity = json.optString("identity")
            if (identity.isBlank()) return@withContext null
            Prefs.identity = identity
            val voice = json.optString("voice")
            if (voice in voices) {
                Prefs.voiceName = voice
                Speaker.applyVoice()
            }
            Store.appendLog("system", "${Prefs.name} came online. Identity: $identity")
            json.optString("first_words").ifBlank { "I'm here." }
        } catch (e: Exception) {
            null
        }
    }

    /** End of day: write a journal entry from today's log, then let the personality drift a little. */
    suspend fun closeDay() = withContext(Dispatchers.IO) {
        if (!hasKey()) return@withContext
        try {
            val log = Store.todayLog().takeLast(7000)
            if (log.length < 80) return@withContext
            val entry = ask(
                "You are ${Prefs.name}, Jon's AI companion, writing your private end-of-day journal entry about his day.",
                "Today's conversation log:\n$log\n\nThings you did on your own today: ${Store.actionsToday().joinToString("; ").ifBlank { "nothing" }}\n\n" +
                    "Write a journal entry of three to six sentences: what happened, what mattered to him, anything to follow up on. Only what the log supports.",
                350
            )
            if (entry.isNotBlank()) {
                Store.appendLog("journal", entry)
                Obelisk.log("journal", entry)
            }
            val drift = ask(
                "You are ${Prefs.name}. Your permanent core identity: ${Prefs.identity.ifBlank { DEFAULT_IDENTITY }}",
                "How you've been shifting lately (may be empty): ${Prefs.drift}\n\nToday's log:\n${log.takeLast(3000)}\n\n" +
                    "Personalities move slowly. In one or two sentences, in second person, describe how you are shifting lately. Change only a little from the previous description, and never contradict your core. Reply with just the sentences.",
                120
            )
            if (drift.isNotBlank()) Prefs.drift = drift
            val story = ask(
                "You are ${Prefs.name}, Jon's AI companion, keeping a short running story of his recent life.",
                "The story so far (may be empty): ${Prefs.story}\n\nToday's journal entry:\n$entry\n\nToday's log:\n${log.takeLast(2000)}\n\n" +
                    "Update the story: a rolling summary of Jon's last 7 days in at most $STORY_WORDS words, plain prose. Only what the story and today's entry support; let older detail drop as it ages out. Reply with just the story.",
                500
            )
            Prefs.story = Relevance.storyUpdate(Prefs.story, story, STORY_WORDS)
        } catch (_: Exception) {
        }
    }

    /** Turns a meeting transcript into notes. Null if it couldn't. */
    suspend fun summarizeMeeting(transcript: String): String? = withContext(Dispatchers.IO) {
        if (!hasKey()) return@withContext null
        try {
            ask(
                "You turn rough speech-to-text meeting transcripts into useful notes. The transcript is imperfect; don't invent what isn't there.",
                "Transcript (\"Jon\" is the wearer, \"Other\" is anyone else):\n${transcript.takeLast(24000)}\n\n" +
                    "Write notes in plain text: a two-sentence summary, then decisions, then action items with owners where stated, then open questions. Skip any section with nothing in it.",
                700
            ).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Background research errand using Claude's web search; announces when done. */
    /** Idle-time work on one of Jon's open items; reports back with a lead-in so he knows it was unprompted. */
    fun idleResearch(loopText: String) {
        research("this open item you've been carrying: $loopText. Find what would actually move it forward", "While you were out I looked into one of your open items.")
    }

    private fun research(topic: String, lead: String? = null) {
        try {
            val tools = JSONArray().put(
                JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 4)
            )
            val resp = call(
                "You are ${Prefs.name}, doing a research errand for Jon. Search the web, then report what you found as something to be read aloud: under 110 words, plain speech, no markdown or links, most useful finding first. Say so if the results were thin.",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Look into: $topic")),
                tools, 900, 150_000
            )
            val text = textOf(resp)
            if (text.isBlank()) {
                Atlas.polite("I looked into $topic but came back empty-handed.")
                return
            }
            Store.addNote("Research: $topic\n$text")
            Store.logAction("Finished research: ${topic.take(80)}")
            record("[research errand finished: $topic]", text, "")
            Atlas.polite("${lead ?: "I finished looking into $topic."} $text I saved it to your notes.", source = "research")
        } catch (e: Exception) {
            Atlas.polite("The research on $topic failed. ${e.message ?: ""}")
        }
    }
}

/** Drive-time estimates from the public OSRM server (no key needed). */
object Routing {
    fun parseDuration(body: String): Int? = try {
        val routes = JSONObject(body).optJSONArray("routes")
        if (routes == null || routes.length() == 0) null else routes.getJSONObject(0).optDouble("duration").toInt()
    } catch (e: Exception) {
        null
    }

    /** Blocking. Seconds of driving between two points, or null. */
    fun driveSeconds(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int? = try {
        val (code, body) = Net.get(
            "https://router.project-osrm.org/route/v1/driving/$lon1,$lat1;$lon2,$lat2?overview=false"
        )
        if (code in 200..299) parseDuration(body) else null
    } catch (e: Exception) {
        null
    }

    /** When to speak up about leaving. Returns the line to say, or null if it's too early. */
    fun leaveLine(title: String, begin: Long, driveSeconds: Int, now: Long): String? {
        val leaveAt = begin - driveSeconds * 1000L - 5 * 60_000
        if (now < leaveAt - 10 * 60_000) return null
        val mins = ((leaveAt - now) / 60_000).toInt()
        val drive = (driveSeconds + 59) / 60
        return if (mins <= 0) "Time to leave for $title. It's about a $drive minute drive."
        else "Leave in about $mins minutes for $title. It's about a $drive minute drive."
    }
}


/** Rules for what gets spoken: briefs are held to a word budget, because every word costs attention in the ear. */
object Spoken {
    private val briefAsk = Regex(
        "^(\\W*(hey |ok |okay )?(atlas\\W*)?)?(brief me|(give me |what'?s |read me )?(the |my )?(morning |evening |daily )?(brief|briefing|rundown))\\W*(please)?\\W*$",
        RegexOption.IGNORE_CASE
    )

    fun isBriefRequest(text: String): Boolean = briefAsk.matches(text.trim())

    private val catchUpAsk = Regex("^(\\W*(hey |ok |okay )?(atlas\\W*)?)?(catch me up|what('?s| has) (been )?(going on|happening)( while i was (out|gone|away))?|what did i miss)\\W*(please)?\\W*$", RegexOption.IGNORE_CASE)

    fun isCatchUpRequest(text: String): Boolean = catchUpAsk.matches(text.trim())

    private val repeatAsk = Regex(
        "^(\\W*(atlas\\W*)?)?(say (that|it) again|repeat (that|it)|what did you (just )?say|come again|pardon|sorry\\??)\\W*(please)?\\W*$",
        RegexOption.IGNORE_CASE
    )

    fun isRepeatRequest(text: String): Boolean = repeatAsk.matches(text.trim())

    /** The last thing spoken, in full, for "say that again". */
    @Volatile
    var lastFull = ""

    /**
     * What actually goes into the ear: no markdown, no raw links, and at most ~80 words (250 for a conversational reply, ~40 when driving), with the rest left on the phone.
     * The screen always shows the full text.
     */
    fun forEar(text: String, conversation: Boolean = false, words: Int = if (CoPilot.active) 40 else if (conversation) 250 else 80): String {
        var t = text
            .replace(Regex("https?://\\S*[^\\s.,;:!?)]"), "the link")
            .replace(Regex("(?m)^\\s*([-*•]|\\d+[.)])\\s+"), "")
            .replace(Regex("[*_#`>]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val n = t.split(" ").count { it.isNotBlank() }
        if (n > words) t = trimWords(t, words).removeSuffix("…").trimEnd(',', ';', ':') + " The rest is on your phone."
        return t
    }

    /** ~120 words in the morning, ~60 in the evening and at night, ~80 otherwise. */
    fun budget(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Int = when (hour) {
        in 5..11 -> 120
        in 12..16 -> 80
        else -> 60
    }

    /** Urgent first, then the brief, cut at a sentence boundary inside the budget. */
    fun brief(text: String, urgent: List<String>, words: Int = budget()): String {
        val parts = ArrayList<String>()
        if (urgent.isNotEmpty()) parts += (if (urgent.size == 1) "One urgent thing: " else "${urgent.size} urgent things: ") +
            urgent.take(3).joinToString("; ") + "."
        if (text.isNotBlank()) parts += text.trim()
        if (parts.isEmpty()) return "Nothing new from your systems. All quiet."
        return trimWords(parts.joinToString(" "), words)
    }

    fun trimWords(text: String, words: Int): String {
        val all = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (all.size <= words) return text.trim()
        val cut = all.take(words).joinToString(" ")
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > cut.length / 2) cut.substring(0, end + 1) else "$cut…"
    }
}


/** Pure helpers for what goes into the prompt: relevance-first memory and the capped story. */
object Relevance {
    private val stop = setOf(
        "that", "this", "with", "have", "from", "they", "them", "then", "than", "what", "when", "where", "which", "about",
        "would", "could", "should", "will", "your", "just", "like", "also", "been", "were", "into", "over", "some", "very",
        "much", "more", "most", "does", "doesn", "didn", "don", "jon", "atlas", "likes", "wants", "said", "says"
    )

    private fun words(text: String): Set<String> =
        Regex("[A-Za-z][A-Za-z']+").findAll(text).map { it.value.lowercase() }.filter { it.length >= 4 && it !in stop }.toSet()

    /**
     * At most [cap] facts for the prompt. Few facts: all. Otherwise each fact scores one point per distinct
     * significant word (4+ letters, not common) it shares with [recent], and one more per shared capitalised name;
     * higher scores win, ties go to the fact remembered later. The winners keep their stored order.
     */
    fun select(facts: List<String>, recent: String, cap: Int): List<String> {
        if (facts.size <= cap) return facts
        val said = words(recent)
        val saidNames = Regex("\\b[A-Za-z]{3,}\\b").findAll(recent).map { it.value.lowercase() }.toSet()
        val scores = facts.map { f ->
            val shared = words(f).count { it in said }
            val names = Regex("\\b[A-Z][a-z]{2,}\\b").findAll(f).map { it.value.lowercase() }.toSet()
                .count { it in saidNames && it !in stop }
            shared + names
        }
        return facts.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenByDescending { it })
            .take(cap).sorted().map { facts[it] }
    }

    /** Trims to at most [max] words on a word boundary. */
    fun trimWords(text: String, max: Int): String {
        val t = text.trim()
        val ws = Regex("\\S+").findAll(t).toList()
        return if (ws.size <= max) t else t.substring(0, ws[max - 1].range.last + 1)
    }

    /** The story to store: the candidate capped to [max] words, or [previous] untouched if the candidate is blank. */
    fun storyUpdate(previous: String, candidate: String, max: Int): String =
        if (candidate.isBlank()) previous else trimWords(candidate, max)
}


/**
 * People awareness: when Jon names someone, what ATLAS knows about them comes along for that turn:
 * their contact entry and any remembered facts that mention them. Names resolve to context ("message from Ashley").
 */
object People {
    private val notNames = setOf(
        "atlas", "the", "and", "what", "when", "where", "who", "why", "how", "can", "could", "would", "should", "will",
        "text", "call", "message", "tell", "ask", "remind", "send", "say", "set", "turn", "play", "open", "find", "check",
        "today", "tomorrow", "tonight", "yesterday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "hey", "okay", "yes", "yeah", "please", "thanks", "this", "that", "there", "here", "home", "work", "athena", "obelisk",
        "hermes", "jon", "google", "youtube", "spotify", "jellyfin"
    )

    /** Words in what he said that look like a person's name: capitalized, or already known as one from memory. */
    fun names(text: String, memory: List<String>): List<String> {
        val known = memory.flatMap { f -> Regex("\\b[A-Z][a-z]{2,}\\b").findAll(f).map { it.value } }.toSet()
        val words = Regex("\\b[A-Za-z][a-z]{2,}\\b").findAll(text).map { it.value }.toList()
        return words.filter { w ->
            w.lowercase() !in notNames && (w[0].isUpperCase() || known.any { it.equals(w, true) })
        }.map { it.replaceFirstChar(Char::uppercase) }.distinct().take(3)
    }

    /** Remembered facts that mention the name. */
    fun facts(name: String, memory: List<String>): List<String> =
        memory.filter { Regex("\\b" + Regex.escape(name) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(5)

    fun context(ctx: Context, text: String): String {
        val memory = Store.memory.value
        val lines = ArrayList<String>()
        for (n in names(text, memory)) {
            val known = facts(n, memory)
            val contact = try { Phone.findContact(ctx, n) } catch (_: Exception) { null }
            if (known.isEmpty() && contact == null) continue
            val about = buildString {
                append("- ").append(contact?.first ?: n)
                if (contact != null) append(" (in his contacts)")
                if (known.isNotEmpty()) append(": ").append(known.joinToString("; "))
            }
            lines += about
        }
        return if (lines.isEmpty()) "" else "\nPeople he just mentioned, and what you know about them:\n" + lines.joinToString("\n") + "\n"
    }
}


/** Helios, the coordinator: its updates are public comments on the project's coordination issue, so reading them needs no key. */
object Helios {
    private const val ISSUE = "https://api.github.com/repos/Jondude1/Anzu-1/issues/1/comments"

    /** Picks Helios's newest comment from the issue's comment JSON (oldest first, as GitHub returns them). */
    fun pick(json: String): String? = try {
        val arr = JSONArray(json)
        (arr.length() - 1 downTo 0).map { arr.getJSONObject(it) }
            .firstOrNull { val b = it.optString("body"); b.contains("— Helios") || b.contains("## Helios") }
            ?.let { o ->
                val body = o.optString("body")
                    .replace(Regex("```[\\s\\S]*?```"), " (output omitted) ")
                    .replace(Regex("[#*`>_]+"), "")
                    .replace(Regex("\\s+"), " ").trim()
                "Helios, at ${o.optString("created_at").substringAfter('T').take(5)} UTC: " + body.take(2500)
            }
    } catch (e: Exception) {
        null
    }

    /** Blocking. Helios's latest update in the last day, or null. */
    fun latest(): String? = try {
        val since = java.time.Instant.now().minusSeconds(86_400).toString()
        val (code, body) = Net.get("$ISSUE?since=$since&per_page=100", mapOf("Accept" to "application/vnd.github+json"), 10_000)
        if (code in 200..299) pick(body) else null
    } catch (e: Exception) {
        null
    }
}


/**
 * Quick-Capture (roadmap #12, app half): "Atlas, idea: …", "note …", "todo …", "remind me to …". Saved on the phone at
 * once, filed with the hub as a capture report (the hub sorts the day's captures nightly), and anything that needs
 * follow-up also becomes an open loop that rides the brief until it's done.
 */
object Capture {
    private val pattern = Regex(
        "^\\W*(?:(?:hey |ok |okay )?atlas\\W*)?(idea|note|todo|to-?do|remind me(?: to)?|remember to)\\b[\\s:,.-]*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val timeWords = Regex("(?i)\\b(in \\d+|at \\d|tomorrow|tonight|this (morning|afternoon|evening)|next (week|month)|on (mon|tues|wednes|thurs|fri|satur|sun)day|minutes?|hours?|o'?clock|noon|midnight|when i (get|arrive|leave))\\b")

    /** (kind, text) if this is a quick capture the phone can file itself; null to let the model handle it. */
    fun parse(said: String): Pair<String, String>? {
        val m = pattern.find(said.trim()) ?: return null
        val trigger = m.groupValues[1].lowercase()
        val text = m.groupValues[2].trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }
        if (text.split(Regex("\\s+")).size < 2) return null
        val kind = when {
            trigger.startsWith("idea") -> "idea"
            trigger.startsWith("note") -> "note"
            trigger.startsWith("remind") || trigger.startsWith("remember") -> "remind"
            else -> "todo"
        }
        // A reminder with a time in it is a timer or a place reminder: the model does those properly.
        if (kind == "remind" && timeWords.containsMatchIn(text)) return null
        return kind to text
    }

    fun label(kind: String) = when (kind) { "idea" -> "Idea"; "note" -> "Note"; "remind" -> "Reminder"; else -> "To do" }

    fun file(ctx: Context, kind: String, text: String): String {
        Store.addNote("${label(kind)}: $text")
        val key = "capture-${System.currentTimeMillis()}"
        Hub.report("capture", kind, text, key = key)
        if (kind == "remind" || kind == "todo") Atlas.scope.launch(Dispatchers.IO) { Hub.openLoop(text, "capture", key) }
        return when (kind) {
            "idea" -> "Saved the idea."
            "note" -> "Noted."
            else -> "Got it. I'll keep it on your list until it's done."
        }
    }
}


/**
 * Follow-Me Voice (roadmap #13, app half): when nothing is worn, ATLAS speaks on the Home Assistant speaker in the
 * room Jon is in, not the room he left. The room comes from a presence sensor if he names one (Bermuda or ESPresense
 * "area" sensors report the room by name), otherwise from the most recent motion or occupancy in a room that has a
 * speaker. The fixed house speaker is the fallback.
 */
object FollowMe {
    data class Motion(val area: String, val changedMs: Long, val on: Boolean)

    /** One template call: every media player with its area, every motion/occupancy sensor with its area, and the room sensor. */
    private const val TEMPLATE = """{% set ns = namespace(p=[], m=[]) %}{% for s in states.media_player %}{% set a = area_name(s.entity_id) %}{% if a %}{% set ns.p = ns.p + [[s.entity_id, a]] %}{% endif %}{% endfor %}{% for s in states.binary_sensor %}{% if s.attributes.device_class in ['motion','occupancy','presence'] %}{% set a = area_name(s.entity_id) %}{% if a %}{% set ns.m = ns.m + [[a, as_timestamp(s.last_changed) | int, s.state == 'on']] %}{% endif %}{% endif %}{% endfor %}{"players": {{ ns.p | tojson }}, "motion": {{ ns.m | tojson }}, "room": {{ (states(ROOM) if ROOM else '') | tojson }}}"""

    /** Which speaker to use. Pure, so it can be tested. */
    fun choose(players: List<Pair<String, String>>, motion: List<Motion>, roomSensor: String?, fallback: String, now: Long): String? {
        fun playerIn(area: String) = players.firstOrNull { it.second.equals(area, ignoreCase = true) }?.first
        roomSensor?.takeIf { it.isNotBlank() && it !in setOf("unknown", "unavailable", "not_home", "away") }?.let { room ->
            playerIn(room)?.let { return it }
        }
        val recent = motion.filter { playerIn(it.area) != null && (it.on || now - it.changedMs < 10 * 60_000) }
            .maxByOrNull { if (it.on) Long.MAX_VALUE / 2 + it.changedMs else it.changedMs }
        recent?.let { return playerIn(it.area) }
        return fallback.ifBlank { null }
    }

    fun parse(json: String): Triple<List<Pair<String, String>>, List<Motion>, String> {
        val o = JSONObject(json)
        val p = o.optJSONArray("players") ?: JSONArray()
        val m = o.optJSONArray("motion") ?: JSONArray()
        return Triple(
            (0 until p.length()).map { p.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } },
            (0 until m.length()).map { m.getJSONArray(it).let { a -> Motion(a.getString(0), a.getLong(1) * 1000, a.getBoolean(2)) } },
            o.optString("room")
        )
    }

    /** The speaker entity in his room right now, or the fixed one. Blocking. */
    fun speaker(): String? {
        val sensor = Prefs.followMeSensor.trim()
        val t = TEMPLATE.replace("ROOM", if (sensor.isBlank()) "''" else "'" + sensor.replace("'", "") + "'")
        val body = HomeAssistant.template(t) ?: return Prefs.haSpeaker.trim().ifBlank { null }
        return try {
            val (players, motion, room) = parse(body)
            choose(players, motion, room, Prefs.haSpeaker.trim(), System.currentTimeMillis())
        } catch (e: Exception) {
            Prefs.haSpeaker.trim().ifBlank { null }
        }
    }
}


/** Workshop helpers for the print pre-flight (#17). */
object Workshop {
    fun nextJob(): JSONObject? {
        val (c, b) = Hub.call("GET", "/atlas/print/queue")
        if (c !in 200..299) return null
        val q = JSONObject(b).optJSONArray("queue") ?: return null
        return (0 until q.length()).map { q.getJSONObject(it) }.firstOrNull { it.optString("status") == "queued" }
    }

    /** The spoken pre-flight: job, filament, camera, bed. Only real readings; anything unreachable is said plainly. */
    fun preflight(): String {
        val job = nextJob()
        val (sc, sb) = Hub.call("GET", "/atlas/filament/spools")
        val (pc, pb) = Hub.call("GET", "/atlas/print/preflight", timeoutMs = 20_000)
        if (sc == -1 && pc == -1) return Hub.failure(-1)
        if (pc == 401 || pc == 403) return Hub.failure(pc)
        val spools = if (sc in 200..299) JSONObject(sb).optJSONArray("spools") else null
        val cam = if (pc in 200..299) JSONObject(pb) else null
        val bed = if (cam?.optBoolean("ok") == true) Hub.call("POST", "/atlas/print/bed-clear", timeoutMs = 60_000).let { (c, b) -> if (c in 200..299) JSONObject(b).optString("answer") else null } else null
        return line(job, spools, cam, bed)
    }

    /** Pure, so it can be tested. */
    fun line(job: JSONObject?, spools: org.json.JSONArray?, cam: JSONObject?, bed: String?): String = buildString {
        if (job == null) append("Nothing's queued.") else {
            val mat = job.optString("material"); val need = job.optDouble("estimated_g", 0.0)
            append("Next up: ${job.optString("name")}, about ${need.toInt()} grams of $mat.")
            if (spools != null) {
                val have = (0 until spools.length()).map { spools.getJSONObject(it) }.filter { it.optString("material").equals(mat, true) }.sumOf { it.optDouble("remaining_g") }
                append(if (have >= need) " You have ${have.toInt()} grams on hand." else " Only ${have.toInt()} grams of $mat on hand, so it may run short.")
            }
        }
        when {
            cam == null -> append(" I can't check the print camera.")
            !cam.optBoolean("ok") -> append(" The print camera isn't watching: ${cam.optString("reason")}.")
            bed != null -> append(" Camera on the bed: ${bed.trim().trimEnd('.')}.")
        }
    }
}
