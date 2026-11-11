package com.jondude.anzu

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.jondude.anzu.assist.Brain
import com.jondude.anzu.assist.HomeWatch
import com.jondude.anzu.assist.Routing
import com.jondude.anzu.assist.Timers
import com.jondude.anzu.assist.Weather
import com.jondude.anzu.update.Updater
import kotlinx.coroutines.async
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
class AtlasTest {
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
        assertNull(wearableKind("Jon's Pixel Buds Pro 2", "CC", "AA")) // set aside for now
        assertNull(wearableKind("CMF Watch Pro 2-C599", "EE", "AA"))
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
        val wav = AudioMath.wav(ShortArray(100))
        assertEquals(244, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("offline speech model not downloaded", Ears.errorName(13))
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
        assertEquals("what time is it", Wake.extract("Atlas, what time is it", "Atlas"))
        assertEquals("turn off the lights", Wake.extract("hey atlas turn off the lights", "Atlas"))
        assertEquals("what's the weather", Wake.extract("what's the weather, Atlas?", "Atlas"))
        assertEquals("", Wake.extract("Atlas?", "Atlas"))
        assertEquals("add milk to my list", Wake.extract("Atlus add milk to my list", "Atlas"))
        assertEquals("status", Wake.extract("At las, status", "Atlas"))
        assertNull(Wake.extract("we finally got there at last", "Atlas"))
        // near-misses where he'd actually address it
        assertEquals("what time is it", Wake.extract("Atles, what time is it", "Atlas"))
        assertEquals("what time is it", Wake.extract("At last, what time is it", "Atlas"))
        assertEquals("what time is it", Wake.extract("At less what time is it", "Atlas"))
        assertEquals("turn the lights off", Wake.extract("Hey Atlast, turn the lights off", "Atlas"))
        assertEquals("what time is it", Wake.extract("what time is it, Adlas?", "Atlas"))
        assertNull(Wake.extract("the last train leaves at six", "Atlas"))
        assertNull(Wake.extract("alas, poor Yorick", "Atlas"))
        assertNull(Wake.extract("let's go to the store", "Atlas"))
        // the name is whatever he calls it; the older name still works if he switches back
        assertEquals("status", Wake.extract("Sage, status", "Sage"))
        assertEquals("what time is it?", Wake.extract("Corinne, what time is it?", "Corin"))
        assertEquals("what time is it?", Wake.extract("Corey, what time is it?", "Corin"))
        assertNull(Wake.extract("or what kind of switch", "Corin"))
    }

    @Test
    fun speechModelInputIsCleanedUp() {
        // a loud 30 Hz rumble under a quiet 1 kHz tone: the rumble should go, the level should come up
        val pcm = ShortArray(16000) { (6000 * sin(2 * PI * 30 * it / 16000.0) + 300 * sin(2 * PI * 1000 * it / 16000.0)).toInt().toShort() }
        val out = Whisperer.prepare(pcm)
        val tail = out.copyOfRange(8000, 16000)
        assertEquals(0.7f, out.maxOf { Math.abs(it) }, 0.01f)
        var low = 0.0; var high = 0.0
        for (i in tail.indices) {
            low += tail[i] * sin(2 * PI * 30 * (i + 8000) / 16000.0)
            high += tail[i] * sin(2 * PI * 1000 * (i + 8000) / 16000.0)
        }
        assertTrue("rumble $low vs tone $high", Math.abs(low) < Math.abs(high))
        assertEquals("what time is it", Whisperer.clean(" [BLANK_AUDIO] what time  is it (music) "))
        assertEquals("", Whisperer.clean("[Music]"))
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
        Store.appendLog("Atlas", "Noted. Want a reminder?")
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
        assertEquals(10, h.size)
        assertEquals("q11", h.last().first)
    }

