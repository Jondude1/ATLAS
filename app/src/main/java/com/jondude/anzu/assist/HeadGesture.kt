package com.jondude.anzu.assist

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build

/**
 * Silent Nod Approvals (roadmap #15): approve an approval card with a nod, decline with a shake, using the head-tracking
 * sensor Android exposes for headsets that support it (Sensor.TYPE_HEAD_TRACKER, Android 13+). The Buddie earbuds and the
 * Anzu glasses don't report head motion; Galaxy Buds may, depending on model and phone. When no head tracker is
 * present, approvals work exactly as before (card, or a spoken yes).
 *
 * A gesture needs two clear swings in opposite directions on one axis within 1.6 s, with little motion on the other,
 * so ordinary head movement doesn't approve anything.
 */
object HeadGesture {
    data class Sample(val t: Long, val pitch: Float, val yaw: Float)

    private const val SWING = 0.12f   // radians from the start position (~7°)
    private const val WINDOW = 1600L

    /** "nod", "shake", or null. Angles are relative to the first sample. Pure, so it can be tested. */
    fun detect(samples: List<Sample>): String? {
        if (samples.size < 4) return null
        val end = samples.last().t
        val recent = samples.filter { end - it.t <= WINDOW }
        if (recent.size < 4) return null
        val p0 = recent.first().pitch; val y0 = recent.first().yaw
        fun swings(values: List<Float>): Int {
            var count = 0; var dir = 0
            for (v in values) {
                val d = if (v > SWING) 1 else if (v < -SWING) -1 else 0
                if (d != 0 && d != dir) { count++; dir = d }
            }
            return count
        }
        val pitch = recent.map { it.pitch - p0 }
        val yaw = recent.map { it.yaw - y0 }
        val pRange = pitch.max() - pitch.min()
        val yRange = yaw.max() - yaw.min()
        return when {
            swings(pitch) >= 2 && pRange > 2 * yRange -> "nod"
            swings(yaw) >= 2 && yRange > 2 * pRange -> "shake"
            else -> null
        }
    }

    fun tracker(ctx: Context): Sensor? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER).firstOrNull() ?: sm.getDefaultSensor(Sensor.TYPE_HEAD_TRACKER)
        } catch (_: Exception) {
            null
        }
    }

    /** Listens until a nod or shake (true / false), or until stopped. Returns a stopper. */
    fun listen(ctx: Context, onGesture: (Boolean) -> Unit): (() -> Unit)? {
        val sensor = tracker(ctx) ?: return null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val samples = ArrayDeque<Sample>()
        var done = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (done || e.values.size < 3) return
                // Head tracker: rotation vector in axis-angle form; x ≈ pitch (nod), z ≈ yaw (shake).
                val now = System.currentTimeMillis()
                samples.addLast(Sample(now, e.values[0], e.values[2]))
                while (samples.isNotEmpty() && now - samples.first().t > WINDOW) samples.removeFirst()
                when (detect(samples.toList())) {
                    "nod" -> { done = true; onGesture(true) }
                    "shake" -> { done = true; onGesture(false) }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        return { done = true; try { sm.unregisterListener(l) } catch (_: Exception) {} }
    }
}
