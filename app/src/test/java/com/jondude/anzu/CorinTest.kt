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
        assertNull(BuddiePacket.parse(ByteArray(20)))
        val silent = BuddiePacket.parse(ByteArray(84))!!
        assertNull(silent.mic)
        assertFalse(silent.selfSpeaking)
        assertFalse(silent.meeting)

        // Layout from the published app: Opus frame first (it never starts with 0 or 1), flags at 80 and 81.
        val a = ByteArray(84)
        a[0] = 0x48; a[5] = 7; a[80] = 1; a[81] = 0xFE.toByte()
        val pa = BuddiePacket.parse(a)!!
        assertEquals(0x48.toByte(), pa.mic!![0])
        assertTrue(pa.selfSpeaking)
        assertTrue(pa.meeting)

        // Layout from the published firmware: speaking flag first, Opus frame from byte 1, counter at 81.
        val b = ByteArray(84)
        b[0] = 1; b[1] = 0x48; b[6] = 7; b[81] = 0xFE.toByte()
        val pb = BuddiePacket.parse(b)!!
        assertEquals(40, pb.mic!!.size)
        assertEquals(0x48.toByte(), pb.mic!![0])
        assertTrue(pb.selfSpeaking)
        assertFalse(pb.meeting)                 // byte 81 is only a counter here

        // Speaking flag set but no audio in the frame.
        val c = ByteArray(84); c[0] = 1
        val pc = BuddiePacket.parse(c)!!
        assertNull(pc.mic)
        assertTrue(pc.selfSpeaking)
    }

    /** The firmware's encoder for one 16 ms frame (fft_and_pca.c), used to check our decoder against it. */
    private fun firmwareEncode(w: FloatArray, frame: DoubleArray): ByteArray {
        val n = 257
        val f = 0.5 * Math.sqrt(2.0 / 256)
        val x = frame.copyOf()
        x[0] = Math.rint(x[0] * Math.sqrt(2.0)); x[n - 1] = Math.rint(x[n - 1] * Math.sqrt(2.0))
        val out = ByteArray(80)
        val mag = DoubleArray(n)
        for (k in 0 until n) {
            var acc = x[0] + (if (k % 2 == 0) x[n - 1] else -x[n - 1])
            for (i in 1 until n - 1) acc += 2 * x[i] * Math.cos(PI * k * i / 256.0)
            var v = (f * acc).toInt().toDouble() // the firmware stores it as a 16-bit integer
            if (v < 0) {
                v = -v
                out[47 + k / 8] = (out[47 + k / 8] + (1 shl (k % 8))).toByte()
            }
            mag[k] = v
        }
        for (i in 0 until 47) {
            var r = 0.0
            for (k in 0 until n) r += mag[k] / 256.0 * w[i * n + k]
            out[i] = Math.rint(r).coerceIn(-128.0, 127.0).toInt().toByte()
        }
        return out
    }

    @Test
    fun compressedBuddieAudioSurvivesTheRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = ctx.assets.open("buddie_pca.bin").use { it.readBytes() }
        assertEquals(47 * 257 * 4, bytes.size)
        val w = FloatArray(47 * 257)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(w)
        val codec = PcaCodec.fromBytes(bytes)

        // A speech-like signal: a 140 Hz voice with two formants and syllable-rate loudness.
        val total = 16000
        val sig = DoubleArray(total)
        var phase = 0.0
        for (i in 0 until total) {
            val t = i / 16000.0
            phase += 2 * PI * 140 * (1 + 0.05 * sin(2 * PI * 3 * t)) / 16000
            var v = 0.0
            for (h in 1 until 40) {
                val hz = h * 140.0
                val e1 = Math.exp(-((hz - 600) / 400) * ((hz - 600) / 400))
                val e2 = 0.5 * Math.exp(-((hz - 1800) / 500) * ((hz - 1800) / 500))
                v += (e1 + e2) * sin(h * phase)
            }
            val s4 = sin(2 * PI * 4 * t)
            sig[i] = v * (0.5 + 0.5 * s4 * s4)
        }
        val peak = sig.maxOf { Math.abs(it) }
        for (i in sig.indices) sig[i] = Math.rint(sig[i] / peak * 9000)

        val decoded = ArrayList<Short>()
        var prev = 0.0
        var i = 0
        while (i + 256 <= total) {
            val frame = DoubleArray(257)
            frame[0] = prev
            for (j in 0 until 256) frame[j + 1] = sig[i + j]
            prev = sig[i + 255]
            val packetBlock = firmwareEncode(w, frame)
            decoded.addAll(codec.decodeBlock(packetBlock, 0).toList())
            i += 256
        }
        // correlation between what went in and what came out
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (k in decoded.indices) {
            val a = sig[k]; val b = decoded[k].toDouble()
            sxy += a * b; sxx += a * a; syy += b * b
        }
        val corr = sxy / Math.sqrt(sxx * syy)
        assertTrue("correlation $corr", corr > 0.88)
        assertTrue("level ${Math.sqrt(syy / decoded.size)}", Math.sqrt(syy / decoded.size) > 1000)

        // and the decoded audio is loud enough for the utterance cutter to pick up
        val seg = Segmenter()
        var u: Utterance? = null
        decoded.chunked(256).forEach { u = u ?: seg.feed(it.toShortArray(), true) }
        repeat(80) { u = u ?: seg.feed(ShortArray(256), false) }
        assertNotNull(u)
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

    // ---------- glasses vs earbuds ----------
    @Test
    fun behaviorDiffersByWearable() {
        Glasses.buddieStream.value = "off"
        Glasses.active.value = "glasses"
        assertTrue(Wear.senderOnly())                      // open-ear: don't read messages aloud
        assertEquals(0.85f, Wear.volume(), 0.001f)
        assertTrue(Wear.eyeBreaks())
        assertFalse(Wear.repliesViaStream())
        assertTrue(Wear.promptLine().contains("open-ear"))
        assertTrue(Wear.promptLine().contains("don't say private details"))
        Prefs.discreetGlasses = false
        assertFalse(Wear.promptLine().contains("don't say private details"))

        Prefs.chattiness = 0; Prefs.chattinessBuds = 2
        assertEquals(0, Wear.chattiness())
        Glasses.active.value = "buddie"
        assertEquals(2, Wear.chattiness())
        assertFalse(Wear.senderOnly())                     // private: read the whole message
        assertEquals(1f, Wear.volume(), 0.001f)
        assertFalse(Wear.eyeBreaks())
        assertTrue(Wear.promptLine().contains("Only he can hear you"))
        assertFalse(Wear.repliesViaStream())               // stream not running yet
        Glasses.buddieStream.value = "listening"
        assertTrue(Wear.repliesViaStream())
        assertTrue(Wear.switchLine().startsWith("Earbuds"))

        Glasses.active.value = ""
        Glasses.buddieStream.value = "off"
        assertEquals("", Wear.promptLine())
        assertEquals(1f, Wear.volume(), 0.001f)
    }

    // ---------- voices ----------
    @Test
    fun premiumVoiceRequestsAreBuilt() {
        val voices = CloudVoice.parseVoices("""{"voices":[{"voice_id":"abc","name":"Rachel","category":"premade"},{"voice_id":"","name":"x"}]}""")
        assertEquals(listOf("abc" to "Rachel · premade"), voices)
        assertTrue(CloudVoice.parseVoices("nope").isEmpty())

        assertNull(CloudVoice.request("hi"))                 // phone voice: no cloud request
        Prefs.voiceProvider = 1
        assertNull(CloudVoice.request("hi"))                 // ElevenLabs without key or voice
        Prefs.elevenKey = "k"; Prefs.elevenVoiceId = "abc"
        val e = CloudVoice.request("hi")!!
        assertTrue(e.first.startsWith("https://api.elevenlabs.io/v1/text-to-speech/abc"))
        assertEquals("k", e.second["xi-api-key"])
        assertEquals("eleven_flash_v2_5", JSONObject(e.third).getString("model_id"))

        Prefs.voiceProvider = 2
        Prefs.speechUrl = "http://obelisk:8880/v1/"
        val c = CloudVoice.request("hello there")!!
        assertEquals("http://obelisk:8880/v1/audio/speech", c.first)
        assertTrue(c.second.isEmpty())                       // no key for a local server
        assertEquals("hello there", JSONObject(c.third).getString("input"))
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
