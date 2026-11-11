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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Atlas's voice: text-to-speech that suspends until the line is finished. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private lateinit var am: AudioManager
    private lateinit var appCtx: Context
    @Volatile
    private var player: android.media.MediaPlayer? = null
    /** Why the last premium-voice attempt fell back to the phone voice, for the settings screen. */
    @Volatile
    var lastCloudError = ""

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()

    fun init(c: Context) {
        appCtx = c.applicationContext
        am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val engine = Prefs.ttsEngine
        tts = if (engine.isBlank()) TextToSpeech(appCtx, this) else TextToSpeech(appCtx, this, engine)
    }

    /** Re-create the speech engine after the engine choice changes. */
    fun restart() {
        ready = false
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        init(appCtx)
    }

    /** Installed speech engines as (name, package). */
    fun engines(): List<Pair<String, String>> = try {
        tts?.engines.orEmpty().map { it.label to it.name }
    } catch (e: Exception) {
        emptyList()
    }

    /** Every English voice the current engine offers, as (id, description), best quality first. */
    fun allVoices(): List<Pair<String, String>> = try {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "en" }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
            .let { sorted ->
                // People can't tell "en-au-x-aua-local" from "en-au-x-aub-local"; number them per country instead.
                val seen = HashMap<String, Int>()
                sorted.map {
                    val country = it.locale.displayCountry.ifBlank { "English" }
                    val n = (seen[country] ?: 0) + 1
                    seen[country] = n
                    val quality = when {
                        it.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "best quality"
                        it.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "high quality"
                        else -> "standard"
                    }
                    it.name to "$country $n · $quality" + (if (it.isNetworkConnectionRequired) " · needs internet" else "")
                }
            }
    } catch (e: Exception) {
        emptyList()
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

    /** Offline English voices Atlas can choose from. */
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

    /** Premium voice: fetch the audio, then play it. False means fall back to the phone voice. */
    @Volatile
    private var cloudDone: CompletableDeferred<Unit>? = null

    private suspend fun sayCloud(text: String, volume: Float): Boolean {
        val audio = withContext(Dispatchers.IO) { CloudVoice.synth(text) } ?: return false
        val file = java.io.File(appCtx.cacheDir, "speech.mp3")
        withContext(Dispatchers.IO) { file.writeBytes(audio) }
        val done = CompletableDeferred<Unit>()
        cloudDone = done
        val mp = android.media.MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            withContext(Dispatchers.IO) { mp.prepare() }
            mp.setVolume(volume, volume)
            try {
                mp.playbackParams = mp.playbackParams.setSpeed((Prefs.speechRate / 100f).coerceIn(0.7f, 1.6f))
            } catch (_: Exception) {
            }
            am.requestAudioFocus(focus)
            player = mp
            mp.start()
            withTimeoutOrNull(120_000) { done.await() }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastCloudError = "Couldn't play the audio: ${e.message}"
            false
        } finally {
            player = null
            try {
                mp.release()
            } catch (_: Exception) {
            }
            am.abandonAudioFocusRequest(focus)
        }
    }

    suspend fun say(text: String, requested: Float = 1f) {
        if (text.isBlank()) return
        val volume = requested * Wear.volume()
        Glasses.lastSaid.value = text // shown on screen in full, even if the speech engine is unavailable
        com.jondude.anzu.assist.Spoken.lastFull = text
        @Suppress("NAME_SHADOWING") val text = com.jondude.anzu.assist.Spoken.forEar(text)
        if (Prefs.voiceProvider != 0 && sayCloud(text, volume)) return
        val t = tts ?: return
        var waited = 0
        while (!ready && waited < 40) {
            delay(100)
            waited++
        }
        if (!ready) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiters[id] = done
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
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        cloudDone?.complete(Unit)
        tts?.stop()
        waiters.values.forEach { it.complete(Unit) }
        waiters.clear()
    }
}

/** Atlas's ears: one-shot speech recognition, routed to the glasses mic when possible. */
data class Heard(val text: String, val quiet: Boolean)

object Ears {
    /** Earcons are Atlas's body language, soft enough for all-day wear. See [Earcons]. */
    fun earcon(kind: String) = Earcons.play(kind)

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
            recognizer.withLock { withTimeoutOrNull(20_000) { recognizeOnce(app) } }
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

    /** The error code from the most recent recognizer session that failed (0 = none). */
    @Volatile
    var lastError = 0

