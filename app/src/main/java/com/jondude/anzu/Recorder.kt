package com.jondude.anzu

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One finished segment of a session. */
data class Seg(val file: String, val startedEpochMs: Long, val durationMs: Long)

/** What session.json holds. [stopEpochMs] is null until the session is finalised. */
data class SessionMeta(
    val label: String,
    val startEpochMs: Long,
    val stopEpochMs: Long? = null,
    val segments: List<Seg> = emptyList()
)

/** What the UI shows about a session, derived from what is actually on disk. */
data class SessionSummary(
    val sessionId: Long,
    val label: String,
    val startEpochMs: Long,
    val durationMs: Long,
    val segmentCount: Int,
    val totalBytes: Long,
    /** True when session.json was never finalised: the phone died or the service was killed. */
    val interrupted: Boolean
)

sealed interface RecorderState {
    object Idle : RecorderState
    data class Recording(
        val sinceMs: Long, val segmentCount: Int, val label: String = "", val warning: String? = null
    ) : RecorderState
    data class Error(val message: String) : RecorderState
    data class Done(val summary: SessionSummary) : RecorderState
}

/** Pure helpers for the recorder: naming, session.json, display formatting. */
object RecorderMath {
    const val SEGMENT_MS = 600_000

    /** seg-001.m4a, seg-002.m4a, … (index is 1-based). */
    fun segmentName(index: Int): String = "seg-%03d.m4a".format(index)

    fun toJson(m: SessionMeta): String {
        val o = JSONObject().put("label", m.label).put("startEpochMs", m.startEpochMs)
        if (m.stopEpochMs != null) o.put("stopEpochMs", m.stopEpochMs)
        val arr = JSONArray()
        for (s in m.segments) {
            arr.put(JSONObject().put("file", s.file).put("startedEpochMs", s.startedEpochMs).put("durationMs", s.durationMs))
        }
        o.put("segments", arr)
        return o.toString(2)
    }

    fun fromJson(text: String): SessionMeta {
        val o = JSONObject(text)
        val arr = o.optJSONArray("segments") ?: JSONArray()
        val segs = (0 until arr.length()).map {
            val s = arr.getJSONObject(it)
            Seg(s.getString("file"), s.getLong("startedEpochMs"), s.getLong("durationMs"))
        }
        return SessionMeta(
            o.getString("label"), o.getLong("startEpochMs"),
            if (o.has("stopEpochMs")) o.getLong("stopEpochMs") else null, segs
        )
    }

    /** mm:ss, or h:mm:ss from one hour up. */
    fun formatElapsed(ms: Long): String {
        val t = (ms.coerceAtLeast(0) / 1000)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
        else -> "$bytes B"
    }
}

/** The recorder's public face: observable state, and start / stop / dismiss / share. */
object Recorder {
    /** Needed to start at all (about 40 MB an hour); below the floor mid-session it stops loudly. */
    private const val MIN_FREE_START = 200L * 1024 * 1024
    internal const val MIN_FREE_RUNNING = 30L * 1024 * 1024
    internal const val FAIL_NOTIFICATION_ID = 4412

    private val flow = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: kotlinx.coroutines.flow.StateFlow<RecorderState> get() = flow
    internal fun set(s: RecorderState) { flow.value = s }

    fun recordingsRoot(ctx: Context) = File(ctx.filesDir, "recordings")

