package com.jondude.anzu.assist

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.jondude.anzu.Glasses
import com.jondude.anzu.Prefs
import com.jondude.anzu.has
import java.util.Calendar

/**
 * Context Discretion (roadmap #16): when Jon is at the D&D table, at work, has guests over, or it's lights-out,
 * low-priority speech is held as cards for later instead of spoken. Urgent hub items, timers and direct replies
 * still break through. After bedtime the app also drops to grayscale.
 */
object Discretion {
    private val tableWords = Regex("(?i)\\b(d&d|dnd|dungeons|campaign|session zero|game night|table ?forge|one[- ]shot)\\b")

    /** Why to keep it down right now, or null. Pure, so it can be tested. */
    fun reason(
        hour: Int, weekday: Boolean, eventNow: String?, meeting: Boolean, guests: Boolean,
        bedtime: Int, wakeHour: Int, workOn: Boolean, workStart: Int, workEnd: Int, conversation: Boolean = false
    ): String? = when {
        guests -> "guests over"
        conversation -> "in a conversation"
        meeting || (eventNow != null && tableWords.containsMatchIn(eventNow)) -> "at the table"
        lightsOut(hour, bedtime, wakeHour) -> "lights out"
        workOn && weekday && hour in workStart until workEnd -> "work hours"
        else -> null
    }

    fun lightsOut(hour: Int, bedtime: Int = Prefs.bedtimeHour, wakeHour: Int = Prefs.wakeHour): Boolean =
        if (bedtime > wakeHour) hour >= bedtime || hour < wakeHour else hour in bedtime until wakeHour

    @Volatile private var cachedEvent: String? = null
    @Volatile private var eventAt = 0L

    /** The title of a (non all-day) calendar event happening now, cached for five minutes. */
    private fun eventNow(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - eventAt < 5 * 60_000) return cachedEvent
        eventAt = now
        cachedEvent = null
        if (!ctx.has(Manifest.permission.READ_CALENDAR)) return null
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now - 60_000)
            ContentUris.appendId(b, now + 60_000)
            ctx.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) != 0) continue
                    if (c.getLong(1) <= now && c.getLong(2) > now) { cachedEvent = c.getString(0); break }
                }
            }
        } catch (_: Exception) {
        }
        return cachedEvent
    }

    fun current(ctx: Context? = Brain.appContext): String? {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return reason(
            hour = cal.get(Calendar.HOUR_OF_DAY),
            weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY,
            eventNow = ctx?.let { eventNow(it) },
            meeting = Glasses.meeting.value,
            guests = System.currentTimeMillis() < Prefs.guestsUntil,
            conversation = SocialGrace.inConversation,
            bedtime = Prefs.bedtimeHour, wakeHour = Prefs.wakeHour,
            workOn = Prefs.workDiscretion, workStart = Prefs.workStart, workEnd = Prefs.workEnd
        )
    }

    /** After bedtime the whole app goes grayscale (orange included). */
    fun grayscale(): Boolean = Prefs.grayscaleNight && lightsOut(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
}
