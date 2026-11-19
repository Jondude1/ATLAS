package com.jondude.anzu.assist

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.CalendarContract
import com.jondude.anzu.Prefs
import com.jondude.anzu.has
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The proactive producers (Life OS Phase 3). They only REGISTER triggers on the hub; the decision and any delivery stay in
 * [Proactive]. Dedupe keys identify one event occurrence, never a recurring condition.
 */
object Producers {
    /** A trigger to register, before it is posted. */
    data class Draft(val source: String, val kind: String, val dedupeKey: String, val summary: String, val payload: JSONObject)

    data class CalEvent(val id: Long, val title: String, val startMs: Long, val location: String, val allDay: Boolean)

    const val CAL_WINDOW_MS = 30 * 60_000L
    const val BATTERY_LOW = 20
    const val BUCKET_MS = 15 * 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val CAL_KEY = "producerCalPosted"
    private const val BAT_KEY = "producerBatteryLast"
    private const val HOUSE_KEY = "producerHouseLast"

    /** The only cameras the house producer will ever watch (Frigate entity names). Night Watch is deliberately absent. */
    val FRIGATE_ROOMS = mapOf("living_room" to "the living room", "family_room" to "the family room")
    private val frigatePerson = Regex("^binary_sensor\\.(living_room|family_room)_person(_occupancy|_motion|_detected)?$")

    private val running = AtomicBoolean(false)

    /** Blocking. One pass over every producer, each isolated so one failure never stops the others. */
    fun runOnce(ctx: Context) {
        if (!Hub.configured() || !running.compareAndSet(false, true)) return
        try {
            try { calendar(ctx) } catch (_: Exception) {}
            try { device(ctx) } catch (_: Exception) {}
            try { house() } catch (_: Exception) {}
        } finally {
            running.set(false)
        }
    }

    // ---- Calendar ----

    private fun calKey(e: CalEvent) = "${e.id}:${e.startMs}"

    /** Timed events starting in the next 30 minutes that haven't been posted yet. Pure. */
    fun selectCalendar(events: List<CalEvent>, now: Long, posted: Set<String>): List<CalEvent> =
        events.filter { !it.allDay && it.startMs > now && it.startMs - now <= CAL_WINDOW_MS && calKey(it) !in posted }

    fun calendarDraft(e: CalEvent): Draft {
        val at = SimpleDateFormat("h:mm a", Locale.US).format(Date(e.startMs))
        val payload = JSONObject().put("title", e.title).put("start", e.startMs)
        if (e.location.isNotBlank()) payload.put("location", e.location)
        return Draft("calendar", "event_soon", "cal:${e.id}:${e.startMs}", "${e.title} at $at", payload)
    }

