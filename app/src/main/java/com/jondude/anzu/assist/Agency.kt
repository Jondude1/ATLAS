package com.jondude.anzu.assist

import android.content.Context
import com.jondude.anzu.Prefs
import com.jondude.anzu.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** "Whenever X happens, do Y": saved orders that fire when a matching line goes past. They can only use tools that don't need a spoken okay. */
object StandingOrders {
    data class Order(val id: Long, val trigger: String, val action: String, val lastFired: Long = 0L)

    private const val KEY = "standingOrders"
    private val stop = setOf("the", "and", "when", "whenever", "that", "this", "with", "for", "are", "was", "has", "have", "then", "from", "into", "after", "before")

    fun load(): List<Order> = try {
        val a = JSONArray(Prefs.sp.getString(KEY, "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Order(o.optLong("id"), o.optString("trigger"), o.optString("action"), o.optLong("last")) }
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<Order>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("trigger", it.trigger).put("action", it.action).put("last", it.lastFired)) }
        Prefs.sp.edit().putString(KEY, a.toString()).apply()
    }

    fun add(trigger: String, action: String): Order {
        val o = Order(System.currentTimeMillis(), trigger.trim().take(120), action.trim().take(240))
        save(load() + o)
        return o
    }

    fun remove(which: String): Boolean {
        val l = load()
        val w = which.trim().lowercase()
        val hit = l.firstOrNull { it.id.toString() == w || it.trigger.lowercase().contains(w) || it.action.lowercase().contains(w) } ?: return false
        save(l - hit)
        return true
    }

    /** Every meaningful word of the trigger appears in the event line. */
    fun matches(trigger: String, event: String): Boolean {
        val words = trigger.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }
        val e = event.lowercase()
        return words.isNotEmpty() && words.all { e.contains(it) }
    }

    fun due(event: String, now: Long = System.currentTimeMillis(), cooldownMs: Long = 600_000L): List<Order> =
        load().filter { now - it.lastFired > cooldownMs && matches(it.trigger, event) }

    fun onEvent(event: String, source: String) {
        if (source == "order" || event.isBlank()) return
        val ctx = Brain.appContext ?: return
        val hits = due(event)
        if (hits.isEmpty()) return
        val now = System.currentTimeMillis()
        save(load().map { o -> if (hits.any { it.id == o.id }) o.copy(lastFired = now) else o })
        hits.forEach { o ->
            Atlas.scope.launch(Dispatchers.IO) {
                val said = try {
                    Brain.reply(ctx, "Standing order. Jon told you: \"whenever ${o.trigger}, ${o.action}\". This just happened: \"${event.take(200)}\". Carry it out now with your tools. Do only what the order says.") { false }
                } catch (e: Exception) { "" }
                Store.logAction("Standing order fired: ${o.trigger.take(50)}")
                if (said.isNotBlank() && said.length < 140) Atlas.polite(said, source = "order")
            }
        }
    }
}

/** Per-tool authority: free (default), ask (needs a spoken okay) or never. Set by voice. */
object Authority {
    const val FREE = "free"; const val ASK = "ask"; const val NEVER = "never"
    private const val KEY = "authority"

