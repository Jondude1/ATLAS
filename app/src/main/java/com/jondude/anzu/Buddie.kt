package com.jondude.anzu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jondude.anzu.assist.Brain
import com.jondude.anzu.assist.Corin
import com.jondude.anzu.assist.Obelisk
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * One 84-byte audio packet from Buddie earbuds (format from the open-source Buddie app):
 * bytes 0..39 an Opus frame from the microphone, 40..79 an Opus frame of what the earbud
 * is playing, byte 80 = 1 while the wearer is speaking (bone conduction), byte 81 = 0xFE
 * while the earbuds are in meeting mode.
 */
class BuddiePacket(val mic: ByteArray?, val selfSpeaking: Boolean, val meeting: Boolean) {
    companion object {
        const val SIZE = 84
        const val LEGACY_SIZE = 244

        fun parse(v: ByteArray): BuddiePacket? {
            if (v.size != SIZE) return null
            val mic = v.copyOfRange(0, 40)
            return BuddiePacket(
                mic.takeIf { m -> m.any { it != 0.toByte() } },
                v[80] == 1.toByte(),
                v[81] == 0xFE.toByte()
            )
        }
    }
}

class Utterance(val pcm: ShortArray, val selfRatio: Float)

/** Cuts the continuous 16 kHz stream into utterances by loudness. */
class Segmenter(private val rate: Int = 16000, private val threshold: Double = 450.0) {
    private var buf = ShortArray(rate * 4)
    private var len = 0
    private var active = false
    private var speechMs = 0
    private var silenceMs = 0
    private var frames = 0
    private var selfFrames = 0
    private var previous = ShortArray(0)

    private fun append(a: ShortArray) {
        if (len + a.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + a.size))
        System.arraycopy(a, 0, buf, len, a.size)
        len += a.size
    }

    private fun rms(a: ShortArray): Double {
        if (a.isEmpty()) return 0.0
        var sum = 0.0
        for (s in a) sum += s.toDouble() * s
        return sqrt(sum / a.size)
    }

    private fun finish(): Utterance? {
        val u = if (speechMs >= 300) Utterance(buf.copyOf(len), if (frames == 0) 0f else selfFrames.toFloat() / frames) else null
        len = 0
        active = false
        speechMs = 0
        silenceMs = 0
        frames = 0
        selfFrames = 0
        return u
    }

    /** Feed the next chunk; returns a finished utterance when one ends. */
    fun feed(pcm: ShortArray, selfSpeaking: Boolean): Utterance? {
        if (pcm.isEmpty()) return null
        val ms = pcm.size * 1000 / rate
        val loud = rms(pcm) > threshold
        if (loud) {
            if (!active) {
                active = true
                append(previous) // a little lead-in so the first syllable isn't clipped
            }
            append(pcm)
            speechMs += ms
            silenceMs = 0
            frames++
            if (selfSpeaking) selfFrames++
        } else if (active) {
            append(pcm)
            silenceMs += ms
            if (silenceMs >= 900) return finish()
        }
        previous = pcm
        if (active && len >= rate * 20) return finish()
        return null
    }
}

object AudioMath {
    /** Average groups of [factor] samples: 48 kHz to 16 kHz when factor is 3. */
    fun downsample(src: ShortArray, factor: Int): ShortArray {
        if (factor <= 1) return src
        val out = ShortArray(src.size / factor)
        for (i in out.indices) {
            var sum = 0
            for (j in 0 until factor) sum += src[i * factor + j]
            out[i] = (sum / factor).toShort()
        }
        return out
    }

    /** The identification header an Opus decoder needs: mono, 16 kHz input. */
    fun opusHead(): ByteArray {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII))
        b.put(1.toByte()) // version
        b.put(1.toByte()) // channels
        b.putShort(0)     // pre-skip
        b.putInt(16000)   // input sample rate
        b.putShort(0)     // gain
        b.put(0.toByte()) // mapping family
        return b.array()
    }

    fun toBytes(pcm: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}

