package com.jondude.anzu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.CalendarContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jondude.anzu.assist.Brain
import com.jondude.anzu.assist.Atlas
import com.jondude.anzu.assist.HomeAssistant
import com.jondude.anzu.assist.HomeWatch
import com.jondude.anzu.assist.Obelisk
import com.jondude.anzu.assist.Timers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * The always-on layer. Watches for the glasses, and while they're on:
 * Atlas speaks up on its own, reads timers and calendar heads-ups, and tracks battery.
 */
class GlassesService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null
    private var startedForeground = false
    private var nextSpeakAt = 0L
    private var lastEyeBreak = 0L
    private var warned20 = false
    private var warned10 = false
    private val announcedEvents = HashSet<String>()
    private var ticks = 0
    private var wasWalking = false
    private var lastWalkTalk = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Keep the CPU awake while the glasses are on (or a timer is pending), so Atlas
     * still speaks up with the phone asleep in a pocket.
     */
    @Synchronized
    private fun updateWakeLock() {
        val want = Glasses.connected.value || Timers.pending()
        try {
            if (want) {
                if (wakeLock == null) {
                    wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "corin:presence")
                        .apply { setReferenceCounted(false) }
                }
                if (wakeLock?.isHeld != true) wakeLock?.acquire(12 * 60 * 60_000L)
            } else if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice? = androidx.core.content.IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            // The Buddie data link is Bluetooth LE and manages itself; only audio (classic) links count as "worn".
            val le = intent.getIntExtra("android.bluetooth.device.extra.TRANSPORT", 0) == BluetoothDevice.TRANSPORT_LE
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (device != null && !le) wearableOn(device)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (device != null && !le) wearableOff(device)
                ACTION_BATTERY -> if (device != null && kind(device) != null) {
                    val level = intent.getIntExtra(EXTRA_BATTERY, -1)
                    if (level >= 0) Glasses.battery.value = level
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Atlas presence", NotificationManager.IMPORTANCE_LOW)
        )
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        checkAlreadyConnected()
        scope.launch(Dispatchers.IO) {
            try {
                com.jondude.anzu.assist.Mcp.refresh()
            } catch (_: Exception) {
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(30_000)
                try {
                    tick()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true
        if (!startedForeground || fromUi) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            // Mic and location can only be attached while the app is visible; that "arms"
            // the conversation window so Atlas can listen after speaking up on its own.
            val mic = fromUi && has(Manifest.permission.RECORD_AUDIO)
            if (mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (fromUi && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            try {
                ServiceCompat.startForeground(this, 1, buildNotification(), types)
                startedForeground = true
                if (fromUi) Glasses.micArmed.value = mic
            } catch (e: Exception) {
                try {
                    ServiceCompat.startForeground(
                        this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                    startedForeground = true
                } catch (_: Exception) {
                }
                Glasses.micArmed.value = false
            }
        }
        if (intent?.action == ACTION_SPEAK_NOW) scope.launch { speakUp("random") }
        if (intent?.action == ACTION_QUIET) {
            val going = Prefs.quietUntil <= System.currentTimeMillis()
            Prefs.quietUntil = if (going) System.currentTimeMillis() + 60 * 60_000 else 0L
            refreshNotification()
            // Confirm by ear, so it works without looking.
            if (Glasses.connected.value) {
                Earcons.play(if (going) "done" else "listen")
                Atlas.announce(if (going) "Going quiet for an hour." else "I'm back.")
            }
        }
        if (fromUi && Glasses.connected.value && Motion.last == null) {
            // Location only flows once the service has been armed from the visible app.
            Motion.stop()
            Motion.start(this)
        }
        updateWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        scope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        Glasses.micArmed.value = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val quiet = Prefs.quietUntil > System.currentTimeMillis()
        val text = when {
            !Glasses.connected.value -> "Waiting for the glasses or earbuds"
            quiet -> "With you, staying quiet"
            Wear.onPixelBuds() -> "With you in the Pixel Buds"
            Wear.onBuds() -> "With you in the earbuds"
            else -> "With you in the glasses"
        }
        val talk = PendingIntent.getActivity(
            this, 1,
            Intent(this, com.jondude.anzu.assist.AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val quietPi = PendingIntent.getService(
            this, 2, Intent(this, GlassesService::class.java).setAction(ACTION_QUIET), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(Prefs.name)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, "Talk", talk)
            .addAction(0, if (quiet) "Resume" else "Quiet 1h", quietPi)
            .build()
    }

    private fun refreshNotification() {
        if (!startedForeground) return
        try {
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        } catch (_: Exception) {
        }
    }

    /** Addresses of the wearables connected right now, mapped to what they are. */
    private val worn = LinkedHashMap<String, String>()

    /** "glasses", "buddie", or null for any other Bluetooth device. */
    @SuppressLint("MissingPermission")
    private fun kind(device: BluetoothDevice): String? {
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return null
        val name = try {
            device.name ?: ""
        } catch (e: Exception) {
            ""
        }
        return wearableKind(name, device.address, Prefs.deviceAddress)?.also {
            if (it == "glasses" && Prefs.deviceAddress.isBlank()) {
                Prefs.deviceAddress = device.address
                Prefs.deviceName = name
            }
        }
    }

    private fun updateWearing() {
        val kinds = synchronized(worn) { worn.values.toSet() }
        // The most recently connected wearable is the one audio goes to.
        val nowActive = synchronized(worn) { worn.values.lastOrNull() ?: "" }
        val before = Glasses.active.value
        Glasses.active.value = nowActive
        if (before.isNotBlank() && nowActive.isNotBlank() && before != nowActive) {
            scheduleNext()
            refreshNotification()
            if (Prefs.announceSwitch) scope.launch {
                delay(3_000) // let audio move to the new device
                Atlas.announce(Wear.switchLine())
            }
        }
        Glasses.wearing.value = listOfNotNull(
            "glasses".takeIf { it in kinds }, "Buddie earbuds".takeIf { "buddie" in kinds },
            "Pixel Buds".takeIf { "pixelbuds" in kinds }, "Galaxy Buds".takeIf { "galaxybuds" in kinds }
        ).joinToString(" and ")
    }

    private fun wearableOn(device: BluetoothDevice, quiet: Boolean = false) {
        val k = kind(device) ?: return
        val first = synchronized(worn) {
            val wasEmpty = worn.isEmpty()
            worn.remove(device.address) // re-insert so the newest connection is last
            worn[device.address] = k
            wasEmpty
        }
        updateWearing()
        if (k == "buddie") BuddieLink.start(this)
        if (first) onGlassesOn(quiet)
    }

    private fun wearableOff(device: BluetoothDevice) {
        val (k, empty) = synchronized(worn) {
            val removed = worn.remove(device.address)
            removed to worn.isEmpty()
        }
        if (k == null) return
        updateWearing()
        if (k == "buddie") BuddieLink.stop()
        if (empty) onGlassesOff()
    }

    @SuppressLint("MissingPermission")
    private fun checkAlreadyConnected() {
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices.forEach { wearableOn(it, quiet = true) }
                    } catch (_: Exception) {
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBattery() {
        val address = synchronized(worn) { worn.keys.lastOrNull() } ?: return
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val dev = adapter.getRemoteDevice(address)
            val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(dev) as? Int ?: -1
            if (level >= 0) Glasses.battery.value = level
        } catch (_: Throwable) {
        }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun canListen(): Boolean =
        Glasses.micArmed.value && Prefs.listenMode == 2 && has(Manifest.permission.RECORD_AUDIO) &&
            !Wear.repliesViaStream() // on the buds, replies arrive through their own stream

    private fun onGlassesOn(quiet: Boolean = false) {
        if (Glasses.connected.value) return
        Glasses.connected.value = true
        Glasses.connectedSince.value = System.currentTimeMillis()
        warned20 = false
        warned10 = false
        lastEyeBreak = System.currentTimeMillis()
        scheduleNext()
        refreshNotification()
        updateWakeLock()
        Motion.start(this)
        Obelisk.presence(if (Wear.onBuds()) "earbuds" else "glasses")
        scope.launch {
            delay(5_000) // let the audio route settle
            readBattery()
            if (!Glasses.connected.value) return@launch
            if (Prefs.identity.isBlank() && Brain.hasKey()) {
                val first = Brain.awaken()
                if (first != null) {
                    Prefs.lastGreetDay = today()
                    Atlas.converse(this@GlassesService, first, canListen())
                    return@launch
                }
            }
            if (quiet) return@launch
            if (Prefs.lastGreetDay != today()) {
                Prefs.lastGreetDay = today()
                val hello = Brain.speakUp(this@GlassesService, "greeting") ?: "I'm here."
                Atlas.converse(this@GlassesService, hello, canListen())
            } else {
                val b = Glasses.battery.value
                Atlas.announce(if (b in 0..25) "I'm here. Glasses are at $b percent." else "I'm here.")
            }
        }
    }

    private fun onGlassesOff() {
        if (!Glasses.connected.value) return
        Glasses.connected.value = false
        Glasses.connectedSince.value = 0
        Glasses.battery.value = -1
        Prefs.lastSeenTime = System.currentTimeMillis()
        saveLocation()
        refreshNotification()
        updateWakeLock()
        Motion.stop()
        BuddieLink.stop()
        Obelisk.presence("phone")
    }

    @SuppressLint("MissingPermission")
    private fun saveLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        Motion.fresh(5 * 60_000)?.let {
            Prefs.lastSeenLat = it.latitude.toString()
            Prefs.lastSeenLon = it.longitude.toString()
            return
        }
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l: Location? = try {
                    lm.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            if (best != null) {
                Prefs.lastSeenLat = best.latitude.toString()
                Prefs.lastSeenLon = best.longitude.toString()
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleNext() {
        val mean = when (Wear.chattiness()) {
            0 -> 120
            2 -> 20
            else -> 50
        }
        val minutes = mean * (0.5 + Random.nextDouble())
        nextSpeakAt = System.currentTimeMillis() + (minutes * 60_000).toLong()
    }

    private fun interruptible(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
        ) return false
        if (!Prefs.talkOverMedia && am.isMusicActive) return false
        return !Atlas.busy
    }

    private var lastBriefSeen = 0L
    private var lastNotice = 0L

    /** 23:00 to 07:00: nothing but urgent makes a sound. */
    private fun quietHours(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 23 || it < 7 }

    private fun inActiveHours(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = Prefs.activeStart
        val e = Prefs.activeEnd
        return if (s <= e) h in s until e else (h >= s || h < e)
    }

    private suspend fun speakUp(kind: String) {
        val line = Brain.speakUp(this, kind) ?: return
        Atlas.converse(this, line, canListen())
    }

    private suspend fun tick() {
        // Timers fire whether or not the glasses are on.
        Timers.due().forEach {
            Atlas.announce("Timer's up: ${it.label}.")
        }
        updateWakeLock()
        Atlas.flushPolite()
        // The hub, by ear. Urgent earns speech (two clear notes first), but never over a call: it waits, unacknowledged,
        // until the call ends. Anything else new earns one soft bell and a line in the next brief, outside quiet time.
        if (com.jondude.anzu.assist.Hub.configured() && ticks % 4 == 0) {
            val b = kotlinx.coroutines.withContext(Dispatchers.IO) { com.jondude.anzu.assist.Hub.brief(0) }
            if (b != null) {
                AtlasWidget.push(this, b.text, b.urgent.map { "${it.app}: ${it.title}" })
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    am.mode == AudioManager.MODE_RINGTONE
                val now = System.currentTimeMillis()
                if (b.urgent.isNotEmpty() && !inCall) {
                    if (Glasses.connected.value && now >= Prefs.quietUntil) {
                        Earcons.play("urgent")
                        kotlinx.coroutines.delay(500)
                        b.urgent.take(3).forEach { Atlas.announce("${it.app}: ${it.title}.") }
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.jondude.anzu.assist.Hub.ack(b.urgent.take(3).map { it.id }) }
                    } else b.urgent.forEach { Glasses.hold("${it.app}: ${it.title}") }
                }
                if (b.updated > lastBriefSeen) {
                    val fresh = lastBriefSeen > 0
                    lastBriefSeen = b.updated
                    if (fresh && b.urgent.isEmpty() && Glasses.connected.value && !inCall && now >= Prefs.quietUntil &&
                        !quietHours() && now - lastNotice > 10 * 60_000
                    ) {
                        lastNotice = now
                        Earcons.play("notice")
                    }
                }
            }
        }
        if (!Glasses.connected.value) {
            ticks++
            return
        }
        // Either earbud can be worn alone; if the audio stream has gone quiet, look for the other one.
        if (Prefs.buddieListen && synchronized(worn) { worn.containsValue("buddie") }) BuddieLink.nudge(this)
        val now = System.currentTimeMillis()
        readBattery()
        val b = Glasses.battery.value
        if (b in 0..10 && !warned10) {
            warned10 = true
            Atlas.announce("Glasses are at $b percent. I'll drop out soon.")
        } else if (b in 11..20 && !warned20) {
            warned20 = true
            Atlas.polite("Heads up, glasses battery is at $b percent.", source = "battery")
        }
        if (Prefs.calendarHeadsUp) calendarHeadsUp(now)
        Motion.fresh(3 * 60_000)?.let { loc -> Places.check(loc).forEach { Atlas.polite(it, source = "places") } }
        ticks++
        if (Glasses.meeting.value) return // stay out of the way while taking notes
        if (Prefs.commuteAlerts && ticks % 10 == 0) commuteCheck(now)
        val quietNow = System.currentTimeMillis() < Prefs.quietUntil
        // House check every three minutes: only things that need him.
        if (Prefs.homeAlerts && HomeAssistant.configured() && ticks % 6 == 0) {
            val lines = kotlinx.coroutines.withContext(Dispatchers.IO) { HomeWatch.poll() }
            if (!quietNow) lines.forEach { Atlas.polite(it, source = "house") }
        }
        if (quietNow) return
        val driving = Prefs.activityAware && Motion.driving()
        val walking = Prefs.activityAware && Motion.walking()
        if (driving) return // no unprompted chatter behind the wheel
        if (walking && !wasWalking && Prefs.proactive && inActiveHours() && interruptible() &&
            now - lastWalkTalk > 45 * 60_000
        ) {
            wasWalking = true
            lastWalkTalk = now
            scheduleNext()
            speakUp("walk")
            return
        }
        wasWalking = walking
        if (Prefs.eyeBreaks && Wear.eyeBreaks() && now - lastEyeBreak > 20 * 60_000 && interruptible() && !Atlas.musicPlaying()) {
            lastEyeBreak = now
            Atlas.announce("Twenty minutes. Look at something far away for twenty seconds.")
            return
        }
        if (!inActiveHours() || !interruptible()) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Prefs.journal && hour >= Prefs.journalHour && Prefs.lastJournalDay != today()) {
            Prefs.lastJournalDay = today()
            speakUp("journal")
            Brain.closeDay()
            scheduleNext()
            return
        }
        if (Prefs.proactive && now >= nextSpeakAt) {
            scheduleNext()
            speakUp("random")
        }
    }

    private val commuteTold = HashSet<String>()
    private val geocoded = HashMap<String, Pair<Double, Double>?>()

    /** "Leave now" alerts for calendar events that have a location, using the current drive time. */
    private suspend fun commuteCheck(now: Long) {
        if (!has(Manifest.permission.READ_CALENDAR)) return
        val here = Motion.fresh(10 * 60_000) ?: return
        val lines = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val out = ArrayList<String>()
            try {
                val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(b, now)
                ContentUris.appendId(b, now + 3 * 60 * 60_000)
                contentResolver.query(
                    b.build(),
                    arrayOf(
                        CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                        CalendarContract.Instances.EVENT_LOCATION
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val begin = c.getLong(2)
                        val where = c.getString(4)?.trim().orEmpty()
                        val key = "${c.getLong(0)}-$begin"
                        if (c.getInt(3) != 0 || begin <= now || where.isEmpty() || key in commuteTold) continue
                        val dest = geocoded.getOrPut(where) {
                            try {
                                android.location.Geocoder(this@GlassesService).getFromLocationName(where, 1)
                                    ?.firstOrNull()?.let { it.latitude to it.longitude }
                            } catch (e: Exception) {
                                null
                            }
                        } ?: continue
                        val d = FloatArray(1)
                        Location.distanceBetween(here.latitude, here.longitude, dest.first, dest.second, d)
                        if (d[0] < 400f) continue // already there
                        val secs = com.jondude.anzu.assist.Routing.driveSeconds(
                            here.latitude, here.longitude, dest.first, dest.second
                        ) ?: continue
                        val line = com.jondude.anzu.assist.Routing.leaveLine(c.getString(1) ?: "your event", begin, secs, now)
                        if (line != null) {
                            commuteTold.add(key)
                            out.add(line)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            out
        }
        lines.forEach { Atlas.announce(it) }
    }

    private fun calendarHeadsUp(now: Long) {
        if (!has(Manifest.permission.READ_CALENDAR)) return
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now)
            ContentUris.appendId(b, now + 11 * 60_000)
            contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(2)
                    val key = "${c.getLong(0)}-$begin"
                    if (c.getInt(3) != 0 || begin <= now || key in announcedEvents) continue
                    announcedEvents.add(key)
                    val mins = ((begin - now) / 60_000 + 1).toInt()
                    Atlas.polite("Heads up: ${c.getString(1) ?: "an event"} in $mins minutes.", 3 * 60_000L, "calendar")
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL = "corin"
        const val EXTRA_FROM_UI = "fromUi"
        const val ACTION_SPEAK_NOW = "com.jondude.anzu.SPEAK_NOW"
        const val ACTION_POKE = "com.jondude.anzu.POKE"
        const val ACTION_QUIET = "com.jondude.anzu.QUIET"
        const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

        fun start(ctx: Context, fromUi: Boolean, action: String? = null) {
            if (!ctx.has(Manifest.permission.BLUETOOTH_CONNECT)) return
            val i = Intent(ctx, GlassesService::class.java).putExtra(EXTRA_FROM_UI, fromUi)
            if (action != null) i.action = action
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GlassesService.start(context, false)
    }
}

/** Notification whisper with triage: people and time-sensitive things now, the rest held for a digest. */
class NotifyListener : NotificationListenerService() {
    private var lastKey = ""
    private var lastAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (!Prefs.readNotifications) return
            if (sbn.packageName == packageName || sbn.isOngoing) return
            if (sbn.packageName !in Prefs.allowedApps) return
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val key = "${sbn.packageName}|$title|$text"
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastAt < 15_000) return
            lastKey = key
            lastAt = now

            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                "An app"
            }
            val line = "$app: $title. ${text.take(160)}"
            Glasses.noteNotification(line)

            val urgent = n.category in setOf(
                Notification.CATEGORY_MESSAGE, Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM,
                Notification.CATEGORY_REMINDER, Notification.CATEGORY_EVENT
            ) || n.extras.containsKey(Notification.EXTRA_MESSAGES)
            if (Prefs.triage && !urgent) {
                Glasses.hold(line)
                return
            }
            if (!Glasses.connected.value || Glasses.meeting.value) {
                Glasses.hold(line)
                return
            }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                Glasses.hold(line)
                return
            }
            if (System.currentTimeMillis() < Prefs.quietUntil) {
                Glasses.hold(line)
                return
            }
            Atlas.announce(if (Wear.senderOnly()) "$app, from $title." else "$app. $title. ${text.take(220)}")
        } catch (_: Exception) {
        }
    }
}

/** Classifies a Bluetooth device by name and address: "glasses", "buddie", "pixelbuds", or null. */
fun wearableKind(name: String, address: String, savedGlassesAddress: String): String? = when {
    savedGlassesAddress.isNotBlank() && address == savedGlassesAddress -> "glasses"
    name.contains("buddie", ignoreCase = true) -> "buddie"
    name.contains("galaxy buds", ignoreCase = true) || (name.contains("buds", ignoreCase = true) && !name.contains("pixel", ignoreCase = true)) -> "galaxybuds"
    savedGlassesAddress.isBlank() && name.contains("anzu", ignoreCase = true) -> "glasses"
    else -> null
}
