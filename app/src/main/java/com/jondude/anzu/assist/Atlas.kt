package com.jondude.anzu.assist

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jondude.anzu.Ears
import com.jondude.anzu.Glasses
import com.jondude.anzu.Prefs
import com.jondude.anzu.Store
import com.jondude.anzu.Earcons
import com.jondude.anzu.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs conversations: one voice at a time, whoever starts it. */
object Atlas {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    val busy: Boolean get() = mutex.isLocked

    /** After Atlas speaks, replies heard by the Buddie earbuds count without the wake word until this time. */
    @Volatile
    var windowUntil = 0L

    private val enders = Regex("^(never ?mind|stop|that'?s all|that is all|nothing|no thanks|cancel|goodbye|bye)\\W*$", RegexOption.IGNORE_CASE)
    private val yes = Regex("\\b(yes|yeah|yep|yup|sure|okay|ok|do it|go ahead|please do|confirm)\\b", RegexOption.IGNORE_CASE)

    /**
     * Optionally says [opening], then (if [canListen]) keeps a back-and-forth going
     * until Jon stops answering. This is the "conversation window".
     */
    suspend fun converse(ctx: Context, opening: String?, canListen: Boolean, maxTurns: Int = 5) {
        mutex.withLock {
            var listened = false
            try {
                var volume = 1f
                if (!opening.isNullOrBlank()) {
                    Glasses.atlasState.value = "speaking"
                    Speaker.say(opening)
                    windowUntil = System.currentTimeMillis() + 30_000
                }
                if (!canListen || Prefs.listenMode == 0) return
                listened = true
                repeat(maxTurns) {
                    Glasses.atlasState.value = "listening"
                    val heard = Ears.listen(ctx) ?: return
                    Glasses.lastHeard.value = heard.text
                    if (enders.matches(heard.text.trim())) return
                    // Whisper-back: a murmur gets a quiet reply.
                    volume = if (Prefs.whisperBack && heard.quiet) 0.45f else 1f
                    Glasses.atlasState.value = "thinking"
                    Ears.earcon("think")
                    val answer = Brain.reply(ctx, heard.text) { question ->
                        Glasses.atlasState.value = "speaking"
                        Speaker.say(question, volume)
                        Glasses.atlasState.value = "listening"
                        val r = Ears.listen(ctx)
                        Glasses.atlasState.value = "thinking"
                        r != null && yes.containsMatchIn(r.text)
                    }
                    Glasses.atlasState.value = "speaking"
                    Speaker.say(answer, volume, conversation = true)
                    windowUntil = System.currentTimeMillis() + 30_000
                }
            } finally {
                Glasses.atlasState.value = "idle"
                if (listened) Ears.earcon("done") // the conversation closed: a soft falling note, so he knows it stopped listening
            }
        }
    }

    /** Something Jon said to Atlas, already transcribed (the Buddie wake-word path). */
    fun heard(ctx: Context, text: String) {
        val app = ctx.applicationContext
        scope.launch {
            mutex.withLock {
                try {
                    if (text.isBlank()) {
                        Glasses.atlasState.value = "speaking"
                        Speaker.say("Yeah?")
                    } else {
                        Glasses.lastHeard.value = text
                        if (enders.matches(text.trim())) {
                            windowUntil = 0
                            return@withLock
                        }
                        Glasses.atlasState.value = "thinking"
                        Ears.earcon("think")
                        // From the earbuds' wake word there's no follow-up listen, so a gated action asks aloud and waits for a tap.
                        val answer = Brain.reply(app, text) { question ->
                            Glasses.atlasState.value = "speaking"
                            if (CoPilot.active) {
                                // Behind the wheel: never "look at your phone". Ask out loud and listen for yes.
                                Speaker.say(question)
                                Glasses.atlasState.value = "listening"
                                val r = Ears.listen(app)
                                Glasses.atlasState.value = "thinking"
                                r != null && yes.containsMatchIn(r.text)
                            } else {
                                Speaker.say("$question Tap approve on your phone.")
                                Glasses.atlasState.value = "thinking"
                                Approvals.ask(app, question)
                            }
                        }
                        Glasses.atlasState.value = "speaking"
                        Speaker.say(answer, conversation = true)
                    }
                    windowUntil = System.currentTimeMillis() + 30_000
                } finally {
                    Glasses.atlasState.value = "idle"
                }
            }
        }
    }