/** Decides whether something Jon said was addressed to Corin. */
object Wake {
    private fun variants(name: String): List<String> {
        val n = name.trim().lowercase()
        val extra = if (n == "corin") listOf("corrin", "coren", "corinne", "koren", "korin", "corrine", "cor in") else emptyList()
        return (listOf(n) + extra).filter { it.isNotBlank() }
    }

    /** Returns the request (possibly empty, for a bare "Corin?"), or null if he wasn't talking to it. */
    fun extract(text: String, name: String): String? {
        val t = text.trim()
        for (v in variants(name)) {
            val m = Regex("(?i)(?:\\b(?:hey|ok|okay|yo)[ ,]+)?\\b" + Regex.escape(v) + "\\b[,.!?]*").find(t) ?: continue
            val after = t.substring(m.range.last + 1).trim().trimStart(',', '.', ' ')
            val before = t.substring(0, m.range.first).trim().trimEnd(',', '.', ' ')
            return if (after.isNotBlank()) after else before
        }
        return null
    }
}

/**
 * Decoder for Buddie's default "compressed spectrum" stream (244-byte packets), derived from the
 * open-source Buddie firmware (MIT licence, github.com/Buddie-AI/Buddie, fft_and_pca.c).
 * Each 80-byte block is one 16 ms frame: 47 signed bytes of a PCA-compressed magnitude spectrum
 * followed by 257 sign bits. Decoding undoes the PCA (its rows are orthonormal, so the inverse is
 * the transpose), restores the signs, and runs the inverse of the firmware's DCT.
 */
class PcaCodec(private val w: FloatArray) {
    private val cosT = FloatArray(512) { Math.cos(Math.PI * it / 256.0).toFloat() }
    private val spec = FloatArray(N)

    /** Decodes the block starting at [offset] into 256 samples of 16 kHz audio. */
    fun decodeBlock(v: ByteArray, offset: Int): ShortArray {
        // magnitude[k] = max(0, 256 * sum_i c[i] * W[i][k])
        java.util.Arrays.fill(spec, 0f)
        for (i in 0 until PCA) {
            val c = v[offset + i].toFloat()
            if (c == 0f) continue
            val row = i * N
            for (k in 0 until N) spec[k] += c * w[row + k]
        }
        for (k in 0 until N) {
            var m = spec[k] * 256f
            if (m < 0f) m = 0f
            val negative = (v[offset + PCA + (k shr 3)].toInt() shr (k and 7)) and 1 == 1
            spec[k] = (if (negative) -m else m) / F
        }
        // inverse DCT-I; sample 0 is the overlap sample and is dropped
        val out = ShortArray(HOP)
        for (n in 1 until N) {
            var acc = spec[0] + (if (n and 1 == 0) spec[N - 1] else -spec[N - 1])
            var idx = 0
            for (k in 1 until N - 1) {
                idx = (idx + n) and 511
                acc += 2f * spec[k] * cosT[idx]
            }
            var x = acc / (2f * (N - 1))
            if (n == N - 1) x /= SQRT2
            out[n - 1] = x.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return out
    }

    companion object {
        const val N = 257
        const val PCA = 47
        const val HOP = 256
        const val BLOCK = 80
        const val SQRT2 = 1.41421356f
        /** The firmware's output scale: 0.5 * sqrt(2 / (N - 1)). */
        const val F = 0.0441941738f

        fun fromBytes(bytes: ByteArray): PcaCodec {
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val w = FloatArray(PCA * N)
            fb.get(w)
            return PcaCodec(w)
        }

        fun load(ctx: Context): PcaCodec = fromBytes(ctx.assets.open("buddie_pca.bin").use { it.readBytes() })
    }
}

/** What the earbud stream is actually doing, so problems can be read off the screen. */
object BuddieStats {
    var compressed = 0L
    var opus = 0L
    var otherSize = 0
    var segments = 0
    var recognized = 0
    @Volatile
    var sensorSeen = false
    @Volatile
    var lastHeard = ""
    @Volatile
    var lastProblem = ""
    val summary = kotlinx.coroutines.flow.MutableStateFlow("No audio received yet.")

