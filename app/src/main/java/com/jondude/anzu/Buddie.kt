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

        private fun part(v: ByteArray, from: Int): ByteArray? =
            v.copyOfRange(from, from + 40).takeIf { m -> m.any { it != 0.toByte() } }

        /**
         * Two layouts exist in the wild. The published firmware puts a speaking flag first and the
         * microphone frame at byte 1; the published app expects the frame at byte 0 with flags at
         * 80 and 81. A real Opus frame never starts with 0 or 1, which tells them apart.
         */
        fun parse(v: ByteArray): BuddiePacket? {
            if (v.size != SIZE) return null
            val first = v[0].toInt() and 0xFF
            return if (first <= 1) {
                BuddiePacket(part(v, 1), first == 1, false)
            } else {
                BuddiePacket(part(v, 0), v[80] == 1.toByte(), v[81] == 0xFE.toByte())
            }
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

    /** A playable WAV file (16 kHz mono) of the given samples. */
    fun wav(pcm: ShortArray): ByteArray {
        val data = toBytes(pcm)
        val b = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + data.size)
        b.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(data.size)
        b.put(data)
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
    /** What the earbuds offer and what was asked of them, e.g. "channels ae01 ae02 ae03 ae04". */
    @Volatile
    var channels = ""
    @Volatile
    var subscriptions = ""
    @Volatile
    var mtu = 0
    @Volatile
    var statusPackets = 0L
    @Volatile
    var lastSegment = ""
    @Volatile
    var earbuds = 0

    fun total(): Long = compressed + opus + statusPackets

    fun reset() {
        compressed = 0; opus = 0; otherSize = 0; segments = 0; recognized = 0; statusPackets = 0
        sensorSeen = false; lastHeard = ""; lastProblem = ""; channels = ""; subscriptions = ""; mtu = 0
        publish()
    }

    private fun link(): String = buildString {
        if (channels.isNotBlank()) append(" · earbud channels: $channels")
        if (subscriptions.isNotBlank()) append(" · subscribed: $subscriptions")
        if (mtu > 0) append(" · packet limit: $mtu")
    }

    fun publish() {
        val total = compressed + opus
        summary.value = if (total == 0L && otherSize == 0) {
            "No audio received yet." + (if (statusPackets > 0) " ($statusPackets status packets)" else "") + link() +
                (if (lastProblem.isNotBlank()) " · problem: $lastProblem" else "")
        } else buildString {
            append("Audio packets: $total")
            append(when {
                compressed > 0 -> " (compressed format)"
                opus > 0 -> " (Opus format)"
                else -> " (unrecognized size $otherSize)"
            })
            append(" · speech segments: $segments · understood: $recognized")
            if (lastSegment.isNotBlank()) append(" · last segment: $lastSegment")
            append(" · your-voice sensor: ${if (sensorSeen) "yes" else "not seen"}")
            if (earbuds > 0) append(" · earbuds linked: $earbuds")
            if (lastHeard.isNotBlank()) append(" · last heard: \"${lastHeard.take(60)}\"")
            if (lastProblem.isNotBlank()) append(" · problem: $lastProblem")
            append(link())
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
    /** One link per earbud: each bud has its own address, and either can be worn alone. */
    private val gatts = HashMap<String, BluetoothGatt>()
    private val linked = HashSet<String>()
    private var scanning = false
    private var lastAudioCount = -1L

    private fun known(): Set<String> {
        val set = HashSet(Prefs.sp.getStringSet("buddieBleAddresses", emptySet()) ?: emptySet())
        if (Prefs.buddieBleAddress.isNotBlank()) set.add(Prefs.buddieBleAddress) // from earlier versions
        return set
    }

    private fun remember(address: String) {
        Prefs.sp.edit().putStringSet("buddieBleAddresses", known() + address).apply()
    }

    fun forget() {
        stop()
        Prefs.buddieBleAddress = ""
        Prefs.sp.edit().remove("buddieBleAddresses").apply()
    }
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
        if (!Prefs.buddieListen) return
        if (!app.has(Manifest.permission.BLUETOOTH_CONNECT)) return
        if (gatts.isEmpty()) BuddieStats.reset()
        // Wait for every earbud we've seen before, and look for ones we haven't (the other bud).
        for (address in known()) if (address !in gatts) connect(address)
        if (!scanning) scan()
    }

    /**
     * Called every half minute while Buddie earbuds are worn. If no audio has arrived since the
     * last call (for example he swapped to the other earbud), search again.
     */
    @Synchronized
    fun nudge(ctx: Context) {
        val count = BuddieStats.compressed + BuddieStats.opus
        val stalled = count == lastAudioCount
        lastAudioCount = count
        if (stalled) start(ctx)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        stopScan()
        for (g in gatts.values) {
            try {
                g.disconnect()
                g.close()
            } catch (_: Exception) {
            }
        }
        gatts.clear()
        linked.clear()
        lastAudioCount = -1
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
            val address = result.device.address
            synchronized(this@BuddieLink) {
                if (address in gatts) return
                remember(address)
                connect(address) // keep scanning: the other earbud may be nearby too
            }
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
            if (gatts.isEmpty()) Glasses.buddieStream.value = "searching"
            scanner.startScan(scanCallback)
            main.postDelayed({
                if (scanning) {
                    stopScan()
                    if (gatts.isEmpty()) Glasses.buddieStream.value = "not found"
                }
            }, 20_000)
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
            if (linked.isEmpty()) Glasses.buddieStream.value = "connecting"
            gatts[address] = dev.connectGatt(app, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Glasses.buddieStream.value = "connection failed"
        }
    }

    val COMPRESSED: UUID = UUID.fromString("0000ae03-0000-1000-8000-00805f9b34fb")

    private fun short(u: UUID): String = u.toString().substring(4, 8)

    /** Ask the earbuds to start notifying on one channel. The result arrives in onDescriptorWrite. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, uuid: UUID): Boolean {
        val ch = g.getService(SERVICE)?.getCharacteristic(uuid) ?: return false
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD)
        if (d == null) {
            BuddieStats.subscriptions += "${short(uuid)}:no-switch "
            return false
        }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
        if (!started) BuddieStats.subscriptions += "${short(uuid)}:refused "
        BuddieStats.publish()
        return started
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val address = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val first = synchronized(linked) { linked.add(address); BuddieStats.earbuds = linked.size; linked.size == 1 }
                if (first) Glasses.buddieStream.value = "connected"
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val none = synchronized(linked) { linked.remove(address); BuddieStats.earbuds = linked.size; linked.isEmpty() }
                if (none) Glasses.buddieStream.value = "waiting for earbuds"
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            BuddieStats.mtu = mtu
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            if (service == null) {
                if (synchronized(linked) { linked.size <= 1 }) {
                    Glasses.buddieStream.value = "connected, but no audio channel found"
                    BuddieStats.channels = g.services.joinToString(" ") { short(it.uuid) }.ifBlank { "none" } + " (services)"
                    BuddieStats.publish()
                }
                return
            }
            BuddieStats.channels = service.characteristics.joinToString(" ") { short(it.uuid) }
            // Newer firmware streams Opus on ae04 and the compressed format on ae03; older units
            // may only have one of them. Try ae04 first, then ae03 if nothing arrives.
            val hasOpus = service.getCharacteristic(AUDIO) != null
            if (!subscribe(g, if (hasOpus) AUDIO else COMPRESSED)) {
                Glasses.buddieStream.value = "connected, but couldn't subscribe to audio"
                return
            }
            Glasses.buddieStream.value = "listening"
            if (hasOpus) main.postDelayed({
                if (gatts.containsValue(g) && BuddieStats.compressed + BuddieStats.opus == 0L) {
                    try {
                        subscribe(g, COMPRESSED)
                    } catch (_: Exception) {
                    }
                }
            }, 5_000)
            main.postDelayed({
                if (gatts.containsValue(g) && BuddieStats.compressed + BuddieStats.opus == 0L) {
                    BuddieStats.lastProblem = "the earbuds accepted the connection but aren't sending audio"
                    BuddieStats.publish()
                }
            }, 12_000)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            BuddieStats.subscriptions += "${short(descriptor.characteristic.uuid)}:${if (status == 0) "ok" else "error $status"} "
            BuddieStats.publish()
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
            } catch (e: Exception) {
                // A bad frame can leave the decoder unusable; start it fresh next time.
                decoder?.release()
                decoder = null
                BuddieStats.lastProblem = "audio decoding failed: ${e.javaClass.simpleName}"
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
                    BuddieStats.statusPackets++
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
            else -> {
                // On the compressed channel the earbuds also send short packets that only carry
                // the speaking flag in their first byte.
                val first = if (v.isEmpty()) 255 else v[0].toInt() and 0xFF
                if (first <= 1) {
                    BuddieStats.sensorSeen = true
                    BuddieStats.statusPackets++
                    if (first == 1) selfUntil = now + 2_000
                } else BuddieStats.otherSize = v.size
            }
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
        // Keep the latest segment so it can be played back from Settings to judge the audio.
        try {
            var peak = 0
            for (x in u.pcm) if (Math.abs(x.toInt()) > peak) peak = Math.abs(x.toInt())
            BuddieStats.lastSegment = "%.1f s, peak %d%%".format(u.pcm.size / 16000f, peak * 100 / 32768)
            java.io.File(ctx.cacheDir, "buddie_last.wav").writeBytes(AudioMath.wav(u.pcm))
        } catch (_: Exception) {
        }
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