    fun errorName(code: Int): String = when (code) {
        0 -> "timed out with no answer"
        1 -> "network timeout"
        2 -> "network error"
        3 -> "audio error"
        4 -> "server error"
        5 -> "client error"
        6 -> "heard no speech"
        7 -> "no match"
        8 -> "recognizer busy"
        9 -> "missing permission"
        10 -> "too many requests"
        11 -> "server disconnected"
        12 -> "language not supported"
        13 -> "offline speech model not downloaded"
        else -> "error $code"
    }

    /** Only one recognizer session at a time, microphone or file. */
    val recognizer = Mutex()

    suspend fun recognizeOnce(
        app: Context, onDevice: Boolean = false, configure: (Intent) -> Unit = {}
    ): Heard? = suspendCancellableCoroutine { cont ->
        val sr = if (onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        } else SpeechRecognizer.createSpeechRecognizer(app)
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
            override fun onError(error: Int) {
                lastError = error
                finish(null)
            }
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
        configure(intent)
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

/** Turns a recorded utterance (16 kHz mono) into text. Used for the Buddie earbud stream. */
object Transcriber {
    /** Why the last attempt produced nothing, for the diagnostics readout. */
    @Volatile
    var lastProblem = ""

    suspend fun transcribe(ctx: Context, pcm: ShortArray): String? {
        val app = ctx.applicationContext
        lastProblem = ""
        // The on-phone Whisper model is the one that understands the earbuds' compressed audio.
        if (Whisperer.downloaded(app)) {
            val heard = Whisperer.transcribe(app, pcm)
            if (heard != null) {
                if (heard.isBlank()) lastProblem = "no words in that segment"
                return heard.ifBlank { null }
            }
            lastProblem = "speech model ${Whisperer.status.value}"
        } else {
            Whisperer.ensure(app)
            lastProblem = "speech model not ready (${Whisperer.status.value.ifBlank { "not downloaded" }})"
            return null
        }
        val text = android(app, pcm) ?: obelisk(pcm)
        if (text == null && lastProblem.isBlank()) lastProblem = "the phone's speech recognizer returned nothing"
        return text
    }

    /** Android's own recognizer, fed from memory instead of a microphone (Android 13+). */
    private suspend fun android(app: Context, pcm: ShortArray): String? {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            lastProblem = "needs Android 13 or newer"
            return null
        }
        if (!app.has(Manifest.permission.RECORD_AUDIO)) {
            lastProblem = "microphone permission is missing"
            return null
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            lastProblem = "no speech recognizer on this phone"
            return null
        }
        // Half a second of silence either side, so the recognizer gets a clean start and end.
        val padded = ShortArray(pcm.size + 16000)
        System.arraycopy(pcm, 0, padded, 8000, pcm.size)
        val bytes = AudioMath.toBytes(padded)
        val notes = ArrayList<String>()
        // Try the offline recognizer first, then the regular one.
        for (onDevice in listOf(true, false)) {
            if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) continue
            val text = attempt(app, bytes, onDevice)
            if (text != null) return text
            notes.add((if (onDevice) "offline recognizer: " else "online recognizer: ") + Ears.errorName(Ears.lastError))
            if (onDevice && Ears.lastError == 13) requestOfflineModel(app)
        }
        lastProblem = notes.joinToString("; ")
        return null
    }

    private var askedForModel = false

