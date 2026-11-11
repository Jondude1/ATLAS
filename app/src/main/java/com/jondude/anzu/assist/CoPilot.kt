package com.jondude.anzu.assist

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.jondude.anzu.Glasses
import com.jondude.anzu.Motion
import com.jondude.anzu.Prefs

/**
 * Co-Pilot Mode (roadmap #25): while Jon drives, ATLAS talks like a good co-pilot. One or two short sentences,
 * nothing to look at, confirmations by voice, and only what matters on the road (calendar, places, urgent).
 * Everything else waits as cards. When he parks: one line on what came in while he drove.
 */
object CoPilot {
    @Volatile var active = false
        private set
    @Volatile private var since = 0L
    @Volatile private var lastSeenDriving = 0L

    /** Sources that still speak on the road; the rest are held for when he parks. */
    val roadworthy = setOf("calendar", "places")

    /** Driving by GPS speed, the phone in car mode (Android Auto), or because he said so. */
    fun detect(ctx: Context): Boolean {
        if (System.currentTimeMillis() < Prefs.drivingUntil) return true
        val car = try {
            (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_CAR
        } catch (_: Exception) { false }
        return car || (Prefs.activityAware && Motion.driving())
    }

    sealed class Change { object Started : Change(); data class Parked(val minutes: Int) : Change(); object None : Change() }

    /** Feed one observation; starts at once, ends only after five minutes of not driving (lights, traffic, gas). */
    fun update(drivingNow: Boolean, now: Long = System.currentTimeMillis()): Change {
        if (!Prefs.coPilot) { active = false; return Change.None }
        if (drivingNow) {
            lastSeenDriving = now
            if (!active) { active = true; since = now; return Change.Started }
            return Change.None
        }
        if (active && now - lastSeenDriving > 5 * 60_000) {
            active = false
            return Change.Parked(((lastSeenDriving - since) / 60_000).toInt())
        }
        return Change.None
    }

    /** First words when the drive starts. */
    fun hello(nextEvent: String?): String =
        "Co-pilot on. " + (nextEvent?.let { "Next up: $it. " } ?: "") + "I'll keep it short."

    /** When he parks: what waited. */
    fun parkedLine(held: Int, catchUp: String?): String = buildString {
        append("You're parked.")
        if (held > 0) append(" $held thing${if (held == 1) "" else "s"} came in while you drove; they're on your phone.")
        if (!catchUp.isNullOrBlank()) append(" ").append(catchUp)
    }

    fun promptLine(): String = if (!active) "" else
        "Jon is driving. Be a co-pilot: one or two short sentences, no lists, never ask him to look at or tap the phone; ask yes-or-no out loud when you need his okay."

    fun reset() { active = false; since = 0; lastSeenDriving = 0 }
}
