package com.jondude.anzu.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jondude.anzu.MainActivity
import com.jondude.anzu.Prefs
import com.jondude.anzu.Store
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The proactive engine (Life OS Phase 3). The hub holds a registry of triggers; each poll the brain tier decides what to do
 * with every pending one, and the guardrails below are enforced in code, whatever the model said. Delivery is speech or a
 * notification only: nothing here reaches a tool, so a trigger can never spend, send or delete.
 */
object Proactive {
    enum class Act { SPEAK, NOTIFY, GATE, SUPPRESS }

    data class Decision(val act: Act, val text: String, val reason: String)

    const val SPEAK_CAP_PER_HOUR = 6
    const val NOTIFY_CAP_PER_HOUR = 12
    const val DEDUPE_WINDOW_MS = 24 * 60 * 60_000L
    private const val HOUR_MS = 60 * 60_000L
    private const val SEEN_KEY = "proactiveSeen"
    private const val SPEAK_KEY = "proactiveSpeakAt"
    private const val NOTIFY_KEY = "proactiveNotifyAt"
    private const val CHANNEL = "atlas_proactive"

    private val unparseable = Decision(Act.SUPPRESS, "", "unparseable decision")

    /** Strict and fail-closed: anything but a JSON object with a known act and a reason (and text, for speech and notices) is a suppression. */
    fun parseDecision(reply: String): Decision {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return unparseable
        return try {
            val o = JSONObject(reply.substring(start, end + 1))
            val act = when (o.optString("act").trim().lowercase()) {
                "speak" -> Act.SPEAK
                "notify" -> Act.NOTIFY
                "gate" -> Act.GATE
                "suppress" -> Act.SUPPRESS
                else -> return unparseable
            }
            val reason = o.optString("reason").trim()
            val text = o.optString("text").trim()
            if (!o.has("reason") || reason.isBlank()) return unparseable
            if ((act == Act.SPEAK || act == Act.NOTIFY) && text.isBlank()) return unparseable
            Decision(act, text, reason)
        } catch (e: Exception) {
            unparseable
        }
    }

    /** The short system prompt: the trigger, Jon's standing orders, what was decided lately for this kind, and the JSON demanded back. */
    fun decisionPrompt(t: Hub.Trigger, orders: List<String>, recent: List<String>): String = buildString {
        append("You decide whether Atlas should act on one event from Jon's systems. Be conservative: most events deserve silence.\n")
        append("Event: ${t.source}/${t.kind}: ${t.summary.take(300)}\n")
        if (t.payload.length() > 0) append("Details: ${t.payload.toString().take(400)}\n")
        if (orders.isNotEmpty()) append("Jon's standing orders: ").append(orders.joinToString("; ") { it.take(160) }).append('\n')
        if (recent.isNotEmpty()) append("Recent decisions for this kind: ").append(recent.joinToString(" | ") { it.take(120) }).append('\n')
        append("Acts: speak (say it aloud now, one short sentence), notify (a silent notification), gate (needs Jon's okay first: anything involving money, sending a message to someone, or deleting), suppress (say nothing).\n")
        append("Reply with ONLY one JSON object, no other text: {\"act\":\"speak|notify|gate|suppress\",\"text\":\"what to say or show\",\"reason\":\"why, briefly\"}")
    }

