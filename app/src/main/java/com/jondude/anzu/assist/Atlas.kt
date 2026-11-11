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
            try {
                var volume = 1f
                if (!opening.isNullOrBlank()) {
                    Glasses.atlasState.value = "speaking"
                    Speaker.say(opening)
                    windowUntil = System.currentTimeMillis() + 30_000
                }
                if (!canListen || Prefs.listenMode == 0) return
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
                    Speaker.say(answer, volume)
                    windowUntil = System.currentTimeMillis() + 30_000
                }
            } finally {
                Glasses.atlasState.value = "idle"
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
                        val answer = Brain.reply(app, text)
                        Glasses.atlasState.value = "speaking"
                        Speaker.say(answer)
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
                    val answer = Brain.reply(app, text)
                    Glasses.lastSaid.value = answer
                    if (Glasses.connected.value) {
                        Glasses.atlasState.value = "speaking"
                        Speaker.say(answer, 0.6f)
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
    fun announce(text: String) {
        scope.launch {
            mutex.withLock {
                try {
                    Glasses.atlasState.value = "speaking"
                    // Nothing worn: hand off to a house speaker if one is set up.
                    val handedOff = !Glasses.connected.value && Prefs.houseSpeaker &&
                        kotlinx.coroutines.withContext(Dispatchers.IO) { HomeAssistant.say(text) }
                    if (!handedOff) Speaker.say(text)
                } finally {
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
            val sheet = if (dark) Color(0xFF16231D) else Color(0xFFFFFFFF)
            val text = if (dark) Color(0xFFF1ECDF) else Color(0xFF13201A)
            val muted = if (dark) Color(0xFF8FA397) else Color(0xFF5E7067)
            val accent = if (dark) Color(0xFFE3B655) else Color(0xFF1F5D45)
            Box(
                Modifier.fillMaxSize().clickable {
                    Speaker.stop()
                    finish()
                },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp)
                        .background(sheet, RoundedCornerShape(28.dp))
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${Prefs.name} · $state", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
