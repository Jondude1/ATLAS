package com.jondude.anzu

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.jondude.anzu.assist.Brain
import com.jondude.anzu.assist.HomeWatch
import com.jondude.anzu.assist.Routing
import com.jondude.anzu.assist.Timers
import com.jondude.anzu.assist.Weather
import com.jondude.anzu.update.Updater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorinTest {
    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.init(ctx)
        Prefs.sp.edit().clear().commit()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        Store.init(ctx)
        HomeWatch.reset()
    }

    // ---------- autonomy zones ----------
    @Test
    fun zonesClassifyCommands() {
        assertEquals(0, Brain.zone("turn off the office lights"))
        assertEquals(1, Brain.zone("Unlock the front door"))
        assertEquals(1, Brain.zone("open the garage"))
        assertEquals(2, Brain.zone("disarm the alarm"))
        Prefs.redWords = "disarm, oven"
        assertEquals(2, Brain.zone("turn on the oven"))
    }

    // ---------- wearables ----------
    @Test
    fun wearablesAreRecognized() {
        assertEquals("glasses", wearableKind("Razer Anzu", "AA", ""))
        assertEquals("buddie", wearableKind("Buddie-1234", "BB", ""))
        assertEquals("buddie", wearableKind("Buddie", "BB", "AA"))
        assertEquals("glasses", wearableKind("Renamed", "AA", "AA"))
        assertNull(wearableKind("Pixel Buds Pro", "CC", "AA"))
        assertNull(wearableKind("Some Anzu clone", "DD", "AA"))
    }

    // ---------- Buddie packets ----------
    @Test
    fun buddiePacketsParse() {
        val v = ByteArray(84)
        assertNull(BuddiePacket.parse(ByteArray(20)))
        val silent = BuddiePacket.parse(v)!!
        assertNull(silent.mic)
        assertFalse(silent.selfSpeaking)
        assertFalse(silent.meeting)
        v[3] = 7; v[80] = 1; v[81] = 0xFE.toByte()
        val p = BuddiePacket.parse(v)!!
        assertEquals(40, p.mic!!.size)
        assertTrue(p.selfSpeaking)
        assertTrue(p.meeting)
    }

    @Test
    fun opusHeaderIsWellFormed() {
        val h = AudioMath.opusHead()
        assertEquals(19, h.size)
        assertEquals("OpusHead", String(h, 0, 8, Charsets.US_ASCII))
        assertEquals(1, h[9].toInt())                       // mono
        assertEquals(0x80.toByte(), h[12]); assertEquals(0x3E.toByte(), h[13]) // 16000 little-endian
    }

    @Test
    fun downsampleAverages() {
        val out = AudioMath.downsample(shortArrayOf(3, 6, 9, 30, 60, 90, 1), 3)
        assertEquals(listOf<Short>(6, 60), out.toList())
        assertEquals(4, AudioMath.toBytes(shortArrayOf(1, -1)).size)
    }

    private fun tone(ms: Int, amp: Double): ShortArray =
        ShortArray(16 * ms) { (amp * sin(2 * PI * 300 * it / 16000.0)).toInt().toShort() }

    @Test
    fun segmenterCutsUtterancesAndTracksSpeaker() {
        val s = Segmenter()
        var got: Utterance? = null
        repeat(10) { assertNull(s.feed(ShortArray(320), false)) }           // silence: nothing
        repeat(40) { assertNull(s.feed(tone(20, 4000.0), true)) }           // 800 ms of Jon talking
        repeat(60) { got = got ?: s.feed(ShortArray(320), false) }          // silence ends it
        assertNotNull(got)
        assertTrue(got!!.selfRatio > 0.9f)
        assertTrue(got!!.pcm.size >= 16000 * 8 / 10)

        var short: Utterance? = null
        repeat(5) { s.feed(tone(20, 4000.0), false) }                       // 100 ms blip: too short
        repeat(60) { short = short ?: s.feed(ShortArray(320), false) }
        assertNull(short)

        var other: Utterance? = null
        repeat(40) { s.feed(tone(20, 4000.0), false) }                      // someone else talking
        repeat(60) { other = other ?: s.feed(ShortArray(320), false) }
        assertTrue(other!!.selfRatio == 0f)
    }

    // ---------- wake word ----------
    @Test
    fun wakeWordExtractsTheRequest() {
        assertEquals("what time is it", Wake.extract("Corin, what time is it", "Corin"))
        assertEquals("turn off the lights", Wake.extract("hey corin turn off the lights", "Corin"))
        assertEquals("what's the weather", Wake.extract("what's the weather, Corin?", "Corin"))
        assertEquals("", Wake.extract("Corin?", "Corin"))
        assertEquals("add milk to my list", Wake.extract("Corrin add milk to my list", "Corin"))
        assertNull(Wake.extract("I ate corn on the cob", "Corin"))
        assertNull(Wake.extract("let's go to the store", "Corin"))
        assertEquals("status", Wake.extract("Sage, status", "Sage"))
    }

    // ---------- checklists ----------
    @Test
    fun checklistsWalkThroughSteps() {
        assertEquals("groceries", Store.listAdd("Groceries", "milk"))
        assertEquals("groceries", Store.listAdd("grocery list", "eggs"))   // resolves to the same list
        assertTrue(Store.listNext("groceries").contains("milk"))
        assertTrue(Store.listDone("groceries").contains("eggs"))
        assertTrue(Store.listDone("groceries").contains("Everything on groceries is done"))
        assertTrue(Store.listShow("groceries").startsWith("done: milk"))
        Store.listClear("groceries")
        assertTrue(Store.lists.value.isEmpty())
    }

    @Test
    fun checklistsSurviveRestart() {
        Store.listAdd("packing", "charger")
        Store.addNote("call the vet")
        Store.addMemory("Likes dry humor")
        Store.addMemory("likes dry humor") // duplicate, ignored
        Store.init(ApplicationProvider.getApplicationContext())
        assertEquals("charger", Store.lists.value["packing"]!![0].text)
        assertEquals("call the vet", Store.notes.value[0].text)
        assertEquals(1, Store.memory.value.size)
    }

    // ---------- log + recall ----------
    @Test
    fun recallFindsPastConversation() {
        Store.appendLog("Jon", "the compressor needs a new belt")
        Store.appendLog("Corin", "Noted. Want a reminder?")
        Store.appendLog("Jon", "what's for dinner")
        val hits = Store.searchLog("compressor belt")
        assertTrue(hits.first().contains("compressor needs a new belt"))
        assertTrue(Store.searchLog("zeppelin").isEmpty())
        assertTrue(Store.todayLog().contains("dinner"))
    }

    @Test
    fun historyKeepsOnlyRecentExchanges() {
        repeat(12) { Store.addExchange("q$it", "a$it") }
        val h = Store.recentExchanges()
        assertEquals(8, h.size)
        assertEquals("q11", h.last().first)
    }

    @Test
    fun actionsAreLoggedForTheDigest() {
        Store.logAction("Saved a note: test")
        assertEquals(listOf("Saved a note: test"), Store.actionsToday())
    }

    // ---------- timers ----------
    @Test
    fun timersFireAndCancel() {
        val keep = Timers.add(-0.01, "tea")       // already due
        val later = Timers.add(30.0, "laundry")
        assertTrue(Timers.pending())
        assertEquals(listOf("tea"), Timers.due().map { it.label })
        assertTrue(Timers.due().isEmpty())
        Timers.cancel(later)
        assertFalse(Timers.pending())
        assertEquals("tea", keep.label)
    }

    // ---------- notifications ----------
    @Test
    fun heldNotificationsDrainOnce() {
        Glasses.takeHeld()
        Glasses.hold("Shop: sale")
        Glasses.hold("News: headline")
        assertEquals(2, Glasses.heldCount.value)
        assertEquals(2, Glasses.takeHeld().size)
        assertTrue(Glasses.takeHeld().isEmpty())
    }

    // ---------- places ----------
    private fun loc(lat: Double, lon: Double) = Location("test").apply { latitude = lat; longitude = lon }

    @Test
    fun placeRemindersFireOnArrivalOnly() {
        val home = loc(41.6, -80.1)
        Places.save("Home", home)
        assertTrue(Places.known("home"))
        Places.addReminder("home", "take out the trash")
        assertTrue(Places.check(home).isEmpty())                 // already there: not an arrival
        assertTrue(Places.check(loc(41.7, -80.1)).isEmpty())     // away: arms it
        val due = Places.check(home)                              // back: fires
        assertEquals(1, due.size)
        assertTrue(due[0].contains("take out the trash"))
        assertEquals(0, Places.pendingCount())
    }

    // ---------- house watch ----------
    private fun ent(id: String, state: String, name: String, dc: String = "") = JSONObject()
        .put("entity_id", id).put("state", state)
        .put("attributes", JSONObject().put("friendly_name", name).put("device_class", dc))

    @Test
    fun houseWatchReportsOnlyWhatNeedsHim() {
        val t0 = 1_000_000L
        fun states(garage: String, jenna: String) = JSONArray()
            .put(ent("cover.garage", garage, "Garage door", "garage"))
            .put(ent("cover.blinds", "open", "Office blinds", "blind"))
            .put(ent("person.jenna", jenna, "Jenna"))
            .put(ent("person.jon", jenna, "Jon"))
        assertTrue(HomeWatch.evaluate(states("open", "not_home"), t0).isEmpty())               // baseline
        assertTrue(HomeWatch.evaluate(states("open", "not_home"), t0 + 5 * 60_000).isEmpty())  // not long enough
        val late = HomeWatch.evaluate(states("open", "home"), t0 + 11 * 60_000)
        assertTrue(late.any { it.startsWith("Garage door has been open for 11") })
        assertTrue(late.contains("Jenna just got home."))
        assertFalse(late.any { it.contains("Jon") })      // his own comings and goings are skipped
        assertFalse(late.any { it.contains("blinds") })   // blinds aren't a problem
        assertTrue(HomeWatch.evaluate(states("open", "home"), t0 + 12 * 60_000).isEmpty())     // said once
        assertTrue(HomeWatch.evaluate(states("closed", "home"), t0 + 13 * 60_000).isEmpty())
    }

    // ---------- weather / routing ----------
    @Test
    fun weatherParses() {
        val body = """{"current":{"temperature_2m":41.6,"weather_code":61,"wind_speed_10m":9.2},
            "daily":{"temperature_2m_max":[48.1],"temperature_2m_min":[33.0],"precipitation_probability_max":[70]}}"""
        val w = Weather.parse(body)
        assertTrue(w, w.startsWith("41 degrees and light rain, wind 9 mph."))
        assertTrue(w.contains("High 48, low 33, 70 percent"))
        assertEquals("", Weather.parse("not json"))
    }

    @Test
    fun routingAndLeaveTimes() {
        assertEquals(1530, Routing.parseDuration("""{"routes":[{"duration":1530.4}]}"""))
        assertNull(Routing.parseDuration("""{"routes":[]}"""))
        val begin = 10_000_000L
        val drive = 25 * 60
        // leave time = begin - 25 min - 5 min buffer
        assertNull(Routing.leaveLine("Dentist", begin, drive, begin - 60 * 60_000))
        assertTrue(Routing.leaveLine("Dentist", begin, drive, begin - 38 * 60_000)!!.startsWith("Leave in about 8 minutes"))
        assertTrue(Routing.leaveLine("Dentist", begin, drive, begin - 29 * 60_000)!!.startsWith("Time to leave"))
    }

    // ---------- updater / usage ----------
    @Test
    fun updaterReadsBuildNumbers() {
        assertEquals(42, Updater.versionOf("v1.0.42"))
        assertEquals(0, Updater.versionOf("nightly"))
    }

    @Test
    fun usageMeterAddsUp() {
        Usage.add(100, 20)
        Usage.add(50, 5)
        assertEquals("Today: 2 calls, 150 tokens in, 25 out.", Usage.summary())
    }
}