    /** Checks permission and storage first, so a problem shows at Start, not an hour in. */
    fun start(ctx: Context, label: String) {
        val app = ctx.applicationContext
        if (flow.value is RecorderState.Recording) return
        if (!app.has(Manifest.permission.RECORD_AUDIO)) {
            flow.value = RecorderState.Error("Microphone permission is off — recording can't start")
            return
        }
        val free = try { StatFs(app.filesDir.absolutePath).availableBytes } catch (e: Exception) { 0L }
        if (free < MIN_FREE_START) {
            flow.value = RecorderState.Error(
                "Not enough free storage to record (${RecorderMath.formatSize(free)} free, need ${RecorderMath.formatSize(MIN_FREE_START)})"
            )
            return
        }
        try {
            val i = Intent(app, RecorderService::class.java).setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_LABEL, label.trim().ifBlank { "Session" })
            androidx.core.content.ContextCompat.startForegroundService(app, i)
        } catch (e: Exception) {
            flow.value = RecorderState.Error("Couldn't start the recorder: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        if (flow.value !is RecorderState.Recording) return
        app.startService(Intent(app, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
    }

    /** Clears an Error (and its failure notification). Never touches a live recording. */
    fun dismiss(ctx: Context) {
        if (flow.value is RecorderState.Error) {
            flow.value = RecorderState.Idle
            ctx.getSystemService(NotificationManager::class.java).cancel(FAIL_NOTIFICATION_ID)
        }
    }

    /** The newest session on disk (finished, failed or interrupted), or null. */
    fun lastSession(ctx: Context): SessionSummary? {
        val dirs = recordingsRoot(ctx).listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null } ?: return null
        return dirs.sortedByDescending { it.name.toLong() }.firstNotNullOfOrNull { summarize(it) }
    }

    fun summarize(dir: File): SessionSummary? {
        val meta = try { RecorderMath.fromJson(File(dir, "session.json").readText()) } catch (e: Exception) { return null }
        val files = audioFiles(dir)
        val bytes = files.sumOf { it.length() } + File(dir, "session.json").length()
        return SessionSummary(
            dir.name.toLong(), meta.label, meta.startEpochMs, meta.segments.sumOf { it.durationMs },
            files.size, bytes, meta.stopEpochMs == null
        )
    }

    private fun audioFiles(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a") && f.length() > 0 } ?: emptyArray()).sortedBy { it.name }

    /** Opens the share sheet with every segment plus session.json. Returns an error message, or null. */
    fun share(ctx: Context, s: SessionSummary): String? {
        return try {
            val dir = File(recordingsRoot(ctx), s.sessionId.toString())
            val files = audioFiles(dir) + File(dir, "session.json")
            val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", it) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .putExtra(Intent.EXTRA_SUBJECT, s.label)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(s.label, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            ctx.startActivity(Intent.createChooser(send, "Share session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) {
            "Couldn't open the share sheet: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/**
 * Records the phone mic in ten-minute AAC segments. The notification lives exactly as long as the
 * service, so if the service dies the notification dies with it.
 */
class RecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var rec: MediaRecorder? = null
    private var dir: File? = null
    private var label = ""
    private var startEpochMs = 0L
    private val segs = ArrayList<Seg>()
    private var segIndex = 0
    private var segStartWall = 0L
    private var segStartElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var active = false
    private var quickFailures = 0
    private var silentPolls = 0
    private var warning: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                val free = StatFs(filesDir.absolutePath).availableBytes
                if (free < Recorder.MIN_FREE_RUNNING) {
                    fail("Storage is full (${RecorderMath.formatSize(free)} left)")
                    return
                }
                // A mic that is silenced (another app holding it) records dead air without any error.
                val amp = try { rec?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
                silentPolls = if (amp == 0) silentPolls + 1 else 0
                warning = if (silentPolls >= 4) "No sound picked up for ${silentPolls / 2} min: the mic may be blocked" else null
                publish()
            } catch (e: Exception) {
                fail("Recorder check failed: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!active) begin(intent.getStringExtra(EXTRA_LABEL) ?: "Session")
            ACTION_STOP -> if (active) stopRecording()
        }
        if (!active) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Killed or torn down while still recording: say so instead of leaving a fake "Recording".
        if (active) fail("The recorder was stopped by Android", stopService = false)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun begin(requestedLabel: String) {
        label = requestedLabel
        startEpochMs = System.currentTimeMillis()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Session recorder", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(FAIL_CHANNEL, "Recorder problems", NotificationManager.IMPORTANCE_HIGH))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            failBeforeStart("Couldn't start the recorder service: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        try {
            val d = File(Recorder.recordingsRoot(this), startEpochMs.toString())
            if (!d.mkdirs() && !d.isDirectory) throw IOException("couldn't create ${d.path}")
            dir = d
            writeMeta(stop = null)
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atlas:recorder")
                .apply { setReferenceCounted(false); acquire(12 * 60 * 60_000L) }
            active = true
            startSegment()
        } catch (e: Exception) {
            if (active) fail("Couldn't start recording: ${e.message ?: e.javaClass.simpleName}")
            else failBeforeStart("Couldn't start recording: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        publish()
        main.postDelayed(tick, 30_000)
    }

    private fun startSegment() {
        segIndex++
        val file = File(dir!!, RecorderMath.segmentName(segIndex))
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(RecorderMath.SEGMENT_MS)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (active && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) rotate()
            }
            r.setOnErrorListener { _, what, extra -> if (active) onSegmentError(what, extra) }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            if (file.exists() && file.length() == 0L) file.delete()
            throw e
        }
        rec = r
        segStartWall = System.currentTimeMillis()
        segStartElapsed = SystemClock.elapsedRealtime()
        silentPolls = 0
    }

    /** Stops and releases the current segment, keeping it if it holds data. */
    private fun finishSegment() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (_: Exception) { /* too short or already broken: keep whatever bytes exist */ }
        try { r.release() } catch (_: Exception) {}
        val file = File(dir!!, RecorderMath.segmentName(segIndex))
        if (file.exists() && file.length() > 0) {
            segs.add(Seg(file.name, segStartWall, SystemClock.elapsedRealtime() - segStartElapsed))
        } else {
            file.delete()
        }
    }

    private fun rotate() {
        finishSegment()
        try {
            writeMeta(stop = null) // a dead battery still leaves a coherent bundle
            startSegment()
        } catch (e: Exception) {
            fail("Couldn't start the next segment: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        quickFailures = 0
        publish()
    }

    private fun onSegmentError(what: Int, extra: Int) {
        val lasted = SystemClock.elapsedRealtime() - segStartElapsed
        quickFailures = if (lasted < 5_000) quickFailures + 1 else 0
        if (quickFailures >= 3) {
            fail("The microphone recorder keeps failing (error $what/$extra)")
            return
        }
        rotate()
    }

    private fun stopRecording() {
        active = false
        main.removeCallbacks(tick)
        finishSegment()
        try {
            writeMeta(stop = System.currentTimeMillis())
        } catch (e: Exception) {
            releaseWakeLock()
            Recorder.set(RecorderState.Error("Stopped, but couldn't write session.json: ${e.message ?: e.javaClass.simpleName}. ${segs.size} segments are saved."))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        releaseWakeLock()
        val summary = Recorder.summarize(dir!!)
        Recorder.set(if (summary != null) RecorderState.Done(summary) else RecorderState.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Anything went wrong mid-session: keep what was recorded, say so loudly, stop pretending. */
    private fun fail(message: String, stopService: Boolean = true) {
        active = false
        main.removeCallbacks(tick)
        try { finishSegment() } catch (_: Exception) {}
        try { if (dir != null) writeMeta(stop = System.currentTimeMillis()) } catch (_: Exception) {}
        releaseWakeLock()
        val kept = segs.size
        val full = message + if (kept > 0) ". $kept segment${if (kept == 1) "" else "s"} saved." else ". Nothing was saved."
        Recorder.set(RecorderState.Error(full))
        postFailure(full)
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failBeforeStart(message: String) {
        Recorder.set(RecorderState.Error(message))
        postFailure(message)
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun writeMeta(stop: Long?) {
        val d = dir ?: return
        val text = RecorderMath.toJson(SessionMeta(label, startEpochMs, stop, segs.toList()))
        val tmp = File(d, "session.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(d, "session.json"))) throw IOException("couldn't replace session.json")
    }

    private fun publish() {
        Recorder.set(RecorderState.Recording(startEpochMs, segIndex, label, warning))
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) } catch (_: Exception) {}
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val elapsed = RecorderMath.formatElapsed(System.currentTimeMillis() - startEpochMs)
        val text = "$elapsed · segment ${maxOf(segIndex, 1)}" + (warning?.let { " · $it" } ?: "")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording — $label")
            .setContentText(text)
            .setWhen(startEpochMs).setShowWhen(true).setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun postFailure(message: String) {
        try {
            val n = NotificationCompat.Builder(this, FAIL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Recording stopped — $label")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openApp())
                .setAutoCancel(false)
                .build()
            getSystemService(NotificationManager::class.java).notify(Recorder.FAIL_NOTIFICATION_ID, n)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_START = "com.jondude.anzu.RECORD_START"
        const val ACTION_STOP = "com.jondude.anzu.RECORD_STOP"
        const val EXTRA_LABEL = "label"
        private const val CHANNEL = "recorder"
        private const val FAIL_CHANNEL = "recorder-problems"
        private const val NOTIFICATION_ID = 4411
    }
}