    /** Hermes when it's set up, else the local model; null if neither answers. Deliberately not Brain's tool loop. */
    fun askBrain(system: String, user: String): String? {
        if (Hermes.configured()) {
            try {
                Hermes.chat(system, listOf("user" to user)).takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) {
            }
        }
        if (Local.configured()) {
            try {
                Local.chat(system, listOf("user" to user)).takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ---- Enforcement: pure, state passed in ----

    private val gatedKind = Regex("(?i)(pay|payment|purchase|buy|order|refund|transfer|charge|invoice|money|spend|send|email|text|message|reply|post|delete|remove|erase|wipe)")
    private val gatedIntent = Regex(
        "(?i)\\b(i'?ll|i will|i'm going to|i am going to|let me|shall i|should i|want me to|going to)\\s+" +
            "(send|text|email|message|reply|pay|buy|order|purchase|transfer|refund|charge|delete|remove|erase|wipe)\\b"
    )

    /** True when the trigger or what the model proposes involves money, sending a message, or deleting anything. */
    fun gated(kind: String, d: Decision): Boolean = gatedKind.containsMatchIn(kind) || gatedIntent.containsMatchIn(d.text)

    fun duplicate(seen: Map<String, Long>, key: String, now: Long): Boolean =
        seen[key]?.let { now - it in 0 until DEDUPE_WINDOW_MS } ?: false

    fun withinCap(stamps: List<Long>, cap: Int, now: Long): Boolean = stamps.count { now - it in 0 until HOUR_MS } < cap

    /** Applies, in order: dedupe, rate limits, quiet hours, gated actions. [speakAt] and [notifyAt] are the delivery times in the last hour or so. */
    fun enforce(
        kind: String, dedupeKey: String, d: Decision,
        seen: Map<String, Long>, speakAt: List<Long>, notifyAt: List<Long>, quiet: Boolean, now: Long
    ): Decision {
        if (duplicate(seen, dedupeKey, now)) return Decision(Act.SUPPRESS, "", "duplicate")
        if (d.act == Act.SUPPRESS) return d
        if (d.act == Act.SPEAK && !withinCap(speakAt, SPEAK_CAP_PER_HOUR, now)) return Decision(Act.SUPPRESS, "", "rate limit")
        if (d.act == Act.NOTIFY && !withinCap(notifyAt, NOTIFY_CAP_PER_HOUR, now)) return Decision(Act.SUPPRESS, "", "rate limit")
        var out = d
        if (out.act == Act.SPEAK && quiet) out = out.copy(act = Act.NOTIFY, reason = "quiet hours: ${out.reason}")
        if (out.act != Act.GATE && gated(kind, out)) out = out.copy(act = Act.GATE, reason = "needs Jon's okay: ${out.reason}")
        // Quiet downgrades and gates land on the notification channel, so it has to have room too.
        if ((out.act == Act.NOTIFY || out.act == Act.GATE) && !withinCap(notifyAt, NOTIFY_CAP_PER_HOUR, now)) {
            return Decision(Act.SUPPRESS, "", "rate limit")
        }
        return out
    }

    // ---- Persisted state ----

    private fun loadSeen(now: Long): MutableMap<String, Long> = try {
        val o = JSONObject(Prefs.sp.getString(SEEN_KEY, "{}"))
        o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { now - it < DEDUPE_WINDOW_MS }.toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }

    private fun saveSeen(m: Map<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        Prefs.sp.edit().putString(SEEN_KEY, o.toString()).apply()
    }

    private fun loadStamps(key: String, now: Long): MutableList<Long> = try {
        val a = JSONArray(Prefs.sp.getString(key, "[]"))
        (0 until a.length()).map { a.getLong(it) }.filter { now - it < HOUR_MS }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun saveStamps(key: String, l: List<Long>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        Prefs.sp.edit().putString(key, a.toString()).apply()
    }

    /** Quiet by Jon's "quiet" request or by the app's discretion (lights out, table, guests, work, conversation). */
    private fun quietNow(now: Long): Boolean = Prefs.quietUntil > now || Discretion.current() != null

    // ---- The loop ----

    private val running = AtomicBoolean(false)

    /** Blocking. One pass over the hub's pending triggers; every outcome is logged and acked, and no failure stops the pass. */
    fun pollOnce(ctx: Context) {
        if (!Hub.configured() || !running.compareAndSet(false, true)) return
        try {
            for (t in Hub.pendingTriggers()) {
                try {
                    handle(ctx, t)
                } catch (e: Exception) {
                    Store.logAction("Proactive ${t.kind}: suppressed (error: ${e.message?.take(60)})")
                    ack(t, Decision(Act.SUPPRESS, "", "error"))
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun handle(ctx: Context, t: Hub.Trigger) {
        val now = System.currentTimeMillis()
        val raw = askBrain(
            decisionPrompt(
                t, StandingOrders.load().map { "whenever ${it.trigger}, ${it.action}" },
                Store.actions.value.map { it.text }.filter { it.startsWith("Proactive ${t.kind}:") }.take(3)
            ),
            "Decide now."
        )
        val proposed = if (raw == null) Decision(Act.SUPPRESS, "", "no brain reachable") else parseDecision(raw)
        val seen = loadSeen(now)
        val speakAt = loadStamps(SPEAK_KEY, now)
        val notifyAt = loadStamps(NOTIFY_KEY, now)
        var d = enforce(t.kind, t.dedupeKey, proposed, seen, speakAt, notifyAt, quietNow(now), now)
        when (d.act) {
            Act.SPEAK -> try {
                Atlas.polite(d.text, source = "proactive")
                speakAt.add(now)
            } catch (e: Exception) {
                d = Decision(Act.SUPPRESS, "", "delivery failed")
            }
            Act.NOTIFY, Act.GATE -> if (notify(ctx, t, d)) {
                notifyAt.add(now)
                if (d.act == Act.NOTIFY) putUpFrigateTile(ctx, t, now)
            } else d = Decision(Act.SUPPRESS, "", "delivery failed")
            Act.SUPPRESS -> {}
        }
        seen[t.dedupeKey] = now
        saveSeen(seen)
        saveStamps(SPEAK_KEY, speakAt)
        saveStamps(NOTIFY_KEY, notifyAt)
        Store.logAction("Proactive ${t.kind}: ${d.act.name.lowercase()} (${d.reason.take(80)})")
        ack(t, d)
    }

    private fun ack(t: Hub.Trigger, d: Decision) {
        val (decision, action) = when (d.act) {
            Act.SPEAK -> "fired" to "speak"
            Act.NOTIFY -> "fired" to "notify"
            Act.GATE -> "gated" to "notify"
            Act.SUPPRESS -> "suppressed" to "none"
        }
        try {
            Hub.ackTrigger(t.id, decision, d.reason, action)
        } catch (_: Exception) {
        }
    }

    /**
     * The put-up that rides a Frigate person notification (v1.2.18): the room's camera
     * tile goes up alongside the notification, under the same manners as every other
     * proactive act — it only happens here because [enforce] already passed the trigger
     * (dedupe, caps, gating), and quiet hours suppress the tile (never the notification).
     * An unresolvable camera skips the tile silently; the notification has already fired.
     */
    private fun putUpFrigateTile(ctx: Context, t: Hub.Trigger, now: Long) {
        if (t.source != "frigate" || t.kind != "person_seen") return
        if (quietNow(now)) return
        val room = t.payload.optString("camera")
        if (room.isBlank()) return
        try {
            val cam = com.jondude.anzu.overlay.TileManager.cameraForRoom(room) ?: return
            com.jondude.anzu.overlay.TileManager.putUpCamera(ctx, cam)
        } catch (_: Exception) {
        }
    }

    /** A plain notification. A gated one only says Jon's okay is needed; nothing runs and nothing waits on him. */
    private fun notify(ctx: Context, t: Hub.Trigger, d: Decision): Boolean = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atlas heads-ups", NotificationManager.IMPORTANCE_DEFAULT))
        val gate = d.act == Act.GATE
        val title = if (gate) "Atlas needs your okay" else "Atlas"
        val body = d.text.ifBlank { t.summary }.take(300)
        nm.notify(
            9000 + (t.id % 1000).toInt(), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build()
        )
        true
    } catch (e: Exception) {
        false
    }
}