    /** Typed input (phone or a wrist keyboard). Spoken back only if the glasses are on. */
    fun typed(ctx: Context, text: String) {
        if (text.isBlank()) return
        val app = ctx.applicationContext
        scope.launch {
            mutex.withLock {
                try {
                    Glasses.lastHeard.value = text
                    Glasses.atlasState.value = "thinking"
                    val answer = Brain.reply(app, text) { question -> Approvals.ask(app, question) }
                    Glasses.lastSaid.value = answer
                    if (Glasses.connected.value) {
                        Glasses.atlasState.value = "speaking"
                        Speaker.say(answer, 0.6f, conversation = true)
                    }
                } finally {
                    Glasses.atlasState.value = "idle"
                }
            }
        }
    }

    /** Says a line, listens, and repeats back what it heard: for checking mic routing. */
    fun micTest(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            mutex.withLock {
                try {
                    Glasses.atlasState.value = "speaking"
                    Speaker.say("Testing. Say something after the tone.")
                    Glasses.atlasState.value = "listening"
                    val h = Ears.listen(app)
                    Glasses.atlasState.value = "speaking"
                    Speaker.say(
                        if (h == null) "I didn't catch anything."
                        else "I heard: ${h.text}." + if (h.quiet) " That registered as quiet." else ""
                    )
                } finally {
                    Glasses.atlasState.value = "idle"
                }
            }
        }
    }

    /** Queue a one-way line (notifications, timers, heads-ups). */
    /** Lines that waited for music to stop: when they may still be said, and the line. */
    private val waiting = ArrayList<Pair<Long, String>>()

    /** True when music or other media is playing and Jon hasn't said Atlas may talk over it. */
    fun musicPlaying(): Boolean {
        val ctx = Brain.appContext ?: return false
        if (Prefs.talkOverMedia) return false
        return try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    /**
     * For things that aren't urgent: never over music. The line waits for a pause (up to [maxWaitMs]),
     * then moves to the held digest he hears in the next brief instead.
     */
    fun polite(text: String, maxWaitMs: Long = 10 * 60_000L, source: String = "") {
        // A kind of line Jon keeps cutting off gets a soft bell and a place in the brief instead of speech.
        // On the road only what matters for driving is spoken; the rest waits for when he parks.
        if (CoPilot.active && source !in CoPilot.roadworthy) {
            Glasses.hold(text)
            return
        }
        // At the table, with guests, at lights-out or during work: hold it as a card instead of speaking.
        Discretion.current()?.let { why ->
            if (why == "work hours") Earcons.play("notice")
            Glasses.hold(text)
            return
        }
        if (Interrupts.muted(source)) {
            Earcons.play("notice")
            Glasses.hold(text)
            return
        }
        if (!musicPlaying()) announce(text, source)
        else synchronized(waiting) { waiting.add(System.currentTimeMillis() + maxWaitMs to text) }
    }

    /** What kind of line is being spoken right now, so a "stop" can be counted against it. */
    @Volatile
    var speakingSource = ""

    /** Called every tick: say what waited if the music stopped; hold what waited too long. */
    fun flushPolite() {
        val due = synchronized(waiting) {
            if (waiting.isEmpty()) return
            val now = System.currentTimeMillis()
            val music = musicPlaying()
            val out = waiting.filter { !music || it.first < now }
            waiting.removeAll(out.toSet())
            out.map { (it.first < now && music) to it.second }
        }
        due.forEach { (held, line) -> if (held) Glasses.hold(line) else announce(line) }
    }

    fun announce(text: String, source: String = "") {
        scope.launch {
            mutex.withLock {
                try {
                    speakingSource = source
                    Glasses.atlasState.value = "speaking"
                    // Nothing worn: hand off to a house speaker if one is set up.
                    val handedOff = !Glasses.connected.value && Prefs.houseSpeaker &&
                        kotlinx.coroutines.withContext(Dispatchers.IO) { HomeAssistant.say(text) }
                    if (!handedOff) Speaker.say(text)
                } finally {
                    speakingSource = ""
                    Glasses.atlasState.value = "idle"
                }
            }
        }
    }
}