    private fun readEvents(ctx: Context, now: Long): List<CalEvent> {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + CAL_WINDOW_MS)
        val out = ArrayList<CalEvent>()
        ctx.contentResolver.query(
            b.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
            ),
            null, null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(CalEvent(c.getLong(0), c.getString(1)?.trim().orEmpty().ifBlank { "An event" }, c.getLong(2), c.getString(4)?.trim().orEmpty(), c.getInt(3) != 0))
            }
        }
        return out
    }

    private fun loadPosted(now: Long): MutableMap<String, Long> = try {
        val o = JSONObject(Prefs.sp.getString(CAL_KEY, "{}"))
        o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { now - it < DAY_MS }.toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }

    private fun calendar(ctx: Context) {
        if (!ctx.has(Manifest.permission.READ_CALENDAR)) return
        val now = System.currentTimeMillis()
        val posted = loadPosted(now)
        var changed = false
        for (e in selectCalendar(readEvents(ctx, now), now, posted.keys)) {
            val d = calendarDraft(e)
            if (post(d)) {
                posted[calKey(e)] = e.startMs
                changed = true
            }
        }
        if (changed) {
            val o = JSONObject()
            posted.forEach { (k, v) -> o.put(k, v) }
            Prefs.sp.edit().putString(CAL_KEY, o.toString()).apply()
        }
    }

    // ---- Device ----

    /** True only on the edge: now low and not charging, having been above the line or charging at the last check. [prevPct] -1 means unknown. */
    fun batteryEdge(prevPct: Int, prevCharging: Boolean, pct: Int, charging: Boolean): Boolean =
        prevPct >= 0 && pct in 0..BATTERY_LOW && !charging && (prevPct > BATTERY_LOW || prevCharging)

    fun batteryDraft(pct: Int, now: Long): Draft = Draft(
        "device", "battery_low", "dev:battery:" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)),
        "Phone battery at $pct%", JSONObject().put("percent", pct)
    )

    private fun device(ctx: Context) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val last = try { JSONObject(Prefs.sp.getString(BAT_KEY, "{}")) } catch (e: Exception) { JSONObject() }
        val fire = batteryEdge(last.optInt("pct", -1), last.optBoolean("charging"), pct, charging)
        // A failed post leaves the old state in place, so the crossing is retried on the next pass.
        if (fire && !post(batteryDraft(pct, System.currentTimeMillis()))) return
        Prefs.sp.edit().putString(BAT_KEY, JSONObject().put("pct", pct).put("charging", charging).toString()).apply()
    }

    // ---- House (Home Assistant + Frigate) ----

    /** The watched slice of one states fetch: Frigate person sensors for the two rooms, door contacts, locks. Entity id to state. */
    fun watched(states: JSONArray): Map<String, String> {
        val out = HashMap<String, String>()
        for (i in 0 until states.length()) {
            val o = states.optJSONObject(i) ?: continue
            val id = o.optString("entity_id")
            val state = o.optString("state")
            val dc = o.optJSONObject("attributes")?.optString("device_class").orEmpty()
            if (frigatePerson.matches(id) || id.startsWith("lock.") || (id.startsWith("binary_sensor.") && dc == "door")) out[id] = state
        }
        return out
    }

    /** Nobody home only when there are people to ask and none of them is home; no person entities means unknown, so someone is assumed home. */
    fun anyoneHome(states: JSONArray): Boolean {
        var people = 0
        for (i in 0 until states.length()) {
            val o = states.optJSONObject(i) ?: continue
            if (!o.optString("entity_id").startsWith("person.")) continue
            people++
            if (o.optString("state") == "home") return true
        }
        return people == 0
    }

    private fun bucket(now: Long) = now / BUCKET_MS

    /** Edges between two polls of [watched] states. A new entity (no previous state) is a baseline, never an event. Pure. */
    fun selectHouse(prev: Map<String, String>, cur: Map<String, String>, anyoneHome: Boolean, now: Long): List<Draft> {
        val out = ArrayList<Draft>()
        for ((id, state) in cur) {
            val before = prev[id] ?: continue
            val name = id.substringAfter('.')
            val person = frigatePerson.matchEntire(id)
            when {
                person != null -> if (state == "on" && before != "on") {
                    val cam = person.groupValues[1]
                    out.add(
                        Draft(
                            "frigate", "person_seen", "frigate:$cam:${bucket(now)}", "Person seen in ${FRIGATE_ROOMS.getValue(cam)}",
                            JSONObject().put("camera", cam).put("entity", id)
                        )
                    )
                }
                id.startsWith("lock.") -> if (state == "unlocked" && before != "unlocked" && !anyoneHome) {
                    out.add(Draft("home_assistant", "lock_unlocked", "ha:$id:${bucket(now)}", "$name unlocked while nobody is home", JSONObject().put("entity", id)))
                }
                // Doors only: a person sensor from any other room must never be read as a door, even if a raw map reaches here.
                else -> if (id.startsWith("binary_sensor.") && !id.contains("_person") && state == "on" && before != "on" && !anyoneHome) {
                    out.add(Draft("home_assistant", "door_opened", "ha:$id:${bucket(now)}", "$name opened while nobody is home", JSONObject().put("entity", id)))
                }
            }
        }
        return out
    }

    private fun house() {
        if (!HomeAssistant.configured()) return
        val states = HomeAssistant.states() ?: return
        val cur = watched(states)
        val prev: Map<String, String>? = try {
            Prefs.sp.getString(HOUSE_KEY, null)?.let { s -> JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }
        } catch (e: Exception) {
            null
        }
        val drafts = if (prev == null) emptyList() else selectHouse(prev, cur, anyoneHome(states), System.currentTimeMillis())
        // Every watched entity advances to its new state; one whose post failed keeps its old state so the edge is retried.
        val next = HashMap(cur)
        for (d in drafts) if (!post(d)) d.payload.optString("entity").takeIf { it.isNotEmpty() }?.let { id -> prev?.get(id)?.let { next[id] = it } }
        val o = JSONObject()
        next.forEach { (k, v) -> o.put(k, v) }
        Prefs.sp.edit().putString(HOUSE_KEY, o.toString()).apply()
    }

    private fun post(d: Draft): Boolean = Hub.postTrigger(d.source, d.kind, d.dedupeKey, d.summary, d.payload)
}
