package com.jondude.anzu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.jondude.anzu.assist.Atlas
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Hands-free "Atlas" through any headset (Galaxy Buds and the like) or the phone itself.
 *
 * A small keyword spotter (sherpa-onnx zipformer, 3.3M parameters, bundled) runs on the phone on the audio from the
 * chosen mic. Nothing leaves the phone before a wake. On "Atlas" or "Hey Atlas" it stops capturing, and one normal
 * ask window opens (same flow as the Talk button). Then it goes back to listening.
 *
 * Holding a Bluetooth mic open keeps the buds in call mode (more bud battery, call-quality audio), so it steps
 * aside whenever something plays or a call is on, during Quiet, and while Atlas is busy, and says so.
 * The Buddie earbuds have their own always-on stream and wake word, so this stays out of their way.
 */
object WakeListener {
    /** "listening · Galaxy Buds", "paused: music playing", "off", … */
    val state = MutableStateFlow("off")

    @Volatile private var running = false
    private var thread: Thread? = null
    private var spotter: KeywordSpotter? = null

    /** What the wake listener should be doing right now. Pure, so it can be tested. */
    data class Decision(val listen: Boolean, val headset: Boolean, val why: String)

    fun decide(
        enabled: Boolean, micSource: Int, worn: Boolean, buddieStreaming: Boolean, scoAvailable: Boolean,
        musicPlaying: Boolean, inCall: Boolean, quiet: Boolean, busy: Boolean, micAllowed: Boolean
    ): Decision = when {
        !enabled -> Decision(false, false, "off")
        buddieStreaming -> Decision(false, false, "the Buddie earbuds are listening")
        !micAllowed -> Decision(false, false, "paused: open Atlas once so it can listen in the background")
        inCall -> Decision(false, false, "paused: on a call")
        musicPlaying -> Decision(false, false, "paused: music playing")
        quiet -> Decision(false, false, "paused: quiet time")
        busy -> Decision(false, false, "paused: talking")
        micSource == 2 -> Decision(true, false, "listening · ${MicRoute.phoneName()}")
        micSource == 1 && !scoAvailable -> Decision(false, true, "paused: no headset connected")
        micSource == 1 -> Decision(true, true, "listening")
        // Auto: through whatever's worn; never through the phone in a pocket.
        worn && scoAvailable -> Decision(true, true, "listening")
        worn -> Decision(false, true, "paused: the headset has no mic link")
        else -> Decision(false, false, "paused: nothing worn")
    }

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        thread = Thread({ loop(app) }, "atlas-wake").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        state.value = "off"
    }

    private fun spotter(ctx: Context): KeywordSpotter? = spotter ?: try {
        KeywordSpotter(
            ctx.assets,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "kws/encoder.int8.onnx", decoder = "kws/decoder.int8.onnx", joiner = "kws/joiner.int8.onnx"
                    ),
                    tokens = "kws/tokens.txt", numThreads = 1, modelType = "zipformer2"
                ),
                keywordsFile = "kws/keywords.txt", keywordsScore = 1.0f, keywordsThreshold = 0.1f, numTrailingBlanks = 1
            )
        ).also { spotter = it }
    } catch (e: Throwable) {
        state.value = "unavailable: ${e.message ?: "the wake model didn't load"}"
        null
    }

    /** Runs the spotter over 16 kHz audio and says whether it heard the wake word (used by the device test). */
    fun spots(ctx: Context, pcm: ShortArray): Boolean {
        val kws = spotter(ctx) ?: return false
        val stream = kws.createStream()
        var hit = false
        try {
            var i = 0
            while (i < pcm.size && !hit) {
                val n = minOf(1600, pcm.size - i)
                stream.acceptWaveform(FloatArray(n) { pcm[i + it] / 32768f }, 16000)
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                    if (kws.getResult(stream).keyword.isNotBlank()) { hit = true; kws.reset(stream) }
                }
                i += n
            }
        } finally {
            stream.release()
        }
        return hit
    }

    private fun scoDevice(am: AudioManager): AudioDeviceInfo? = try {
        am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    } catch (_: Exception) {
        null
    }

    private fun current(ctx: Context, am: AudioManager): Decision = decide(
        enabled = Prefs.wakeListen,
        micSource = Prefs.micSource,
        worn = Glasses.connected.value,
        buddieStreaming = Glasses.active.value == "buddie" && Glasses.buddieStream.value == "listening",
        scoAvailable = scoDevice(am) != null,
        musicPlaying = am.isMusicActive,
        inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_RINGTONE ||
            (am.mode == AudioManager.MODE_IN_COMMUNICATION && Glasses.atlasState.value == "idle" && !holdingSco),
        quiet = System.currentTimeMillis() < Prefs.quietUntil,
        busy = Atlas.busy || Glasses.atlasState.value != "idle",
        micAllowed = ctx.has(Manifest.permission.RECORD_AUDIO) && Glasses.micArmed.value
    )

    @Volatile private var holdingSco = false

    @SuppressLint("MissingPermission")
    private fun loop(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        while (running) {
            val d = current(ctx, am)
            state.value = d.why
            if (!d.listen) {
                sleep(2000)
                continue
            }
            val kws = spotter(ctx) ?: run { sleep(10_000); null } ?: continue
            // Route the mic: the headset over SCO, or the phone's own array (and make sure SCO is let go).
            var label = MicRoute.phoneName()
            if (d.headset) {
                val sco = scoDevice(am) ?: run { sleep(2000); null } ?: continue
                holdingSco = try { am.setCommunicationDevice(sco) } catch (_: Exception) { false }
                if (!holdingSco) { sleep(3000); continue }
                label = sco.productName?.toString()?.takeIf { it.isNotBlank() } ?: "headset mic"
                sleep(600) // let the link come up
            } else {
                try { am.clearCommunicationDevice() } catch (_: Exception) {}
            }
            val rec = try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
            } catch (_: Exception) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release(); release(am); sleep(5000); continue
            }
            if (d.headset) scoDevice(am)?.let { sco ->
                try {
                    am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.productName == sco.productName }
                        ?.let { rec.preferredDevice = it }
                } catch (_: Exception) {}
            }
            state.value = "listening · $label"
            Glasses.inputSource.value = label
            val stream = kws.createStream()
            val buf = ShortArray(1600) // 100 ms
            var heard = false
            var checkedAt = System.currentTimeMillis()
            try {
                rec.startRecording()
                while (running && !heard) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { sleep(50); continue }
                    var sum = 0.0
                    for (k in 0 until n) sum += buf[k].toDouble() * buf[k]
                    com.jondude.anzu.assist.SocialGrace.background(Math.sqrt(sum / n))
                    if (com.jondude.anzu.assist.SocialGrace.loud) { state.value = "paused: too loud around you"; continue }
                    val f = FloatArray(n) { buf[it] / 32768f }
                    stream.acceptWaveform(f, 16000)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        if (kws.getResult(stream).keyword.isNotBlank()) {
                            kws.reset(stream)
                            heard = true
                        }
                    }
                    // Every second, check whether anything changed (music started, buds out, quiet, Atlas busy).
                    val now = System.currentTimeMillis()
                    if (now - checkedAt > 1000) {
                        checkedAt = now
                        val again = current(ctx, am)
                        if (!again.listen || again.headset != d.headset) break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                stream.release()
                release(am)
            }
            if (heard) wake(ctx, label)
        }
        release(am)
    }

    private fun release(am: AudioManager) {
        if (holdingSco) {
            try { am.clearCommunicationDevice() } catch (_: Exception) {}
            holdingSco = false
        }
    }

    /** One ask window, exactly like the Talk button, then back to listening. */
    private fun wake(ctx: Context, source: String) {
        Prefs.lastWake = System.currentTimeMillis()
        Prefs.lastWakeSource = source
        state.value = "woke · $source"
        if (System.currentTimeMillis() < Prefs.quietUntil) return
        val done = java.util.concurrent.CountDownLatch(1)
        Atlas.scope.launch {
            try {
                Atlas.converse(ctx, null, true, maxTurns = 1)
            } finally {
                done.countDown()
            }
        }
        try { done.await(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        sleep(800)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
