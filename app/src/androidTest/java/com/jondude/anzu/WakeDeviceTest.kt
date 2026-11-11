package com.jondude.anzu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The bundled wake-word model loads on a real Android system, hears "Hey Atlas", and stays quiet otherwise. */
@RunWith(AndroidJUnit4::class)
class WakeDeviceTest {
    private fun pcm(name: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(sb.remaining()).also { sb.get(it) }
    }

    @Test
    fun hearsAtlasAndNothingElse() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val t0 = System.currentTimeMillis()
        assertTrue("didn't hear 'Hey Atlas'", WakeListener.spots(app, pcm("wake_atlas.pcm")))
        val loaded = System.currentTimeMillis() - t0
        assertFalse("false wake on ordinary speech", WakeListener.spots(app, pcm("no_wake.pcm")))
        // Real-time factor: ~4 s of audio should take well under 4 s to scan.
        val t1 = System.currentTimeMillis()
        WakeListener.spots(app, pcm("no_wake.pcm"))
        val scan = System.currentTimeMillis() - t1
        android.util.Log.i("ATLASTEST", "wake model: first run $loaded ms (includes load), 4.7 s of audio scanned in $scan ms")
        assertTrue("too slow: $scan ms", scan < 4000)
    }
}