    /** Asks Android to download the offline English speech model (it shows its own prompt). */
    private suspend fun requestOfflineModel(app: Context) {
        if (askedForModel || android.os.Build.VERSION.SDK_INT < 33) return
        askedForModel = true
        withContext(Dispatchers.Main) {
            try {
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                sr.triggerModelDownload(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun attempt(app: Context, bytes: ByteArray, onDevice: Boolean): String? {
        return try {
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            Thread {
                try {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
                } catch (_: Exception) {
                }
            }.start()
            Ears.lastError = 0
            val heard = Ears.recognizer.withLock {
                withContext(Dispatchers.Main) {
                    withTimeoutOrNull(15_000) {
                        Ears.recognizeOnce(app, onDevice = onDevice) { intent ->
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                        }
                    }
                }
            }
            try {
                pipe[0].close()
            } catch (_: Exception) {
            }
            heard?.text
        } catch (e: Exception) {
            Ears.lastError = 5
            null
        }
    }

    /** Fallback: Whisper on Obelisk, if the Atlas server is linked and has it installed. */
    private suspend fun obelisk(pcm: ShortArray): String? = withContext(Dispatchers.IO) {
        com.jondude.anzu.assist.Obelisk.transcribe(AudioMath.toBytes(pcm))
    }
}

/** Premium neural voices: ElevenLabs, or any server that speaks the OpenAI speech API. */
object CloudVoice {
    /** ElevenLabs voice list JSON -> (voice id, name). */
    fun parseVoices(body: String): List<Pair<String, String>> = try {
        val arr = org.json.JSONObject(body).optJSONArray("voices") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it.optString("voice_id") to (it.optString("name") + it.optString("category").let { c -> if (c.isBlank()) "" else " · $c" }) }
            .filter { it.first.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking. The voices on the user's ElevenLabs account. */
    fun elevenVoices(): List<Pair<String, String>> = try {
        val (code, body) = com.jondude.anzu.assist.Net.get(
            "https://api.elevenlabs.io/v1/voices", mapOf("xi-api-key" to Prefs.elevenKey.trim()), 15_000
        )
        if (code in 200..299) parseVoices(body) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** The request for the chosen provider, as (url, headers, body), or null if it isn't set up. */
    fun request(text: String): Triple<String, Map<String, String>, String>? = when (Prefs.voiceProvider) {
        1 -> if (Prefs.elevenKey.isBlank() || Prefs.elevenVoiceId.isBlank()) null else Triple(
            "https://api.elevenlabs.io/v1/text-to-speech/${Prefs.elevenVoiceId.trim()}?output_format=mp3_44100_128",
            mapOf("xi-api-key" to Prefs.elevenKey.trim(), "Accept" to "audio/mpeg"),
            org.json.JSONObject().put("text", text)
                .put("model_id", Prefs.elevenModel.trim().ifBlank { "eleven_flash_v2_5" }).toString()
        )
        2 -> if (Prefs.speechUrl.isBlank()) null else Triple(
            Prefs.speechUrl.trim().trimEnd('/') + "/audio/speech",
            if (Prefs.speechKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${Prefs.speechKey.trim()}"),
            org.json.JSONObject().put("model", Prefs.speechModel.trim()).put("voice", Prefs.speechVoice.trim())
                .put("input", text).put("response_format", "mp3").toString()
        )
        else -> null
    }

    /** Blocking. Audio bytes, or null (with Speaker.lastCloudError set) so the phone voice takes over. */
    fun synth(text: String): ByteArray? {
        val (url, headers, body) = request(text) ?: run {
            Speaker.lastCloudError = "Premium voice isn't fully set up."
            return null
        }
        return try {
            val (code, bytes) = com.jondude.anzu.assist.Net.requestBytes("POST", url, headers, body, 30_000)
            if (code in 200..299 && bytes.size > 200) {
                Speaker.lastCloudError = ""
                bytes
            } else {
                Speaker.lastCloudError = "Voice service returned $code: ${String(bytes).take(160)}"
                null
            }
        } catch (e: Exception) {
            Speaker.lastCloudError = "Couldn't reach the voice service: ${e.message}"
            null
        }
    }
}


/**
 * The ring's audio twin: short, quiet sine tones with soft attacks, so they read as presence, not alarms.
 * listen = a gentle rise, think = one low breath, done = a gentle fall,
 * notice = a single soft bell (something new is in the next brief), urgent = two clear notes before speech.
 */
object Earcons {
    private const val RATE = 22_050

    /** Notes as (frequency Hz, length ms, gap ms after). Pure so it can be tested. */
    fun notes(kind: String): List<Triple<Double, Int, Int>> = when (kind) {
        "listen" -> listOf(Triple(659.3, 90, 20), Triple(880.0, 140, 0))   // E5 → A5
        "think" -> listOf(Triple(440.0, 220, 0))                           // A4, held softly
        "notice" -> listOf(Triple(1046.5, 260, 0))                         // C6, bell-like decay
        "urgent" -> listOf(Triple(784.0, 120, 60), Triple(1046.5, 180, 0)) // G5, C6
        else -> listOf(Triple(880.0, 90, 20), Triple(659.3, 140, 0))       // done: A5 → E5
    }

    fun render(kind: String, volume: Float = 0.12f): ShortArray {
        val out = ArrayList<Short>()
        for ((f, ms, gap) in notes(kind)) {
            val n = RATE * ms / 1000
            val attack = (RATE * 0.012).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val env = if (i < attack) i.toDouble() / attack else Math.exp(-4.0 * (i - attack) / n)
                val v = Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t)
                out.add((v / 1.25 * env * volume * Short.MAX_VALUE).toInt().toShort())
            }
            repeat(RATE * gap / 1000) { out.add(0) }
        }
        return out.toShortArray()
    }

    fun play(kind: String) {
        Thread {
            try {
                val pcm = render(kind)
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 80)
                track.release()
            } catch (_: Exception) {
            }
        }.start()
    }
}
