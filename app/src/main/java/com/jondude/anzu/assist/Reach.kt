package com.jondude.anzu.assist

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.documentfile.provider.DocumentFile
import com.jondude.anzu.Prefs
import com.jondude.anzu.has
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Things Atlas can do on the phone itself. Every function returns a short line for the model. */
object Phone {
    /** Starting another app from the background needs the "display over other apps" permission. */
    private fun canLaunch(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
    private const val NEED_LAUNCH = "I need the 'display over other apps' permission for that; it's on the Setup card."

    private fun launch(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** Resolves a spoken name (or a typed number) to (display name, number). */
    fun findContact(ctx: Context, who: String): Pair<String, String>? {
        val q = who.trim()
        if (q.count { it.isDigit() } >= 7 && q.all { it.isDigit() || it in "+-() " }) return q to q
        if (!ctx.has(Manifest.permission.READ_CONTACTS)) return null
        return try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.STARRED + " DESC"
            )?.use { c ->
                var best: Pair<String, String>? = null
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val mobile = c.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val exact = name.equals(q, true) || name.split(' ').first().equals(q, true)
                    if (best == null || (exact && mobile)) best = name to number
                    if (exact && mobile) break
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }

    fun sendText(ctx: Context, number: String, body: String): String {
        if (!ctx.has(Manifest.permission.SEND_SMS)) return "I don't have permission to send texts yet; grant it on the Setup card."
        return try {
            val sms = ctx.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
            "Sent."
        } catch (e: Exception) {
            "The text failed to send: ${e.message}"
        }
    }

    fun call(ctx: Context, number: String): String {
        if (!ctx.has(Manifest.permission.CALL_PHONE)) return "I don't have permission to place calls yet; grant it on the Setup card."
        return try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            "Calling."
        } catch (e: Exception) {
            "Couldn't place the call: ${e.message}"
        }
    }

    fun media(ctx: Context, action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = when (action.lowercase()) {
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volume_up", "louder" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); return "Louder."
            }
            "volume_down", "quieter" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); return "Quieter."
            }
            else -> return "I can play, pause, skip, go back, or change the volume."
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "Done."
    }

    fun openApp(ctx: Context, name: String): String {
        if (!canLaunch(ctx)) return NEED_LAUNCH
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.trim().lowercase()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "I couldn't find an app called $name."
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "That app can't be opened."
        return if (launch(ctx, intent)) "Opened ${hit.loadLabel(pm)}." else "Couldn't open it."
    }

    fun navigate(ctx: Context, destination: String): String {
        if (!canLaunch(ctx)) return NEED_LAUNCH
        val uri = Uri.parse("google.navigation:q=" + URLEncoder.encode(destination, "UTF-8"))
        return if (launch(ctx, Intent(Intent.ACTION_VIEW, uri))) "Navigation to $destination is starting." else "Couldn't start navigation."
    }

    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String): String {
        if (!canLaunch(ctx)) return NEED_LAUNCH
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(ctx, i)) "Alarm set for %d:%02d.".format(hour, minute) else "Couldn't set the alarm."
    }

    /** Events for a day; 0 is today, 1 tomorrow. */
    fun agenda(ctx: Context, dayOffset: Int): String {
        if (!ctx.has(Manifest.permission.READ_CALENDAR)) return "I don't have calendar access."
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start.timeInMillis)
        ContentUris.appendId(b, start.timeInMillis + 24 * 60 * 60_000L - 1)
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
                ),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 12) {
                    val title = c.getString(0) ?: continue
                    val where = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                    out.add(if (c.getInt(2) != 0) "all day: $title$where" else "${fmt.format(Date(c.getLong(1)))} $title$where")
                }
            }
        } catch (e: Exception) {
            return "Couldn't read the calendar."
        }
        val day = SimpleDateFormat("EEEE MMM d", Locale.US).format(start.time)
        return if (out.isEmpty()) "Nothing on the calendar for $day." else "$day: " + out.joinToString("; ")
    }

    /** Parses "2026-11-12 14:30". */
    fun parseWhen(text: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(text.trim())?.time
    } catch (e: Exception) {
        null
    }

    /** Adds an event to the primary calendar. Returns (message, event uri or null). */
    fun createEvent(ctx: Context, title: String, start: Long, minutes: Int, location: String): Pair<String, Uri?> {
        if (!ctx.has(Manifest.permission.WRITE_CALENDAR)) return "I don't have permission to add calendar events yet; grant it on the Setup card." to null
        return try {
            var calId = -1L
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= " + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (calId < 0 || c.getInt(1) == 1) calId = c.getLong(0)
                    if (c.getInt(1) == 1) break
                }
            }
            if (calId < 0) return "There's no calendar on this phone I can write to." to null
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + minutes.coerceAtLeast(5) * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val whenText = SimpleDateFormat("EEE MMM d 'at' h:mm a", Locale.US).format(Date(start))
            if (uri == null) "The calendar refused the event." to null else "Added $title on $whenText." to uri
        } catch (e: Exception) {
            "Couldn't add the event: ${e.message}" to null
        }
    }
}