    fun reset() {
        compressed = 0; opus = 0; otherSize = 0; segments = 0; recognized = 0
        sensorSeen = false; lastHeard = ""; lastProblem = ""
        publish()
    }

    fun publish() {
        val total = compressed + opus
        summary.value = if (total == 0L && otherSize == 0) "No audio received yet." else buildString {
            append("Audio packets: $total")
            append(when {
                compressed > 0 -> " (compressed format)"
                opus > 0 -> " (Opus format)"
                else -> " (unrecognized size $otherSize)"
            })
            append(" · speech segments: $segments · understood: $recognized")
            append(" · your-voice sensor: ${if (sensorSeen) "yes" else "not seen"}")
            if (lastHeard.isNotBlank()) append(" · last heard: \"${lastHeard.take(60)}\"")
            if (lastProblem.isNotBlank()) append(" · problem: $lastProblem")
        }
    }
}

/** Opus to PCM using the platform decoder. */
internal class OpusDec {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var outRate = 48000
    private var pts = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(AudioMath.opusHead()))
        fmt.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, 0L))
        fmt.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, 80_000_000L))
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    fun decode(packet: ByteArray): ShortArray {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
            val inBuf = codec.getInputBuffer(i) ?: return ShortArray(0)
            inBuf.clear()
            inBuf.put(packet)
            codec.queueInputBuffer(i, 0, packet.size, pts, 0)
            pts += 20_000
        }
        var out = ShortArray(0)
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)
                if (b != null && info.size > 0) {
                    val chunk = ShortArray(info.size / 2)
                    b.order(ByteOrder.nativeOrder()).asShortBuffer().get(chunk)
                    out += chunk
                }
                codec.releaseOutputBuffer(o, false)
            } else break
        }
        return AudioMath.downsample(out, outRate / 16000)
    }

    fun release() {
        try {
            codec.stop()
            codec.release()
        } catch (_: Exception) {
        }
    }
}

/** Meeting notes built from the Buddie stream. */
object Meeting {
    private val lines = ArrayList<String>()
    private var startedAt = 0L

    /** True when the earbuds' own meeting switch started this one (so it can end it too). */
    @Volatile
    var fromEarbuds = false

    @Synchronized
    fun start() {
        if (Glasses.meeting.value) return
        fromEarbuds = false
        lines.clear()
        startedAt = System.currentTimeMillis()
        Glasses.meeting.value = true
    }

    @Synchronized
    fun add(who: String, text: String) {
        lines.add("$who: $text")
    }

    @Synchronized
    private fun take(): String {
        Glasses.meeting.value = false
        val t = lines.joinToString("\n")
        lines.clear()
        return t
    }

    /** Ends the meeting, saves the transcript, and returns a spoken one-line result. */
    suspend fun finish(ctx: Context): String {
        if (!Glasses.meeting.value) return "No meeting was being recorded."
        val transcript = take()
        if (transcript.length < 40) return "Meeting ended. I didn't catch enough to make notes."
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(startedAt))
        try {
            val dir = File(ctx.filesDir, "meetings").apply { mkdirs() }
            File(dir, "$stamp.md").writeText(transcript)
        } catch (_: Exception) {
        }
        val summary = Brain.summarizeMeeting(transcript)
        val note = "Meeting $stamp\n" + (summary ?: transcript.take(1500))
        Store.addNote(note)
        Store.appendLog("meeting", summary ?: "Transcript saved, no summary.")
        Obelisk.log("meeting", note)
        Store.logAction("Wrote meeting notes for $stamp")
        return if (summary != null) "Meeting notes are saved." else "Meeting transcript is saved, but I couldn't summarize it."
    }
}

