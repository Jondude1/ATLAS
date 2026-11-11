package com.jondude.anzu

import android.content.Context
import android.net.ConnectivityManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.jondude.anzu.assist.Atlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline speech recognition for the earbud stream: Whisper (distil-small.en) running on the phone
 * through sherpa-onnx. Android's own recognizer can't make sense of the earbuds' heavily compressed
 * audio; this model can. The model (about 300 MB) is downloaded once from this app's GitHub releases.
 */
object Whisperer {
    private const val BASE = "https://github.com/Jondude1/Anzu-1/releases/download/models-v1/"
    const val ENCODER = "distil-small.en-encoder.int8.onnx"
    const val DECODER = "distil-small.en-decoder.int8.onnx"
    const val TOKENS = "distil-small.en-tokens.txt"
    val FILES = listOf(ENCODER to 102961431L, DECODER to 195079097L, TOKENS to 835554L)

    /** "", "downloading 43%", "ready", or "failed: reason". */
    val status = MutableStateFlow("")
    @Volatile
    private var rec: OfflineRecognizer? = null
    @Volatile
    private var downloading = false
    private val lock = Mutex()

    private fun dir(ctx: Context) = File(ctx.filesDir, "asr").apply { mkdirs() }

    fun downloaded(ctx: Context): Boolean = FILES.all { File(dir(ctx), it.first).length() == it.second }

    /** Start the one-time model download if it's needed. On mobile data it waits for [force]. */
    fun ensure(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (downloaded(app)) {
            if (status.value.isBlank() || status.value.startsWith("waiting")) status.value = "ready"
            return
        }
        if (downloading) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!force && cm.isActiveNetworkMetered) {
            status.value = "waiting for Wi-Fi (300 MB)"
            return
        }
        downloading = true
        Atlas.scope.launch(Dispatchers.IO) {
            try {
                downloadBlocking(app)
            } finally {
                downloading = false
            }
        }
    }

    /** Blocking download of whatever is missing. Returns true when the model is complete. */
    fun downloadBlocking(app: Context): Boolean {
        return try {
            val total = FILES.sumOf { it.second }
            var done = FILES.filter { File(dir(app), it.first).length() == it.second }.sumOf { it.second }
            for ((name, size) in FILES) {
                val target = File(dir(app), name)
                if (target.length() == size) continue
                val part = File(dir(app), "$name.part")
                val conn = open(BASE + name)
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var lastShown = -1L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = done * 100 / total
                            if (pct != lastShown) {
                                lastShown = pct
                                status.value = "downloading $pct%"
                            }
                        }
                    }
                }
                if (part.length() != size) {
                    part.delete()
                    throw RuntimeException("$name arrived incomplete")
                }
                target.delete()
                part.renameTo(target)
            }
            status.value = "ready"
            true
        } catch (e: Exception) {
            status.value = "failed: ${e.message}"
            false
        }
    }

    /** GitHub redirects release downloads to another host, so follow by hand. */
    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            val conn = URL(com.jondude.anzu.tailnet(url)).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399) {
                url = conn.getHeaderField("Location") ?: throw RuntimeException("redirect without location")
                conn.disconnect()
            } else if (code == 200) {
                return conn
            } else throw RuntimeException("download server said $code")
        }
        throw RuntimeException("too many redirects")
    }

    /**
     * Cleans up decoded earbud audio for recognition: removes the low rumble the earbuds' codec leaves behind
     * (150 Hz high-pass), then brings quiet speech up by its average level rather than its loudest click
     * (up to 40x, more with the mic boost), with a soft limiter so peaks never clip.
     */
    fun prepare(pcm: ShortArray, boost: Int = Prefs.micBoost): FloatArray {
        val out = FloatArray(pcm.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var sum = 0.0
        for (i in pcm.indices) {
            val x = pcm[i].toDouble()
            val y = B0 * x + B1 * x1 + B0 * x2 - A1 * y1 - A2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            out[i] = (y / 32768.0).toFloat()
            sum += (y / 32768.0) * (y / 32768.0)
        }
        val rms = Math.sqrt(sum / maxOf(1, pcm.size))
        val maxGain = when (boost) { 0 -> 20.0; 2 -> 80.0; else -> 40.0 }
        val gain = if (rms < 1e-6) 1.0 else (0.12 / rms).coerceIn(1.0, maxGain)
        for (i in out.indices) out[i] = (0.95 * Math.tanh(out[i] * gain / 0.95)).toFloat()
        return out
    }

    private const val B0 = 0.9592031496383375
    private const val B1 = -1.918406299276675
    private const val A1 = -1.9167412231576226
    private const val A2 = 0.9200713753957276

    /** Whisper marks non-speech as things like [BLANK_AUDIO] or (music); those aren't words. */
    fun clean(text: String): String =
        text.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

    private fun load(app: Context): OfflineRecognizer {
        val d = dir(app)
        val config = OfflineRecognizerConfig().apply {
            modelConfig = OfflineModelConfig().apply {
                whisper = OfflineWhisperModelConfig().apply {
                    encoder = File(d, ENCODER).absolutePath
                    decoder = File(d, DECODER).absolutePath
                    language = "en"
                    task = "transcribe"
                }
                tokens = File(d, TOKENS).absolutePath
                numThreads = 4
                modelType = "whisper"
            }
        }
        return OfflineRecognizer(null, config)
    }

    /** Returns the words heard ("" for no speech), or null if the model isn't usable. */
    suspend fun transcribe(ctx: Context, pcm: ShortArray): String? {
        val app = ctx.applicationContext
        if (!downloaded(app)) return null
        return lock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val r = rec ?: load(app).also { rec = it }
                    val stream = r.createStream()
                    try {
                        stream.acceptWaveform(prepare(pcm), 16000)
                        r.decode(stream)
                        clean(r.getResult(stream).text)
                    } finally {
                        stream.release()
                    }
                } catch (t: Throwable) {
                    status.value = "failed: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
                    null
                }
            }
        }
    }

    /** Free the model's memory when no earbuds are streaming. */
    fun release() {
        Atlas.scope.launch {
            lock.withLock {
                try {
                    rec?.release()
                } catch (_: Throwable) {
                }
                rec = null
            }
        }
    }
}
