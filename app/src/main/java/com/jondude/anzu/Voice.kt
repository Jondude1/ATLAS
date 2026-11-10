package com.jondude.anzu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Corin's voice: text-to-speech that suspends until the line is finished. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private lateinit var am: AudioManager

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()

    fun init(c: Context) {
        am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(c.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.setAudioAttributes(attrs)
        ready = true
        applyVoice()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { waiters.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { waiters.remove(it)?.complete(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { waiters.remove(it)?.complete(Unit) }
            }
        })
    }

    /** Offline English voices Corin can choose from. */
    fun voices(): List<String> = try {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .map { it.name }.sorted().take(16)
    } catch (e: Exception) {
        emptyList()
    }

    fun applyVoice() {
        val want = Prefs.voiceName
        if (want.isBlank()) return
        try {
            tts?.voices?.firstOrNull { it.name == want }?.let { tts?.voice = it }
        } catch (_: Exception) {
        }
    }

    suspend fun say(text: String, volume: Float = 1f) {
        val t = tts ?: return
        if (text.isBlank()) return
        var waited = 0
        while (!ready && waited < 40) {
            delay(100)
            waited++
        }
        if (!ready) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiters[id] = done
        Glasses.lastSaid.value = text
        try {
            am.requestAudioFocus(focus)
            t.setSpeechRate(Prefs.speechRate / 100f)
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            t.speak(text, TextToSpeech.QUEUE_ADD, params, id)
            withTimeoutOrNull(90_000) { done.await() }
        } finally {
            waiters.remove(id)
            am.abandonAudioFocusRequest(focus)
        }
    }

    fun stop() {
        tts?.stop()
        waiters.values.forEach { it.complete(Unit) }
        waiters.clear()
    }
}

/** Corin's ears: one-shot speech recognition, routed to the glasses mic when possible. */
data class Heard(val text: String, val quiet: Boolean)

object Ears {
    /** Earcons are Corin's body language: "listen", "think", "done". */
    fun earcon(kind: String) {
        try {
            val tone = when (kind) {
                "listen" -> ToneGenerator.TONE_PROP_BEEP
                "think" -> ToneGenerator.TONE_PROP_ACK
                else -> ToneGenerator.TONE_PROP_PROMPT
            }
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
            tg.startTone(tone, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {
        }
    }

    /** Returns what was heard, or null on silence / failure. */
    suspend fun listen(ctx: Context): Heard? = withContext(Dispatchers.Main) {
        val app = ctx.applicationContext
        if (!app.has(Manifest.permission.RECORD_AUDIO)) return@withContext null
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return@withContext null

        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var routed = false
        if (Prefs.useGlassesMic && Glasses.connected.value) {
            try {
                val sco = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (sco != null) {
                    routed = am.setCommunicationDevice(sco)
                    if (routed) delay(800)
                }
            } catch (_: Exception) {
            }
        }
        try {
            earcon("listen")
            delay(200)
            withTimeoutOrNull(20_000) { recognizeOnce(app) }
        } finally {
            if (routed) {
                try {
                    am.clearCommunicationDevice()
                } catch (_: Exception) {
                }
                delay(300)
            }
        }
    }

    private suspend fun recognizeOnce(app: Context): Heard? = suspendCancellableCoroutine { cont ->
        val sr = SpeechRecognizer.createSpeechRecognizer(app)
        var finished = false
        var peak = -10f
        fun finish(v: Heard?) {
            if (finished) return
            finished = true
            try {
                sr.destroy()
            } catch (_: Exception) {
            }
            if (cont.isActive) cont.resume(v)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > peak) peak = rmsdB
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = finish(null)
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(text?.takeIf { it.isNotBlank() }?.let { Heard(it, peak < 6f) })
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            finish(null)
        }
        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post {
                if (!finished) {
                    finished = true
                    try {
                        sr.cancel()
                        sr.destroy()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