/**
 * Opened by the glasses' voice-assistant gesture (or the Talk button).
 * Being a visible activity guarantees microphone access.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setContent {
            val state by Glasses.atlasState.collectAsState()
            val said by Glasses.lastSaid.collectAsState()
            val heard by Glasses.lastHeard.collectAsState()
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val sheet = if (dark) Color(0xFF161616) else Color(0xFFFFFFFF)
            val text = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
            val muted = if (dark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B)
            val accent = if (dark) Color(0xFFFF5C00) else Color(0xFFFF5C00)
            Box(
                Modifier.fillMaxSize().clickable {
                    Speaker.stop()
                    finish()
                },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp)
                        .background(sheet, RoundedCornerShape(16.dp))
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val mic by Glasses.inputSource.collectAsState()
                    Text(
                        "${Prefs.name} · $state" + (if (state == "listening" && mic.isNotBlank()) " · $mic" else ""),
                        color = accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                    if (heard.isNotBlank()) Text("You: $heard", color = muted, fontSize = 15.sp)
                    if (said.isNotBlank()) Text(said, color = text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                    Text("Tap anywhere to dismiss", color = muted, fontSize = 12.sp)
                }
            }
        }
        lifecycleScope.launch {
            if (Atlas.busy) Speaker.stop()
            Atlas.converse(this@AssistActivity, null, true, 8)
            finish()
        }
    }
}


/**
 * Learned interruption policy (roadmap Phase 2): every time Jon says "stop" over a proactive line, that kind of line
 * (house, places, calendar, research, battery…) gets a mark. Three marks in a week and that kind stops being spoken:
 * it becomes a soft bell plus a line in the next brief. A week without being cut off and it can speak again.
 */
object Interrupts {
    private const val WEEK = 7L * 24 * 60 * 60_000
    const val LIMIT = 3

    /** source -> times he cut it off, newest last. */
    fun parse(raw: String): Map<String, List<Long>> = try {
        val o = org.json.JSONObject(raw.ifBlank { "{}" })
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k); (0 until a.length()).map { a.getLong(it) }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    fun encode(m: Map<String, List<Long>>): String =
        org.json.JSONObject(m.mapValues { org.json.JSONArray(it.value) }).toString()

    fun mutedIn(m: Map<String, List<Long>>, source: String, now: Long = System.currentTimeMillis()): Boolean =
        source.isNotBlank() && (m[source]?.count { now - it < WEEK } ?: 0) >= LIMIT

    fun muted(source: String): Boolean = mutedIn(parse(Prefs.cutoffs), source)

    fun cut(source: String, now: Long = System.currentTimeMillis()) {
        if (source.isBlank()) return
        val m = parse(Prefs.cutoffs).toMutableMap()
        m[source] = ((m[source] ?: emptyList()) + now).filter { now - it < WEEK }.takeLast(10)
        Prefs.cutoffs = encode(m)
        // The hub learns from this too (ack vs ignore): one report per cut-off.
        Hub.report("atlas", "cut off: $source", "Jon said stop over a $source line.", key = "cutoff-$source-$now")
        if (mutedIn(m, source, now)) Store.logAction("Stopped speaking $source updates out loud (you cut them off ${LIMIT}x this week); they'll be a soft bell and a line in your brief.")
    }

    /** Kinds that are currently bell-only, for Settings and "why didn't you tell me". */
    fun mutedSources(): List<String> = parse(Prefs.cutoffs).keys.filter { muted(it) }

    fun reset() { Prefs.cutoffs = "" }
}