/** An Obsidian vault folder on the phone, chosen once in Settings. */
object Vault {
    fun configured(): Boolean = Prefs.vaultUri.isNotBlank()

    private fun root(ctx: Context): DocumentFile? = try {
        DocumentFile.fromTreeUri(ctx, Uri.parse(Prefs.vaultUri))?.takeIf { it.canRead() }
    } catch (e: Exception) {
        null
    }

    private fun notes(dir: DocumentFile, depth: Int, out: ArrayList<DocumentFile>) {
        if (depth > 4 || out.size >= 700) return
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (name.startsWith(".")) continue
            if (f.isDirectory) notes(f, depth + 1, out)
            else if (name.endsWith(".md", true)) out.add(f)
            if (out.size >= 700) return
        }
    }

    private fun read(ctx: Context, f: DocumentFile, max: Int = 200_000): String = try {
        ctx.contentResolver.openInputStream(f.uri)?.use { String(it.readBytes().let { b -> if (b.size > max) b.copyOf(max) else b }) } ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Lines across the vault that match the query, best first. */
    fun search(ctx: Context, query: String): String {
        val root = root(ctx) ?: return "The vault folder isn't reachable."
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (terms.isEmpty()) return "Nothing to search for."
        val files = ArrayList<DocumentFile>()
        notes(root, 0, files)
        val hits = ArrayList<Pair<Int, String>>()
        for (f in files) {
            val title = (f.name ?: "").removeSuffix(".md")
            val titleScore = terms.count { title.lowercase().contains(it) }
            for (line in read(ctx, f).lineSequence()) {
                val l = line.lowercase()
                val score = terms.count { l.contains(it) }
                if (score > 0) hits.add((score * 2 + titleScore) to "$title: ${line.trim().take(240)}")
            }
            if (titleScore > 0 && hits.none { it.second.startsWith("$title:") }) hits.add(titleScore to "$title: (note title matches)")
        }
        return hits.sortedByDescending { it.first }.take(8).joinToString("\n") { it.second }.ifBlank { "Nothing in the vault matches." }
    }

    /** The start of the note whose name best matches. */
    fun readNote(ctx: Context, name: String): String {
        val root = root(ctx) ?: return "The vault folder isn't reachable."
        val files = ArrayList<DocumentFile>()
        notes(root, 0, files)
        val q = name.trim().lowercase().removeSuffix(".md")
        val f = files.firstOrNull { (it.name ?: "").lowercase().removeSuffix(".md") == q }
            ?: files.firstOrNull { (it.name ?: "").lowercase().contains(q) }
            ?: return "There's no note called $name."
        return read(ctx, f, 6000).take(3000)
    }

    /** Appends a line to today's note in the vault's Atlas folder. Returns false if it couldn't. */
    fun appendDaily(ctx: Context, line: String): Boolean {
        val root = root(ctx) ?: return false
        return try {
            val dir = root.findFile("Atlas") ?: root.createDirectory("Atlas") ?: return false
            val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".md"
            val file = dir.findFile(name) ?: dir.createFile("text/markdown", name) ?: return false
            ctx.contentResolver.openOutputStream(file.uri, "wa")?.use { it.write((line.trimEnd() + "\n").toByteArray()) }
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Hermes on Athena: Atlas's heavier-duty other half. Anything with an OpenAI-style
 * chat endpoint works here (the Hermes API server, or another agent gateway).
 */
object Hermes {
    fun configured(): Boolean = Prefs.hermesUrl.isNotBlank()

    fun parseReply(body: String): String = try {
        JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    } catch (e: Exception) {
        ""
    }

    /** Blocking; agent runs can take minutes. Throws on failure. */
    fun ask(task: String): String {
        val headers = if (Prefs.hermesKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${Prefs.hermesKey.trim()}")
        val body = JSONObject()
            .put("model", Prefs.hermesModel.trim().ifBlank { "hermes-agent" })
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", task)))
            .toString()
        val (code, text) = Net.postJson(Prefs.hermesUrl.trim().trimEnd('/') + "/chat/completions", headers, body, 240_000)
        if (code !in 200..299) throw RuntimeException("Hermes answered $code")
        return parseReply(text).ifBlank { throw RuntimeException("Hermes sent back nothing") }
    }
}