/** The Bluetooth LE link to Buddie earbuds and everything that reacts to what they hear. */
object BuddieLink {
    val SERVICE: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
    val AUDIO: UUID = UUID.fromString("0000ae04-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var appCtx: Context? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var decoder: OpusDec? = null
    private var pca: PcaCodec? = null
    /** The wearer counts as speaking until this time (the earbuds report it in bursts). */
    private var selfUntil = 0L
    private var segmenter = Segmenter()
    private val order = Mutex()
    private var meetingOnSince = 0L
    private var meetingOffSince = 0L
    private var packets = 0L

    private fun adapter() = (appCtx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (!Prefs.buddieListen || gatt != null || scanning) return
        if (!app.has(Manifest.permission.BLUETOOTH_CONNECT)) return
        val saved = Prefs.buddieBleAddress
        if (saved.isNotBlank()) connect(saved) else scan()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        worker.execute {
            decoder?.release()
            decoder = null
            segmenter = Segmenter()
            packets = 0
        }
        Glasses.buddieStream.value = "off"
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            var name: String? = null
            try {
                name = result.device.name ?: result.scanRecord?.deviceName
            } catch (_: Exception) {
            }
            if (name == null || !name.startsWith("Buddie", ignoreCase = true)) return
            stopScan()
            Prefs.buddieBleAddress = result.device.address
            connect(result.device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        val app = appCtx ?: return
        if (!app.has(Manifest.permission.BLUETOOTH_SCAN)) {
            Glasses.buddieStream.value = "needs the nearby-devices permission"
            return
        }
        val scanner = adapter()?.bluetoothLeScanner ?: return
        try {
            scanning = true
            Glasses.buddieStream.value = "searching"
            scanner.startScan(scanCallback)
            main.postDelayed({
                if (scanning) {
                    stopScan()
                    Glasses.buddieStream.value = "not found"
                }
            }, 30_000)
        } catch (e: Exception) {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        val app = appCtx ?: return
        try {
            val dev = adapter()?.getRemoteDevice(address) ?: return
            Glasses.buddieStream.value = "connecting"
            BuddieStats.reset()
            gatt = dev.connectGatt(app, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Glasses.buddieStream.value = "connection failed"
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Glasses.buddieStream.value = "connected"
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Glasses.buddieStream.value = "waiting for earbuds"
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(AUDIO)
            if (ch == null) {
                Glasses.buddieStream.value = "connected, but no audio channel found"
                return
            }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
            Glasses.buddieStream.value = "listening"
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onPacket(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { onPacket(it.copyOf()) }
        }
    }

    private fun onPacket(v: ByteArray) {
        worker.execute {
            try {
                handle(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(v: ByteArray) {
        val now = System.currentTimeMillis()
        packets++
        if (packets % 250 == 1L) {
            Glasses.buddieStream.value = "listening"
            BuddieStats.publish()
        }
        when (v.size) {
            BuddiePacket.LEGACY_SIZE -> {
                val header = v[0].toInt() and 0xFF
                if (header == 0x00 || header == 0x01) {
                    // a status packet from the speaking (bone conduction) sensor, no audio
                    BuddieStats.sensorSeen = true
                    if (header == 0x01) selfUntil = now + 2_000
                    return
                }
                if (header != 0xFF && header != 0xFE) return
                BuddieStats.compressed++
                if (!trackCall(header == 0xFE)) return
                val ctx = appCtx ?: return
                val codec = pca ?: PcaCodec.load(ctx).also { pca = it }
                for (i in 0 until 3) {
                    val pcm = codec.decodeBlock(v, 1 + i * PcaCodec.BLOCK)
                    segmenter.feed(pcm, now < selfUntil)?.let { onUtterance(it) }
                }
            }
            BuddiePacket.SIZE -> {
                val p = BuddiePacket.parse(v) ?: return
                BuddieStats.opus++
                if (p.selfSpeaking) {
                    BuddieStats.sensorSeen = true
                    selfUntil = now + 2_000
                }
                if (!trackCall(p.meeting)) return
                val pcm = if (p.mic != null) {
                    val dec = decoder ?: OpusDec().also { decoder = it }
                    dec.decode(p.mic)
                } else ShortArray(320) // 20 ms of silence keeps the segmenter's clock running
                segmenter.feed(pcm, now < selfUntil)?.let { onUtterance(it) }
            }
            else -> BuddieStats.otherSize = v.size
        }
    }

    /**
     * The earbuds flag when a phone call is running. Calls are only transcribed if Jon turned on
     * call notes; otherwise call audio is dropped. Returns whether to process this packet's audio.
     */
    private fun trackCall(onCall: Boolean): Boolean {
        if (onCall && !Prefs.callNotes) return false
        trackMeetingFlag(onCall)
        return true
    }

    /** The earbuds can switch meeting mode themselves; follow them, with some debounce. */
    private fun trackMeetingFlag(on: Boolean) {
        val now = System.currentTimeMillis()
        val ctx = appCtx ?: return
        if (on) {
            meetingOffSince = 0
            if (meetingOnSince == 0L) meetingOnSince = now
            if (!Glasses.meeting.value && now - meetingOnSince > 3_000) {
                Meeting.start()
                Meeting.fromEarbuds = true
                Corin.announce("Taking meeting notes.")
            }
        } else {
            meetingOnSince = 0
            if (Glasses.meeting.value && Meeting.fromEarbuds) {
                if (meetingOffSince == 0L) meetingOffSince = now
                if (now - meetingOffSince > 10_000) {
                    meetingOffSince = 0
                    Corin.scope.launch { Corin.announce(Meeting.finish(ctx)) }
                }
            }
        }
    }

    private fun onUtterance(u: Utterance) {
        val ctx = appCtx ?: return
        // While Corin itself is talking or listening, what the earbuds hear is mostly Corin.
        if (Glasses.corinState.value != "idle") return
        BuddieStats.segments++
        // If these earbuds never report who is speaking, any voice might be his: the wake word
        // still has to be said, but nearby speech gets transcribed on the phone to look for it.
        val sensor = BuddieStats.sensorSeen
        val mine = if (sensor) u.selfRatio > 0.3f else true
        val meeting = Glasses.meeting.value
        if (!mine && !meeting && !Prefs.buddieAmbient) return
        Corin.scope.launch {
            order.withLock {
                val text = Transcriber.transcribe(ctx, u.pcm)?.trim().orEmpty()
                if (text.length < 2) {
                    BuddieStats.lastProblem = Transcriber.lastProblem.ifBlank { "speech wasn't understood" }
                    BuddieStats.publish()
                    return@withLock
                }
                BuddieStats.recognized++
                BuddieStats.lastHeard = text
                BuddieStats.lastProblem = ""
                BuddieStats.publish()
                if (meeting) {
                    val cmd = if (mine) Wake.extract(text, Prefs.name) else null
                    if (cmd != null && Regex("(?i)\\b(stop|end|finish)\\b").containsMatchIn(cmd)) {
                        Corin.announce(Meeting.finish(ctx))
                    } else Meeting.add(if (mine && sensor) "Jon" else if (sensor) "Other" else "Voice", text)
                    return@withLock
                }
                if (mine) {
                    val cmd = if (Prefs.wakeWord) Wake.extract(text, Prefs.name) else null
                    if (cmd != null) {
                        Corin.heard(ctx, cmd)
                        return@withLock
                    }
                    if (System.currentTimeMillis() < Corin.windowUntil) {
                        Corin.heard(ctx, text)
                        return@withLock
                    }
                }
                if (Prefs.buddieAmbient) {
                    val line = "${if (!sensor) "Heard" else if (mine) "Jon" else "Someone nearby"}: $text"
                    Glasses.noteAmbient(line)
                    Store.appendLog("heard", line)
                }
            }
        }
    }
}
