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

    data class Container(val name: String, val status: String, val ports: String) {
        val up get() = status.startsWith("Up", ignoreCase = true)
        /** Containers group by the first part of their name: piped-nginx and piped-backend are one stack. */
        val stack get() = name.substringBefore('-').substringBefore('_')
    }
    data class AthenaStatus(val containers: List<Container>, val uptime: String, val disk: String, val error: String?)
    data class AthenaRun(val ok: Boolean, val exit: Int, val out: String)

    /** Reads the hub's cached Athena status. Lenient about the shape so small server changes don't blank the screen. */
    fun parseAthena(body: String): AthenaStatus {
        val o = JSONObject(body)
        val arr = o.optJSONArray("containers") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val ports = c.opt("ports")
            Container(
                c.optString("name", c.optString("Names")),
                c.optString("status", c.optString("Status")),
                if (ports is JSONArray) (0 until ports.length()).joinToString(", ") { ports.optString(it) } else (ports?.toString() ?: "")
            )
        }
        // The hub fills its cache in the background; right after a restart it has nothing yet.
        if (list.isEmpty() && o.optLong("updated", -1) == 0L) {
            return AthenaStatus(emptyList(), "", "", "Athena's status is still loading on the hub. Ask again in a minute.")
        }
        return AthenaStatus(list, readableUptime(o.optString("uptime")), readableDisk(o.opt("disk")?.toString() ?: ""), null)
    }

    /** PowerShell's TimeSpan, flattened ("Days : 3 Hours : 4 Minutes : 12 ..."), as "3 days, 4 hours". Anything else passes through. */
    fun readableUptime(raw: String): String {
        val d = Regex("\\bDays\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val h = Regex("\\bHours\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        if (d == null && h == null) return raw.trim()
        val parts = ArrayList<String>()
        if ((d ?: 0) > 0) parts += "$d day${if (d == 1) "" else "s"}"
        parts += "${h ?: 0} hour${if (h == 1) "" else "s"}"
        return parts.joinToString(", ")
    }

    /** PowerShell's "Used Free ---- ---- <bytes> <bytes>" as "412 GB free of 931 GB". Anything else passes through. */
    fun readableDisk(raw: String): String {
        val nums = Regex("\\b\\d{6,}\\b").findAll(raw).map { it.value.toDouble() }.toList()
        if (!raw.contains("Free") || nums.size < 2) return raw.trim()
        val gb = 1024.0 * 1024 * 1024
        val used = nums[0] / gb
        val free = nums[1] / gb
        return "%.0f GB free of %.0f GB".format(java.util.Locale.US, free, used + free)
    }

    /** Blocking. Athena's containers, uptime and disk, as the hub last saw them. */
    fun athenaStatus(): AthenaStatus {
        if (!configured()) return AthenaStatus(emptyList(), "", "", "The hub isn't set up.")
        return try {
            val (code, body) = Net.get(base() + "/atlas/athena/status", auth(), 10_000)
            when (code) {
                in 200..299 -> parseAthena(body)
                401, 403 -> AthenaStatus(emptyList(), "", "", "The hub wants its token (Settings, Obelisk).")
                404 -> AthenaStatus(emptyList(), "", "", "The hub doesn't have the Athena channel yet.")
                else -> AthenaStatus(emptyList(), "", "", "Athena didn't answer (${code}).")
            }
        } catch (e: Exception) {
            AthenaStatus(emptyList(), "", "", "Couldn't reach the hub on Obelisk.")
        }
    }

    /** Blocking. Runs one command on Athena through the hub and returns what it printed. */
    fun athenaRun(command: String): AthenaRun {
        if (!configured()) return AthenaRun(false, -1, "The hub isn't set up.")
        return try {
            val (code, body) = Net.postJson(
                base() + "/atlas/athena", auth(), JSONObject().put("command", command).toString(), 90_000
            )
            when (code) {
                in 200..299 -> {
                    val o = JSONObject(body)
                    val out = listOf(o.optString("stdout"), o.optString("stderr")).filter { it.isNotBlank() }.joinToString("\n").trimEnd()
                    AthenaRun(o.optBoolean("ok"), o.optInt("exit", -1), out.ifBlank { "(no output)" })
                }
                401, 403 -> AthenaRun(false, -1, "The hub wants its token. Add it in Settings, Obelisk.")
                else -> AthenaRun(false, -1, "Athena didn't answer (${code}). " + try { JSONObject(body).optString("detail") } catch (e: Exception) { "" })
            }
        } catch (e: Exception) {
            AthenaRun(false, -1, "Athena didn't answer: couldn't reach the hub.")
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
