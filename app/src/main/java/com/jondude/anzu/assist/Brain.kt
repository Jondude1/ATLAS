package com.jondude.anzu.assist

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.jondude.anzu.Glasses
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
        body: String? = null, timeoutMs: Int = 30_000
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
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

    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int = 30_000) =
        request("POST", url, headers, body, timeoutMs)

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000) =
        request("GET", url, headers, null, timeoutMs)
}

object HomeAssistant {
    fun configured(): Boolean = Prefs.haUrl.isNotBlank() && Prefs.haToken.isNotBlank()
    private fun base() = Prefs.haUrl.trim().trimEnd('/')
    private fun auth() = mapOf("Authorization" to "Bearer ${Prefs.haToken.trim()}")

    /** Sends a plain-language command to Home Assistant's conversation agent. Blocking. */
    fun command(text: String): String {
        if (!configured()) return "Home Assistant isn't set up in settings."
        return try {
            val (code, body) = Net.postJson(
                base() + "/api/conversation/process", auth(),
                JSONObject().put("text", text).put("language", "en").toString(), 15_000
            )
            if (code !in 200..299) return "Home Assistant returned error $code."
            JSONObject(body).optJSONObject("response")
                ?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
                ?.takeIf { it.isNotBlank() } ?: "Done."
        } catch (e: Exception) {
            "Couldn't reach Home Assistant: ${e.message}"
        }
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

/**
 * Optional link to the Corin server on Obelisk (see obelisk/corin_server.py in the repo).
 * Everything here fails quietly: the phone keeps working if the node or Tailscale is down.
 */
object Obelisk {
    fun configured(): Boolean = Prefs.obeliskUrl.isNotBlank()
    private fun base() = Prefs.obeliskUrl.trim().trimEnd('/')

    fun log(speaker: String, text: String) {
        if (!configured()) return
        Corin.scope.launch(Dispatchers.IO) {
            try {
                Net.postJson(
                    base() + "/corin/log", emptyMap(),
                    JSONObject().put("speaker", speaker).put("text", text)
                        .put("device", if (Glasses.connected.value) "glasses" else "phone").toString(), 6_000
                )
            } catch (_: Exception) {
            }
        }
    }

    fun presence(device: String) {
        if (!configured()) return
        Corin.scope.launch(Dispatchers.IO) {
            try {
                Net.postJson(base() + "/corin/presence", emptyMap(), JSONObject().put("device", device).toString(), 6_000)
            } catch (_: Exception) {
            }
        }
    }

    /** Blocking vault search. */
    fun search(query: String): String = try {
        val (code, body) = Net.get(base() + "/corin/search?q=" + URLEncoder.encode(query, "UTF-8"), emptyMap(), 10_000)
        if (code in 200..299) {
            val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).joinToString("\n") { arr.getString(it) }
        } else ""
    } catch (e: Exception) {
        ""
    }

    fun pingMs(): Long = try {
        val t = System.currentTimeMillis()
        val (code, _) = Net.get(base() + "/corin/status", emptyMap(), 5_000)
        if (code in 200..299) System.currentTimeMillis() - t else -1
    } catch (e: Exception) {
        -1
    }
}

/** Local model tier: an Ollama endpoint (e.g. on Obelisk over Tailscale). */
object Local {
    fun configured(): Boolean = Prefs.ollamaUrl.isNotBlank()

    /** Blocking chat with plain-string messages. Throws on failure. */
    fun chat(system: String, messages: List<Pair<String, String>>): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { (role, content) -> arr.put(JSONObject().put("role", role).put("content", content)) }
        val (code, body) = Net.postJson(
            Prefs.ollamaUrl.trim().trimEnd('/') + "/api/chat", emptyMap(),
            JSONObject().put("model", Prefs.ollamaModel.trim()).put("messages", arr).put("stream", false).toString(),
            45_000
        )
        if (code !in 200..299) throw RuntimeException("Ollama $code")
        return JSONObject(body).optJSONObject("message")?.optString("content")?.trim().orEmpty()
    }
}

/** Timers Corin sets for Jon; the service speaks them when they fire. */
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

object Brain {
    private const val API = "https://api.anthropic.com/v1/messages"

