package com.jondude.anzu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The whole listening chain on a real Android system: download the speech model, push real speech
 * through the earbud firmware's compression and the app's decoder, and check the words come out.
 */
@RunWith(AndroidJUnit4::class)
class WhisperDeviceTest {
    private fun fetch(url: String): ByteArray {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            if (c.responseCode in 300..399) {
                u = c.getHeaderField("Location")
                c.disconnect()
            } else return c.inputStream.use { it.readBytes() }
        }
        throw IllegalStateException("too many redirects")
    }

    /** One 16 ms frame through the firmware's encoder (fft_and_pca.c). */
    private fun firmwareEncode(w: FloatArray, frame: DoubleArray): ByteArray {
        val n = 257
        val f = 0.5 * Math.sqrt(2.0 / 256)
        val x = frame.copyOf()
        x[0] = Math.rint(x[0] * Math.sqrt(2.0)); x[n - 1] = Math.rint(x[n - 1] * Math.sqrt(2.0))
        val out = ByteArray(80)
        val mag = DoubleArray(n)
        val cosT = DoubleArray(512) { Math.cos(Math.PI * it / 256.0) }
        for (k in 0 until n) {
            var acc = x[0] + (if (k % 2 == 0) x[n - 1] else -x[n - 1])
            for (i in 1 until n - 1) acc += 2 * x[i] * cosT[(k * i) and 511]
            var v = (f * acc).toInt().toDouble()
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
    fun hearsSpeechThroughTheEarbudCodec() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("model download: ${Whisperer.status.value}", Whisperer.downloadBlocking(ctx))

        // A public-domain LibriSpeech sentence about "yellow lamps" lighting up after nightfall.
        val wav = fetch("https://github.com/Jondude1/Anzu-1/releases/download/models-v1/sample.wav")
        val sb = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val clean = ShortArray(sb.remaining()); sb.get(clean)

        val t0 = System.currentTimeMillis()
        val direct = runBlocking { Whisperer.transcribe(ctx, clean) }
        Log.i("ATLASTEST", "speech model, clean audio (${System.currentTimeMillis() - t0} ms): $direct")
        assertNotNull("model failed: ${Whisperer.status.value}", direct)
        assertTrue("clean: $direct", direct!!.lowercase().contains("yellow lamps"))

        // Now at an earbud-like level (peak about 2500 of 32768) through the firmware codec and our decoder.
        val bytes = ctx.assets.open("buddie_pca.bin").use { it.readBytes() }
        val w = FloatArray(47 * 257)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(w)
        val codec = PcaCodec.fromBytes(bytes)
        val peak = clean.maxOf { Math.abs(it.toInt()) }.toDouble()
        val quiet = DoubleArray(clean.size) { Math.rint(clean[it] / peak * 2500) }
        val decoded = ShortArray(quiet.size / 256 * 256)
        var prev = 0.0
        var i = 0
        while (i + 256 <= quiet.size) {
            val frame = DoubleArray(257)
            frame[0] = prev
            System.arraycopy(quiet, i, frame, 1, 256)
            prev = quiet[i + 255]
            System.arraycopy(codec.decodeBlock(firmwareEncode(w, frame), 0), 0, decoded, i, 256)
            i += 256
        }
        val through = runBlocking { Whisperer.transcribe(ctx, decoded) }
        Log.i("ATLASTEST", "speech model, through earbud codec: $through")
        assertNotNull(through)
        val t = through!!.lowercase()
        assertTrue("through codec: $through", t.contains("yellow") && t.contains("lamps") && t.contains("night"))
    }
}