    private fun map(): JSONObject = try { JSONObject(Prefs.sp.getString(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    fun level(tool: String): String = map().optString(tool, FREE)

    fun set(tool: String, level: String): Boolean {
        val l = level.trim().lowercase()
        if (l !in listOf(FREE, ASK, NEVER)) return false
        Prefs.sp.edit().putString(KEY, map().put(tool.trim(), l).toString()).apply()
        return true
    }

    /** These can't be locked, or Jon could lock himself out of changing the locks. */
    val exempt = setOf("set_authority", "undo", "standing_order")
}

/** What Jon tends to ask for at what time of day, so a quiet card can offer it. */
object Habits {
    private const val KEY = "habits"
    private val watched = setOf("weather", "agenda", "home_assistant", "media", "navigate", "run_routine", "protocol", "status", "prep_day", "sweep", "open_loops")
    @Volatile var offered: Pair<String, String>? = null
    @Volatile private var offeredAt = 0L
    private val cooldown = HashMap<String, Long>()

    private fun data(): JSONObject = try { JSONObject(Prefs.sp.getString(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    fun dayOf(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    fun minuteOf(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

    fun note(tool: String, args: JSONObject, now: Calendar = Calendar.getInstance()) {
        if (tool !in watched) return
        val d = data()
        val arr = d.optJSONArray(tool) ?: JSONArray()
        arr.put(JSONObject().put("d", dayOf(now)).put("m", minuteOf(now)).put("a", args.toString().take(300)))
        val keep = JSONArray()
        for (i in maxOf(0, arr.length() - 40) until arr.length()) keep.put(arr.get(i))
        Prefs.sp.edit().putString(KEY, d.put(tool, keep).toString()).apply()
    }

    /** A tool used on three or more different days within 30 minutes of this time, and not yet today. Pure over [d]. */
    fun candidate(d: JSONObject, now: Calendar, window: Int = 30): Pair<String, String>? {
        val today = dayOf(now); val m = minuteOf(now)
        for (tool in d.keys()) {
            val arr = d.optJSONArray(tool) ?: continue
            val hits = (0 until arr.length()).map { arr.getJSONObject(it) }
            val days = hits.filter { Math.abs(it.optInt("m") - m) <= window && it.optInt("d") != today }.map { it.optInt("d") }.toSet()
            val usedToday = hits.any { it.optInt("d") == today }
            if (days.size >= 3 && !usedToday) return tool to (hits.lastOrNull { Math.abs(it.optInt("m") - m) <= window }?.optString("a") ?: "{}")
        }
        return null
    }

    fun suggest(now: Calendar = Calendar.getInstance()): Pair<String, String>? {
        val c = candidate(data(), now) ?: return null
        val t = System.currentTimeMillis()
        if (t - (cooldown[c.first] ?: 0L) < 6 * 3_600_000L) return null
        cooldown[c.first] = t
        offered = c; offeredAt = t
        return c
    }

    /** "Do it" within five minutes of an offer accepts it. */
    fun accept(said: String): String? {
        val o = offered ?: return null
        if (System.currentTimeMillis() - offeredAt > 300_000L) return null
        if (!Regex("(?i)^\\W*(do it|yes|yeah|go ahead|sure|please|yes please)\\W*$").matches(said.trim())) return null
        offered = null
        return "[Jon accepted your suggestion: call ${o.first} with ${o.second}] $said"
    }
}

/** Background jobs Atlas has out right now (Hermes, Helios, research), for the live status strip. */
object Jobs {
    data class Job(val id: Long, val label: String, val startedAt: Long)
    val running = MutableStateFlow<List<Job>>(emptyList())

    fun start(label: String): Long {
        val j = Job(System.nanoTime(), label.take(24), System.currentTimeMillis())
        running.value = running.value + j
        return j.id
    }

    fun finish(id: Long) { running.value = running.value.filter { it.id != id } }

    fun strip(jobs: List<Job>, now: Long = System.currentTimeMillis()): String =
        jobs.joinToString("  ") { j ->
            val s = ((now - j.startedAt) / 1000).coerceAtLeast(0)
            "▸ ${j.label.uppercase()} " + if (s < 90) "${s}s" else "${s / 60}m"
        }
}

/** Built-in modes. Anything else falls through to Jon's own named routines. */
object Protocols {
    data class Step(val tool: String, val args: JSONObject)

    fun steps(name: String): List<Step>? = when (name.trim().lowercase()) {
        "heads down", "focus", "heads-down" -> listOf(Step("quiet", JSONObject().put("minutes", 120)))
        "house party", "party", "company" -> listOf(Step("guests", JSONObject().put("hours", 6)))
        "road trip", "driving" -> listOf(Step("driving", JSONObject().put("driving", true)))
        "all clear", "stand down", "normal" -> listOf(
            Step("quiet", JSONObject().put("minutes", 0)),
            Step("guests", JSONObject().put("hours", 0)),
            Step("driving", JSONObject().put("driving", false))
        )
        else -> null
    }
}

/** Small repairs Atlas does itself, then tells Jon about on his action list. */
object SelfFix {
    private var last = 0L

    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - last < 300_000L) return
        last = now
        try {
            if (com.jondude.anzu.overlay.OverlayService.isEnabled(ctx) && !com.jondude.anzu.overlay.OverlayService.isRunning) {
                com.jondude.anzu.overlay.OverlayService.ensureRunning(ctx)
                Store.logAction("Self-repair: restarted the overlay")
            }
        } catch (_: Throwable) {}
    }
}

/** Called from the service tick. */
object Agency {
    private var lastHabit = 0L

    fun tick(ctx: Context) {
        SelfFix.tick(ctx)
        val now = System.currentTimeMillis()
        if (now - lastHabit < 60_000L) return
        lastHabit = now
        if (Discretion.current() != null || Atlas.busy) return
        val s = Habits.suggest() ?: return
        com.jondude.anzu.overlay.OverlayService.card("Usually about now", listOf("You often ask for: ${s.first.replace('_', ' ')}", "Say \"do it\" and I will."))
    }
}
