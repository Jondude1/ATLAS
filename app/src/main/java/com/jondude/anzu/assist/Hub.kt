package com.jondude.anzu.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jondude.anzu.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hub on Obelisk: every app reports there, the local model digests it for free, and ATLAS
 * only receives a short brief (or a short answer when it asks). The contract is in GitHub issue #1.
 */
object Hub {
    data class Urgent(val id: Long, val app: String, val title: String, val body: String)
    data class Brief(val text: String, val urgent: List<Urgent>, val updated: Long)

    fun configured(): Boolean = Prefs.obeliskUrl.isNotBlank()
    private fun base() = Prefs.obeliskUrl.trim().trimEnd('/')
    private fun auth(): Map<String, String> =
        if (Prefs.hubToken.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${Prefs.hubToken.trim()}")

    @Volatile
    private var cached: Brief? = null
    @Volatile
    private var fetchedAt = 0L

    fun parseBrief(body: String): Brief? = try {
        val o = JSONObject(body)
        val arr = o.optJSONArray("urgent") ?: JSONArray()
        Brief(
            o.optString("brief").trim(),
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Urgent(it.optLong("id"), it.optString("app"), it.optString("title"), it.optString("body"))
            },
            o.optLong("updated")
        )
    } catch (e: Exception) {
        null
    }

    /** Blocking. The current brief, at most two minutes old; null if the hub can't be reached. */
    fun brief(maxAgeMs: Long = 120_000): Brief? {
        if (!configured()) return null
        val now = System.currentTimeMillis()
        cached?.let { if (now - fetchedAt < maxAgeMs) return it }
        return try {
            val (code, body) = Net.get(base() + "/atlas/brief", auth(), 6_000)
            if (code !in 200..299) return cached
            parseBrief(body)?.also { cached = it; fetchedAt = now }
        } catch (e: Exception) {
            cached
        }
    }

    /** Blocking. Marks urgent items as told so they aren't announced twice. */
    fun ack(ids: List<Long>) {
        if (!configured() || ids.isEmpty()) return
        try {
            Net.postJson(base() + "/atlas/ack", auth(), JSONObject().put("ids", JSONArray(ids)).toString(), 6_000)
            cached = cached?.let { b -> b.copy(urgent = b.urgent.filter { it.id !in ids }) }
        } catch (_: Exception) {
        }
    }

    /** Blocking. The local model answers from everything reported; null if unavailable. */
    fun ask(question: String): String? {
        if (!configured()) return null
        return try {
            val (code, body) = Net.postJson(
                base() + "/atlas/ask", auth(), JSONObject().put("question", question).toString(), 60_000
            )
            if (code in 200..299) JSONObject(body).optString("answer").takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking, and slow: Hermes works the task through the hub. Null if it couldn't. */
    fun delegate(task: String): String? {
        if (!configured()) return null
        return try {
            val (code, body) = Net.postJson(
                base() + "/atlas/delegate", auth(), JSONObject().put("task", task).toString(), 300_000
            )
            if (code in 200..299) JSONObject(body).optString("answer").takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Builds a report in the hub's format. */
    fun reportJson(app: String, title: String, body: String, priority: String, key: String?): JSONObject {
        val p = priority.lowercase().takeIf { it in setOf("low", "normal", "urgent") } ?: "normal"
        val o = JSONObject().put("app", app).put("title", title).put("body", body).put("priority", p)
            .put("at", System.currentTimeMillis())
        if (!key.isNullOrBlank()) o.put("key", key)
        return o
    }

    /** Sends a report in the background. */
    fun report(app: String, title: String, body: String = "", priority: String = "normal", key: String? = null) {
        if (!configured() || app.isBlank() || title.isBlank()) return
        val json = reportJson(app, title, body, priority, key).toString()
        Atlas.scope.launch(Dispatchers.IO) {
            try {
                Net.postJson(base() + "/atlas/report", auth(), json, 8_000)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Lets Jon's other phone apps report to ATLAS without network code of their own:
 *
 *   sendBroadcast(Intent("com.jondude.atlas.REPORT").setPackage("com.jondude.anzu")
 *       .putExtra("app", "Orbit").putExtra("title", "...").putExtra("body", "...")
 *       .putExtra("priority", "normal").putExtra("key", "optional-id"))
 *
 * ATLAS forwards it to the hub on Obelisk.
 */
class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.report(
            intent.getStringExtra("app") ?: return,
            intent.getStringExtra("title") ?: return,
            intent.getStringExtra("body") ?: "",
            intent.getStringExtra("priority") ?: "normal",
            intent.getStringExtra("key")
        )
    }
}