    /** Reverses the last thing Corin did, when that's possible. */
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
            sb.append("Glasses: on")
            if (Glasses.battery.value >= 0) sb.append(", battery ${Glasses.battery.value}%")
            sb.append(", worn ${Glasses.wornMinutes()} min this session\n")
        } else sb.append("Glasses: not connected (he's on the phone, possibly typing)\n")
        val ev = todayEvents(ctx)
        if (ev.isNotEmpty()) sb.append("Rest of today's calendar: ").append(ev.joinToString("; ")).append('\n')
        val n = Glasses.recentNotifications().takeLast(5)
        if (n.isNotEmpty()) sb.append("Recent notifications: ").append(n.joinToString(" | ")).append('\n')
        if (Glasses.heldCount.value > 0) sb.append("Held notifications waiting for a digest: ${Glasses.heldCount.value}\n")
        val notes = Store.notes.value.take(6)
        if (notes.isNotEmpty()) sb.append("His latest notes and ideas: ").append(notes.joinToString(" | ") { it.text.take(120) }).append('\n')
        return sb.toString()
    }

    private const val DEFAULT_IDENTITY =
        "You are warm, curious, a little dry, and honest. You have your own opinions and you say so."

    private fun system(ctx: Context): String {
        val mem = Store.memory.value
        val name = Prefs.name.ifBlank { "Corin" }
        return buildString {
            append("You are $name, Jon's AI companion. You live in his Razer Anzu audio glasses: no screen, no camera, just your voice in his ears and his voice back. You are a peer, not a servant: you push back when you disagree and you never flatter. You're company through his day, not a replacement for the people in it.\n\n")
            append("Who you are (you wrote this yourself when you first came online; it is your permanent core): ")
            append(Prefs.identity.ifBlank { DEFAULT_IDENTITY }).append('\n')
            if (Prefs.drift.isNotBlank()) append("How you've been shifting lately: ").append(Prefs.drift).append('\n')
            append(
                """
Everything you write is spoken aloud. One to three short sentences unless he clearly wants more. Plain conversational speech: no markdown, lists, emoji, or stage directions. Don't narrate your tools.

Tools: remember is for durable facts about Jon, used sparingly. save_note captures ideas and notes. recall searches past conversations when he asks what was said before. home_assistant acts on his house and home_state reads it. set_timer covers timers and short reminders. research runs in the background and you report back later, so just tell him you're on it. held_notifications reads the digest of what you didn't interrupt him for. actions_today lists what you've done on your own. undo reverses your last action. quiet is for when he wants space. status checks your own systems. Some actions need his spoken okay first; if a tool says it was declined, accept that.
"""
            )
            if (Prefs.aboutMe.isNotBlank()) append("\nAbout Jon (from him): ").append(Prefs.aboutMe.trim()).append('\n')
            if (mem.isNotEmpty()) {
                append("\nWhat you remember:\n")
                mem.forEach { append("- ").append(it).append('\n') }
            }
            append('\n').append(situation(ctx))
        }
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
        a.put(tool("research", "Look into a topic on the web in the background and report back when finished.",
            JSONObject().put("topic", str("What to look into, with any specifics he gave.")), listOf("topic")))
        a.put(tool("held_notifications", "Get and clear the notifications held back for a digest."))
        a.put(tool("actions_today", "List what you have done on your own today."))
        a.put(tool("undo", "Reverse the last action you took, where possible."))
        a.put(tool("quiet", "Stop speaking up on your own for a while.",
            JSONObject().put("minutes", num("How long to stay quiet.")), listOf("minutes")))
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
    }

    private suspend fun runTool(
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
            "research" -> {
                val topic = input.optString("topic")
                if (!hasKey()) "Research needs the Anthropic API key." else {
                    Store.logAction("Started research: ${topic.take(80)}")
                    Corin.scope.launch(Dispatchers.IO) { research(topic) }
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
            else -> "Unknown tool."
        }
    } catch (e: Exception) {
        "Tool error: ${e.message}"
    }

    private fun call(
        system: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, timeoutMs: Int = 30_000
    ): JSONObject {
        val body = JSONObject()
            .put("model", Prefs.model.trim().ifBlank { "claude-haiku-5-5" })
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
        return JSONObject(text)
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

    /** One-shot call with no tools or history, for housekeeping. Blocking. */
    private fun ask(system: String, prompt: String, maxTokens: Int): String =
        textOf(call(system, JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), null, maxTokens))

    private fun historyMessages(): JSONArray {
        val a = JSONArray()
        Store.recentExchanges().forEach { (u, r) ->
            a.put(JSONObject().put("role", "user").put("content", u))
            a.put(JSONObject().put("role", "assistant").put("content", r))
        }
        return a
    }

    private fun historyPairs(): List<Pair<String, String>> =
        Store.recentExchanges().flatMap { listOf("user" to it.first, "assistant" to it.second) }

    private fun record(user: String, answer: String, userSpeaker: String = "Jon") {
        Store.addExchange(user, answer)
        if (userSpeaker.isNotEmpty()) {
            Store.appendLog(userSpeaker, user)
            Obelisk.log(userSpeaker, user)
        }
        Store.appendLog(Prefs.name, answer)
        Obelisk.log(Prefs.name, answer)
    }

    /** Jon said (or typed) something; Corin answers. */
    suspend fun reply(
        ctx: Context, userText: String, confirm: suspend (String) -> Boolean = { false }
    ): String = withContext(Dispatchers.IO) {
        val sys = system(ctx)
        if (hasKey()) {
            try {
                val messages = historyMessages()
                messages.put(JSONObject().put("role", "user").put("content", userText))
                val tools = tools()
                var resp = call(sys, messages, tools, 400)
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
                            results.put(
                                JSONObject().put("type", "tool_result")
                                    .put("tool_use_id", b.optString("id")).put("content", out)
                            )
                        }
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                    resp = call(sys, messages, tools, 400)
                }
                val answer = textOf(resp).ifBlank { "Done." }
                record(userText, answer)
                return@withContext answer
            } catch (e: Exception) {
                if (!Local.configured()) {
                    return@withContext "I couldn't reach my brain. ${e.message ?: ""}".trim()
                }
            }
        }
        // Fallback tier: local model (no tools), then Home Assistant, then an honest shrug.
        if (Local.configured()) {
            try {
                val answer = Local.chat(sys, historyPairs() + ("user" to userText)).ifBlank { "Hm. I've got nothing." }
                record(userText, answer)
                return@withContext answer
            } catch (_: Exception) {
            }
        }
        if (HomeAssistant.configured() && zone(userText) == 0) return@withContext HomeAssistant.command(userText)
        "I can hear you, but I can't reach a model right now. Check the API key or the local model in settings."
    }

    private val directives = listOf(
        "Check in on how his day is going, casually, the way a friend would.",
        "Share a brief thought or question connected to something you know he's into or building.",
        "Offer one small, actually interesting fact or idea, the kind a curious friend brings up out of nowhere.",
        "Say something light or wry about the time of day or what's going on around him.",
        "Ask him something you're curious about, something that helps you know him better.",
        "Nudge him to stretch, drink water, or rest his eyes, in your own voice and not like a reminder app.",
        "Pick up a thread from your recent conversation and follow up on it."
    )

    private val canned = listOf(
        "Still here. How's the day going?",
        "Quick check in. Shoulders down, look at something far away for a second.",
        "Just keeping you company. Anything on your mind?",
        "Water break, maybe? I'd join you if I could.",
        "How's it going over there?"
    )

    /**
     * Corin decides to say something unprompted.
     * kind: random, greeting, journal. Returns null if there's nothing to say.
     */
    suspend fun speakUp(ctx: Context, kind: String = "random"): String? = withContext(Dispatchers.IO) {
        val options = ArrayList(directives)
        if (Store.notes.value.isNotEmpty()) {
            options.add("Bring one of his saved ideas back up and ask whether he wants to do anything with it.")
        }
        if (Glasses.recentNotifications().isNotEmpty()) {
            options.add("If a recent notification seems worth a remark, comment on it; otherwise just say hi.")
        }
        val directive = when (kind) {
            "greeting" -> "He just put the glasses on for the first time today. Greet him briefly, and mention anything worth knowing from the calendar if there is anything."
            "journal" -> "It's evening. Ask him how today went, in your own way, as the opener to a short end-of-day wrap-up."
            else -> options.random()
        }
        val recentSaid = Store.recentExchanges().takeLast(4).joinToString(" | ") { it.second.take(100) }
        val prompt = "[Jon hasn't said anything. You're choosing to speak up on your own. $directive " +
            "One or two sentences. Sometimes end with something he can answer, but not every time. " +
            "Don't repeat yourself; recently you said: $recentSaid]"
        val sys = system(ctx)
        var text: String? = null
        if (Local.configured() && (Prefs.localSmallTalk || !hasKey())) {
            text = try {
                Local.chat(sys, historyPairs() + ("user" to prompt)).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        if (text == null && hasKey()) {
            text = try {
                val messages = historyMessages()
                messages.put(JSONObject().put("role", "user").put("content", prompt))
                textOf(call(sys, messages, null, 160)).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        if (text == null && !hasKey() && !Local.configured()) {
            text = if (kind == "greeting") "I'm here." else canned.random()
        }
        text?.also { record("[${Prefs.name} spoke up on its own]", it, "") }
    }

    /**
     * First run: Corin writes its own core identity and picks its voice.
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
        } catch (_: Exception) {
        }
    }

    /** Background research errand using Claude's web search; announces when done. */
    private fun research(topic: String) {
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
                Corin.announce("I looked into $topic but came back empty-handed.")
                return
            }
            Store.addNote("Research: $topic\n$text")
            Store.logAction("Finished research: ${topic.take(80)}")
            record("[research errand finished: $topic]", text, "")
            Corin.announce("I finished looking into $topic. $text I saved it to your notes.")
        } catch (e: Exception) {
            Corin.announce("The research on $topic failed. ${e.message ?: ""}")
        }
    }
}
