package com.jondude.anzu

import android.media.MediaCodec
import android.media.MediaFormat
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs on a real Android system: checks that audio shaped like the Buddie earbud stream
 * (16 kHz mono Opus, 20 ms frames) survives the app's decoder and utterance cutter.
 */
@RunWith(AndroidJUnit4::class)
class OpusDeviceTest {
    private fun rms(a: ShortArray): Double {
        var s = 0.0
        for (x in a) s += x.toDouble() * x
        return sqrt(s / maxOf(1, a.size))
    }

    /** Encodes PCM to Opus packets with the platform encoder, one packet per 20 ms frame. */
    private fun encode(pcm: ShortArray): List<ByteArray> {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 16000)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val packets = ArrayList<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var sentEos = false
        var done = false
        var guard = 0
        while (!done && guard++ < 5000) {
            if (!sentEos) {
                val i = enc.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val buf = enc.getInputBuffer(i)!!
                    buf.clear()
                    val n = minOf(320, pcm.size - pos)
                    if (n <= 0) {
                        enc.queueInputBuffer(i, 0, 0, pos * 1_000_000L / 16000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sentEos = true
                    } else {
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                        enc.queueInputBuffer(i, 0, n * 2, pos * 1_000_000L / 16000, 0)
                        pos += n
                    }
                }
            }
            val o = enc.dequeueOutputBuffer(info, 5_000)
            if (o >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val b = enc.getOutputBuffer(o)!!
                    val p = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(p)
                    packets.add(p)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                enc.releaseOutputBuffer(o, false)
            }
        }
        enc.stop()
        enc.release()
        return packets
    }

    @Test
    fun decodesOpusAndFindsTheUtterance() {
        val pcm = ShortArray(16000 * 2) { (8000 * sin(2 * PI * 300 * it / 16000.0)).toInt().toShort() }
        val packets = encode(pcm)
        Log.i("CORINTEST", "encoded ${packets.size} packets, first sizes ${packets.take(5).map { it.size }}")
        assertTrue("expected about 100 packets, got ${packets.size}", packets.size in 80..120)

        val dec = OpusDec()
        val seg = Segmenter()
        var total = 0
        var energy = 0.0
        var utterance: Utterance? = null
        for (p in packets) {
            val out = dec.decode(p)
            total += out.size
            if (out.isNotEmpty()) {
                energy = maxOf(energy, rms(out))
                utterance = utterance ?: seg.feed(out, true)
            }
        }
        repeat(60) { utterance = utterance ?: seg.feed(ShortArray(320), false) }
        dec.release()
        Log.i("CORINTEST", "decoded $total samples from ${pcm.size}, peak rms $energy")

        assertTrue("decoded $total samples from ${pcm.size}", total > pcm.size * 7 / 10 && total < pcm.size * 13 / 10)
        assertTrue("decoded audio is too quiet: $energy", energy > 1500)
        assertNotNull("no utterance found", utterance)
        assertTrue(utterance!!.selfRatio > 0.9f)
    }

    /** The transcriber must come back (with text or nothing) and never hang or crash. */
    @Test
    fun transcriberReturns() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i(
            "CORINTEST",
            "recognizer available=${SpeechRecognizer.isRecognitionAvailable(ctx)} " +
                "onDevice=${SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)}"
        )
        val pcm = ShortArray(16000) { (6000 * sin(2 * PI * 220 * it / 16000.0)).toInt().toShort() }
        val result = runBlocking { withTimeoutOrNull(25_000) { Transcriber.transcribe(ctx, pcm) ?: "(nothing recognized)" } }
        Log.i("CORINTEST", "transcriber result: $result")
        assertNotNull("transcriber hung", result)
    }
}