    @Test
    fun conversationsKnowWhenTheyHappened() {
        val now = 1_800_000_000_000L
        Store.addExchange("morning question", "morning answer", now - 5 * 60 * 60_000L)
        Store.addExchange("lunch question", "lunch answer", now - 70 * 60_000L)
        Store.addExchange("old question", "old answer", now - 30 * 60 * 60_000L) // over 12 hours: dropped
        val h = com.jondude.anzu.assist.Brain.timedHistory(now)
        assertEquals(4, h.size)
        assertTrue(h[0].second, h[0].second.startsWith("[5 hours ago") || h[0].second.startsWith("[yesterday"))
        assertTrue(h[2].second, h[2].second.startsWith("[an hour ago"))
        assertEquals("lunch answer", h[3].second)
        val stamped = com.jondude.anzu.assist.Brain.stampNow("what's up", now)
        assertTrue(stamped, stamped.contains("new conversation; you last spoke an hour ago"))
        assertTrue(stamped.endsWith("] what's up"))
        Store.addExchange("just now", "yep", now - 60_000L)
        assertTrue(com.jondude.anzu.assist.Brain.stampNow("and another", now).contains("continuing the conversation"))
        assertEquals("just now", When.ago(now - 30_000L, now))
        assertEquals("12 minutes ago", When.ago(now - 12 * 60_000L, now))
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

        // Pixel Buds are private like the Buddie buds, but have no always-on stream.
        Glasses.active.value = "pixelbuds"
        assertTrue(Wear.onBuds())
        assertFalse(Wear.senderOnly())
        assertFalse(Wear.repliesViaStream())
        assertFalse(Wear.eyeBreaks())
        assertTrue(Wear.promptLine().contains("Pixel Buds Pro 2"))
        assertTrue(Wear.switchLine().startsWith("Pixel Buds"))

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

    // ---------- reach: phone, calendar, Hermes ----------
    @Test
    fun reachHelpersParse() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // a typed number is used as is; a name needs contacts access, which the test doesn't have
        assertEquals("814-555-0100", com.jondude.anzu.assist.Phone.findContact(ctx, "814-555-0100")!!.second)
        assertNull(com.jondude.anzu.assist.Phone.findContact(ctx, "Jenna"))
        val t = com.jondude.anzu.assist.Phone.parseWhen("2026-11-12 14:30")!!
        assertEquals("2026-11-12 14:30", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t)))
        assertNull(com.jondude.anzu.assist.Phone.parseWhen("next Tuesday"))
        assertEquals("Done. Two files moved.", com.jondude.anzu.assist.Hermes.parseReply("""{"choices":[{"message":{"role":"assistant","content":" Done. Two files moved. "}}]}"""))
        assertEquals("", com.jondude.anzu.assist.Hermes.parseReply("oops"))
        assertFalse(com.jondude.anzu.assist.Vault.configured())
        assertTrue(com.jondude.anzu.assist.Phone.media(ctx, "juggle").startsWith("I can play"))
    }

    // ---------- connections (MCP) ----------
    @Test
    fun mcpRepliesAndNamesParse() {
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n"
        assertTrue(com.jondude.anzu.assist.Mcp.parseReply(sse, "text/event-stream", 7)!!.getJSONObject("result").getBoolean("ok"))
        assertNull(com.jondude.anzu.assist.Mcp.parseReply(sse, "text/event-stream", 8))
        assertEquals(3, com.jondude.anzu.assist.Mcp.parseReply("""{"jsonrpc":"2.0","id":3,"result":{}}""", "application/json", 3)!!.getInt("id"))
        val n = com.jondude.anzu.assist.Mcp.apiName("Home Assistant", "HassTurnOn")
        assertEquals("x_Home_Assistant__HassTurnOn", n)
        val long = com.jondude.anzu.assist.Mcp.apiName("A very long server name indeed", "an_extremely_long_tool_name_that_keeps_going_on")
        assertTrue(long.length <= 64)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(long))
    }

    /** A real MCP conversation over HTTP: a small server in the test, Atlas's client talking to it. */
    @Test
    fun mcpClientTalksToAServer() {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        fun respond(out: java.io.OutputStream, code: Int, headers: Map<String, String>, body: ByteArray) {
            val head = StringBuilder("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray()); out.write(body); out.flush()
        }
        val worker = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                sock.use { c ->
                    val input = java.io.BufferedInputStream(c.getInputStream())
                    val reqHeaders = HashMap<String, String>()
                    fun line(): String {
                        val b = StringBuilder()
                        while (true) {
                            val ch = input.read()
                            if (ch < 0 || ch == '\n'.code) break
                            if (ch != '\r'.code) b.append(ch.toChar())
                        }
                        return b.toString()
                    }
                    line() // request line
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        if (i > 0) reqHeaders[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                    }
                    val len = reqHeaders["content-length"]?.toIntOrNull() ?: 0
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) { val n = input.read(buf, read, len - read); if (n < 0) break; read += n }
                    val msg = JSONObject(String(buf))
                    val method = msg.optString("method")
                    calls.add(method)
                    val out = c.getOutputStream()
                    if (reqHeaders["authorization"] != "Bearer secret") { respond(out, 401, emptyMap(), ByteArray(0)); return@use }
                    if (method != "initialize" && reqHeaders["mcp-session-id"] != "s1") { respond(out, 400, emptyMap(), ByteArray(0)); return@use }
                    if (!msg.has("id")) { respond(out, 202, emptyMap(), ByteArray(0)); return@use }
                    val id = msg.getInt("id")
                    val result = when (method) {
                        "initialize" -> JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "fake").put("version", "1"))
                        "tools/list" -> JSONObject().put("tools", JSONArray()
                            .put(JSONObject().put("name", "light_on").put("description", "Turn on a light")
                                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().put("room", JSONObject().put("type", "string"))))))
                        "tools/call" -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
                            .put("text", "Light on in " + msg.getJSONObject("params").getJSONObject("arguments").getString("room"))))
                        else -> JSONObject()
                    }
                    val reply = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
                    // initialize answers as plain JSON and hands out a session; the rest answer as an event stream
                    if (method == "initialize") {
                        respond(out, 200, mapOf("Content-Type" to "application/json", "Mcp-Session-Id" to "s1"), reply.toByteArray())
                    } else {
                        respond(out, 200, mapOf("Content-Type" to "text/event-stream"), "event: message\ndata: $reply\n\n".toByteArray())
                    }
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val url = "http://127.0.0.1:${server.localPort}/mcp"
            val bad = com.jondude.anzu.assist.McpServer("Wrong", url, "nope")
            assertTrue(com.jondude.anzu.assist.Mcp.discover(bad).isEmpty())
            assertTrue(com.jondude.anzu.assist.Mcp.status.value["Wrong"]!!.contains("refused"))

            val s = com.jondude.anzu.assist.McpServer("House", url, "secret")
            com.jondude.anzu.assist.Mcp.upsert(s)
            val tools = com.jondude.anzu.assist.Mcp.discover(s)
            assertEquals(listOf("light_on"), tools.map { it.name })
            assertEquals("1 tools", com.jondude.anzu.assist.Mcp.status.value["House"])
            assertTrue(calls.contains("notifications/initialized"))
            val def = com.jondude.anzu.assist.Mcp.toolDefinitions().getJSONObject(0)
            assertEquals("x_House__light_on", def.getString("name"))
            val t = com.jondude.anzu.assist.Mcp.find("x_House__light_on")!!
            assertEquals("Light on in office", com.jondude.anzu.assist.Mcp.call(t, JSONObject().put("room", "office")))
            assertTrue(com.jondude.anzu.assist.Mcp.summary().contains("House (light_on)"))
            com.jondude.anzu.assist.Mcp.setEnabled("House", false)
            assertNull(com.jondude.anzu.assist.Mcp.find("x_House__light_on"))
        } finally {
            server.close()
            com.jondude.anzu.assist.Mcp.remove("House")
        }
    }

    // ---------- hub on Obelisk ----------
    @Test
    fun hubBriefAndReportsFollowTheContract() {
        val b = com.jondude.anzu.assist.Hub.parseBrief(
            """{"brief":"Quiet evening. Orbit has two new ideas.","urgent":[{"id":4,"app":"Home","title":"Garage open 40 min","body":"","at":1}],"updated":5}"""
        )!!
        assertEquals("Quiet evening. Orbit has two new ideas.", b.text)
        assertEquals(4L, b.urgent[0].id)
        assertEquals("Garage open 40 min", b.urgent[0].title)
        assertNull(com.jondude.anzu.assist.Hub.parseBrief("not json"))
        val r = com.jondude.anzu.assist.Hub.reportJson("Orbit", "New idea", "Desk HUD", "URGENT", "idea-7")
        assertEquals("urgent", r.getString("priority"))
        assertEquals("idea-7", r.getString("key"))
        assertEquals("normal", com.jondude.anzu.assist.Hub.reportJson("X", "t", "", "whenever", null).getString("priority"))
        assertFalse(com.jondude.anzu.assist.Hub.reportJson("X", "t", "", "low", null).has("key"))
        // Home Assistant's address is built in; its connection appears once a token is set, and can be switched off
        assertEquals(HOME_ASSISTANT, Prefs.haUrl)
        assertTrue(com.jondude.anzu.assist.Mcp.servers().none { it.name == "Home Assistant" })
        Prefs.haToken = "t"
        val ha = com.jondude.anzu.assist.Mcp.servers().first { it.name == "Home Assistant" }
        assertEquals("http://100.68.172.113/api/mcp", ha.url)
        com.jondude.anzu.assist.Mcp.setEnabled("Home Assistant", false)
        assertFalse(com.jondude.anzu.assist.Mcp.servers().first { it.name == "Home Assistant" }.enabled)
        Prefs.haToken = ""
        // the hub address is built in, and comes back if the setting is ever cleared
        assertEquals(OBELISK_HUB, Prefs.obeliskUrl)
        assertTrue(com.jondude.anzu.assist.Hub.configured())
        Prefs.obeliskUrl = ""
        assertFalse(com.jondude.anzu.assist.Hub.configured())
        assertNull(com.jondude.anzu.assist.Hub.brief())
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

    @Test
    fun athenaStatusGroupsAndFlags() {
        val st = com.jondude.anzu.assist.Hub.parseAthena(
            """{"containers":[{"name":"piped-nginx","status":"Up 38 hours","ports":["0.0.0.0:80->80/tcp"]},
               {"name":"piped-backend","status":"Exited (1) 2 hours ago","ports":""},
               {"name":"jellyfin","status":"Up 3 days","ports":"0.0.0.0:8096->8096/tcp"}],"uptime":"3 days","disk":"412 GB free"}"""
        )
        assertEquals(3, st.containers.size)
        assertEquals(listOf("piped-backend"), st.containers.filter { !it.up }.map { it.name })
        assertEquals(setOf("piped", "jellyfin"), st.containers.map { it.stack }.toSet())
        assertEquals("412 GB free", st.disk)
    }

    @Test
    fun companionWordsAreHonest() {
        assertEquals("Good morning.", greeting(8))
        assertEquals("Late night.", greeting(2))
        val st = com.jondude.anzu.assist.Hub.parseAthena("""{"containers":[{"name":"jellyfin","status":"Up 3 days"},{"name":"sonarr","status":"Exited (1)"}]}""")
        assertEquals("Athena needs a look: sonarr is down. The other 1 are up.", athenaSentence(st))
        assertEquals(
            "It's Tuesday. Your earbuds are on (64%). The house is connected. Athena has 1 service down. Garage is open.",
            knowsLine(true, "buddie", 64, true, true, st, "Garage is open.", "Tuesday")
        )
        assertEquals("It's Tuesday. Obelisk isn't answering.", knowsLine(false, null, -1, null, false, null, "", "Tuesday"))
        assertEquals("Garage is open.", firstSentence("Garage is open. Two packages came."))
    }

    @Test
    fun earconsAreShortAndQuiet() {
        for (kind in listOf("listen", "think", "done", "notice", "urgent")) {
            val pcm = Earcons.render(kind)
            assertTrue("$kind too long", pcm.size < 22_050 / 2)       // under half a second
            assertTrue("$kind too loud", pcm.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE / 6)
            assertTrue("$kind silent", pcm.any { it.toInt() != 0 })
        }
        assertTrue(Earcons.notes("listen").last().first > Earcons.notes("listen").first().first) // rises
        assertTrue(Earcons.notes("done").last().first < Earcons.notes("done").first().first)     // falls
    }

    @Test
    fun spokenBriefKeepsToItsBudget() {
        val S = com.jondude.anzu.assist.Spoken
        listOf("brief me", "Atlas, brief me", "what's the brief?", "give me the morning briefing", "catch me up", "What did I miss")
            .forEach { assertTrue(it, S.isBriefRequest(it)) }
        listOf("brief me on the garage door later", "write a brief for work").forEach { assertFalse(it, S.isBriefRequest(it)) }
        assertEquals(120, S.budget(8)); assertEquals(60, S.budget(21))
        assertEquals("One urgent thing: house: Garage open. Two packages came.", S.brief("Two packages came.", listOf("house: Garage open"), 60))
        val long = (1..40).joinToString(" ") { "Sentence $it is here." }
        val cut = S.brief(long, emptyList(), 60)
        assertTrue(cut.split(" ").size <= 60)
        assertTrue(cut.endsWith("."))
        assertEquals("Nothing new from your systems. All quiet.", S.brief("", emptyList()))
    }

    @Test
    fun approvalCardWaitsForATap() = kotlinx.coroutines.runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val A = com.jondude.anzu.assist.Approvals
        val yes = async { A.ask(ctx, "Text Jenna: on my way. Send it?") }
        while (A.pending.value == null) kotlinx.coroutines.delay(10)
        assertEquals("Text Jenna: on my way. Send it?", A.pending.value!!.question)
        A.answer(A.pending.value!!.id, true)
        assertTrue(yes.await())
        assertNull(A.pending.value)
        val no = async { A.ask(ctx, "Call Dave?") }
        while (A.pending.value == null) kotlinx.coroutines.delay(10)
        A.answer(A.pending.value!!.id, false)
        assertFalse(no.await())
    }

    @Test
    fun earGetsShortPlainSpeech() {
        val S = com.jondude.anzu.assist.Spoken
        assertEquals("Jellyfin restarted. See the link.", S.forEar("**Jellyfin** restarted. See https://athena:8096/web."))
        assertEquals("Milk Eggs", S.forEar("- Milk\n- Eggs"))
        val long = (1..60).joinToString(" ") { "Point $it." }
        val ear = S.forEar(long)
        assertTrue(ear.endsWith("The rest is on your phone."))
        assertTrue(ear.split(" ").size <= 86)
        listOf("say that again", "Atlas, repeat that", "what did you say?", "come again").forEach { assertTrue(it, S.isRepeatRequest(it)) }
        assertFalse(S.isRepeatRequest("repeat that to Jenna tomorrow"))
    }

    @Test
    fun bargeInStopsOnlyWhenMeant() {
        assertEquals(true to null, Barge.decide("stop", "Atlas"))
        assertEquals(true to null, Barge.decide("Atlas, stop.", "Atlas"))
        assertEquals(true to "what time is it?", Barge.decide("Atlas, what time is it?", "Atlas").let { it.first to it.second?.lowercase() })
        assertEquals(false to null, Barge.decide("I was telling him to stop by the store later today", "Atlas"))
        assertEquals(false to null, Barge.decide("", "Atlas"))
    }

    @Test
    fun athenaLiveShapeFromTheHub() {
        val H = com.jondude.anzu.assist.Hub
        // What the deployed hub actually returns: docker ps pipes, PowerShell TimeSpan and PSDrive tables flattened.
        val st = H.parseAthena(
            """{"containers":[{"name":"jellyfin","status":"Up 3 days (healthy)","ports":"0.0.0.0:8096->8096/tcp"}],
               "uptime":"Days : 3 Hours : 4 Minutes : 12 Seconds : 9 Milliseconds : 0 Ticks : 2700000000000 TotalDays : 3.17",
               "disk":"Used Free ---- ---- 542000000000 443000000000","updated":1794390000000}"""
        )
        assertNull(st.error)
        assertEquals("3 days, 4 hours", st.uptime)
        assertEquals("413 GB free of 917 GB", st.disk)
        assertTrue(st.containers[0].up)
        val warming = H.parseAthena("""{"containers":[],"uptime":"","disk":"","updated":0}""")
        assertNotNull(warming.error)
    }

    @Test
    fun peopleResolveFromWhatHeSaid() {
        val P = com.jondude.anzu.assist.People
        val mem = listOf("Jenna is Jon's wife and the Common Grounds treasurer", "Dave runs the D&D game on Fridays", "Likes his coffee black")
        assertEquals(listOf("Jenna"), P.names("text jenna that I'm on my way", mem))
        assertEquals(listOf("Ashley"), P.names("Atlas, what did Ashley say?", mem))
        assertEquals(emptyList<String>(), P.names("What time is it on Friday?", mem))
        assertEquals(listOf("Dave runs the D&D game on Fridays"), P.facts("Dave", mem))
    }

    @Test
    fun tailnetNamesBecomeIpsAndSystemsSpeak() {
        assertEquals("http://100.65.202.81:8765/atlas/brief", tailnet("http://obalisk.tail8dab0b.ts.net:8765/atlas/brief"))
        assertEquals("http://100.68.172.113/api/mcp", tailnet("http://homeassistant-1.tail8dab0b.ts.net/api/mcp"))
        assertEquals("https://api.anthropic.com/v1/messages", tailnet("https://api.anthropic.com/v1/messages"))
        val S = com.jondude.anzu.assist.Systems
        assertEquals("Jellyfin", S.find("jellyfin")!!.name)
        val up = S.all.map { com.jondude.anzu.assist.Systems.Check(it, true, 200) }
        assertEquals("Everything's answering: all ${S.all.size} services are up.", S.sentence(up))
        val oneDown = up.mapIndexed { i, c -> if (c.service.name == "Frigate") com.jondude.anzu.assist.Systems.Check(c.service, false, -1) else c }
        assertEquals("All up except Frigate on Athena.", S.sentence(oneDown))
    }

    @Test
    fun heliosLatestUpdateIsPicked() {
        val json = """[{"created_at":"2026-11-11T13:46:48Z","body":"## Helios coordinator check\nAll green.\n— Helios (coordinator)"},
                      {"created_at":"2026-11-11T13:50:00Z","body":"## Cloud session: thing\n_Cloud session_"}]"""
        val s = com.jondude.anzu.assist.Helios.pick(json)!!
        assertTrue(s.startsWith("Helios, at 13:46 UTC:"))
        assertTrue(s.contains("All green."))
        assertNull(com.jondude.anzu.assist.Helios.pick("[]"))
    }
}
