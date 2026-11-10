package com.jondude.anzu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Walking (step counter) and driving (speed) awareness, plus the latest location fix. */
object Motion : SensorEventListener {
    @Volatile
    var last: Location? = null
    private val steps = ArrayDeque<Pair<Long, Float>>()
    private var running = false
    private var sm: SensorManager? = null
    private var lm: LocationManager? = null
    private val locListener = LocationListener { loc ->
        last = loc
        Prefs.sp.edit().putString("wxLat", loc.latitude.toString()).putString("wxLon", loc.longitude.toString()).apply()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            if (app.has(Manifest.permission.ACTIVITY_RECOGNITION)) {
                val m = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                sm = m
                m.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                    m.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (app.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm = m
                val provider = when {
                    m.allProviders.contains("fused") -> "fused"
                    m.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider != null) m.requestLocationUpdates(provider, 60_000L, 30f, locListener, Looper.getMainLooper())
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            sm?.unregisterListener(this)
        } catch (_: Exception) {
        }
        try {
            lm?.removeUpdates(locListener)
        } catch (_: Exception) {
        }
        synchronized(steps) { steps.clear() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        synchronized(steps) {
            steps.addLast(now to event.values[0])
            while (steps.size > 2 && now - steps.first().first > 5 * 60_000) steps.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** More than ~150 steps in the last three minutes, and still moving. */
    fun walking(): Boolean = synchronized(steps) {
        val now = System.currentTimeMillis()
        val recent = steps.filter { now - it.first < 3 * 60_000 }
        recent.size >= 2 && recent.last().second - recent.first().second > 150 && now - recent.last().first < 60_000
    }

    /** A recent fix faster than about 20 mph. */
    fun driving(): Boolean {
        val l = last ?: return false
        return System.currentTimeMillis() - l.time < 3 * 60_000 && l.hasSpeed() && l.speed > 9f
    }

    fun fresh(maxAgeMs: Long): Location? = last?.takeIf { System.currentTimeMillis() - it.time < maxAgeMs }
}

/** Named places and "remind me when I'm at X". */
object Places {
    private fun places(): JSONObject = try {
        JSONObject(Prefs.sp.getString("places", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun reminders(): JSONArray = try {
        JSONArray(Prefs.sp.getString("placeReminders", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun names(): List<String> = places().keys().asSequence().toList()

    @Synchronized
    fun save(name: String, loc: Location) {
        val p = places().put(name.trim().lowercase(), JSONObject().put("lat", loc.latitude).put("lon", loc.longitude))
        Prefs.sp.edit().putString("places", p.toString()).apply()
    }

    fun known(name: String): Boolean = places().has(name.trim().lowercase())

    @Synchronized
    fun addReminder(place: String, text: String) {
        val r = reminders().put(JSONObject().put("place", place.trim().lowercase()).put("text", text).put("armed", false))
        Prefs.sp.edit().putString("placeReminders", r.toString()).apply()
    }

    fun pendingCount(): Int = reminders().length()

    /** Returns reminders that just came due at this location, and removes them. */
    @Synchronized
    fun check(loc: Location): List<String> {
        val all = reminders()
        if (all.length() == 0) return emptyList()
        val p = places()
        val keep = JSONArray()
        val due = ArrayList<String>()
        val d = FloatArray(1)
        for (i in 0 until all.length()) {
            val r = all.getJSONObject(i)
            val place = p.optJSONObject(r.optString("place"))
            if (place == null) {
                keep.put(r)
                continue
            }
            Location.distanceBetween(loc.latitude, loc.longitude, place.optDouble("lat"), place.optDouble("lon"), d)
            // Only fire on arrival: the reminder arms once he's been away from the place.
            if (d[0] > 400f) r.put("armed", true)
            if (d[0] < 180f && r.optBoolean("armed")) due.add("You're at ${r.optString("place")}. ${r.optString("text")}")
            else keep.put(r)
        }
        Prefs.sp.edit().putString("placeReminders", keep.toString()).apply()
        return due
    }
}
