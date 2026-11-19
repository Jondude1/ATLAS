package com.jondude.anzu

import com.jondude.anzu.assist.Producers
import com.jondude.anzu.assist.Producers.CalEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProducersTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private fun ev(id: Long, startMs: Long, allDay: Boolean = false) = CalEvent(id, "Dentist", startMs, "Main St", allDay)

    @Test
    fun calendarPostsInWindowEventOnce() {
        val e = ev(1, now + 20 * min)
        assertEquals(listOf(e), Producers.selectCalendar(listOf(e), now, emptySet()))
        val d = Producers.calendarDraft(e)
        assertEquals("cal:1:${e.startMs}", d.dedupeKey)
        assertEquals("event_soon", d.kind)
        assertEquals("Main St", d.payload.getString("location"))
    }

    @Test
    fun calendarSkipsOutOfWindowAndAllDay() {
        val events = listOf(ev(1, now + 45 * min), ev(2, now - 5 * min), ev(3, now + 10 * min, allDay = true))
        assertTrue(Producers.selectCalendar(events, now, emptySet()).isEmpty())
    }

    @Test
    fun calendarSkipsAlreadyPosted() {
        val e = ev(1, now + 20 * min)
        assertTrue(Producers.selectCalendar(listOf(e), now, setOf("1:${e.startMs}")).isEmpty())
        // A moved occurrence of the same event is a new one.
        assertEquals(1, Producers.selectCalendar(listOf(ev(1, now + 25 * min)), now, setOf("1:${e.startMs}")).size)
    }

    @Test
    fun batteryFiresOnlyOnTheEdge() {
        assertTrue(Producers.batteryEdge(35, false, 20, false))   // crossing down
        assertTrue(Producers.batteryEdge(15, true, 18, false))    // was charging, now unplugged and low
        assertFalse(Producers.batteryEdge(18, false, 15, false))  // staying low
        assertFalse(Producers.batteryEdge(35, false, 20, true))   // charging
        assertFalse(Producers.batteryEdge(35, false, 21, false))  // still above
        assertFalse(Producers.batteryEdge(-1, false, 10, false))  // no earlier reading
    }

    @Test
    fun batteryDedupeKeyIsPerDay() {
        val d = Producers.batteryDraft(19, now)
        assertTrue(d.dedupeKey.matches(Regex("dev:battery:\\d{4}-\\d{2}-\\d{2}")))
        assertEquals("Phone battery at 19%", d.summary)
    }

    private fun states(vararg p: Pair<String, String>) = mapOf(*p)

    @Test
    fun personInFamilyRoomFires() {
        val prev = states("binary_sensor.family_room_person_occupancy" to "off")
        val cur = states("binary_sensor.family_room_person_occupancy" to "on")
        val out = Producers.selectHouse(prev, cur, true, now)
        assertEquals(1, out.size)
        assertEquals("frigate", out[0].source)
        assertEquals("person_seen", out[0].kind)
        assertEquals("Person seen in the family room", out[0].summary)
        assertEquals("frigate:family_room:${now / (15 * min)}", out[0].dedupeKey)
    }

    @Test
    fun personInAnyOtherRoomDoesNot() {
        val prev = states("binary_sensor.night_watch_person_occupancy" to "off", "binary_sensor.garage_person_occupancy" to "off")
        val cur = states("binary_sensor.night_watch_person_occupancy" to "on", "binary_sensor.garage_person_occupancy" to "on")
        assertTrue(Producers.selectHouse(prev, cur, false, now).isEmpty())
        // And the extractor never admits them to the watched set.
        val arr = JSONArray().put(JSONObject().put("entity_id", "binary_sensor.night_watch_person_occupancy").put("state", "on"))
        assertTrue(Producers.watched(arr).isEmpty())
    }

    @Test
    fun doorWhileHomeDoesNotButAwayDoes() {
        val prev = states("binary_sensor.front_door" to "off")
        val cur = states("binary_sensor.front_door" to "on")
        assertTrue(Producers.selectHouse(prev, cur, true, now).isEmpty())
        val away = Producers.selectHouse(prev, cur, false, now)
        assertEquals(1, away.size)
        assertEquals("door_opened", away[0].kind)
        assertEquals("home_assistant", away[0].source)
        assertEquals("ha:binary_sensor.front_door:${now / (15 * min)}", away[0].dedupeKey)
    }

    @Test
    fun lockUnlockedWhileAway() {
        val prev = states("lock.front_door" to "locked")
        val cur = states("lock.front_door" to "unlocked")
        assertTrue(Producers.selectHouse(prev, cur, true, now).isEmpty())
        assertEquals("lock_unlocked", Producers.selectHouse(prev, cur, false, now).single().kind)
        // Already unlocked at the last poll, or never seen before: no event.
        assertTrue(Producers.selectHouse(cur, cur, false, now).isEmpty())
        assertTrue(Producers.selectHouse(emptyMap(), cur, false, now).isEmpty())
    }

    @Test
    fun presenceAndWatchedExtraction() {
        fun e(id: String, st: String, dc: String = "") = JSONObject().put("entity_id", id).put("state", st)
            .put("attributes", JSONObject().put("device_class", dc))
        val arr = JSONArray().put(e("person.jenna", "not_home")).put(e("person.jon", "not_home"))
            .put(e("binary_sensor.front_door", "off", "door")).put(e("binary_sensor.hall_motion", "on", "motion"))
            .put(e("lock.front_door", "locked")).put(e("binary_sensor.living_room_person_occupancy", "off"))
        assertFalse(Producers.anyoneHome(arr))
        assertEquals(setOf("binary_sensor.front_door", "lock.front_door", "binary_sensor.living_room_person_occupancy"), Producers.watched(arr).keys)
        assertTrue(Producers.anyoneHome(JSONArray())) // no people to ask: assume someone is home
    }
}
