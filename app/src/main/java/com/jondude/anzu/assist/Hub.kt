package com.jondude.anzu.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jondude.anzu.Prefs
import com.jondude.anzu.Store
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

    /** True when the hub turned the app away for want of its token (Settings → Obelisk). */
    @Volatile
    var needsToken = false
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
            val (code, body) = Net.get(base() + "/atlas/brief", auth(), 6_000, connectTimeoutMs = 2_000)
            needsToken = code == 401 || code == 403
            if (code !in 200..299) return cached
            parseBrief(body)?.also { cached = it; fetchedAt = now; lastSpokenLoops = parseLoops(body) }
        } catch (e: Exception) {
            cached
        }
    }

    data class HermesConfig(val url: String, val key: String, val model: String)

    /** The hub's /atlas/hermes-config payload; null when malformed or when url or key is blank (not provisioned). */
    fun parseHermesConfig(body: String): HermesConfig? = try {
        val o = JSONObject(body)
        val url = o.optString("url").trim()
        val key = o.optString("key").trim()
        if (url.isBlank() || key.isBlank()) null
        else HermesConfig(url, key, o.optString("model").trim().ifBlank { "hermes-agent" })
    } catch (e: Exception) {
        null
    }

    private const val PROVISION_RETRY_MS = 6 * 60 * 60 * 1000L
    private const val PROVISION_FAILED_RETRY_MS = 60_000L

    private fun tokenHash(token: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * True (and stamps the attempt) when Hermes still needs its KEY, the hub is set up,
     * and either no attempt ran in the last 6 hours or the hub token changed since the last
     * attempt (a corrected typo re-arms provisioning at once). The gate is the key, not
     * Hermes.configured(): the Hermes URL is hardwired now (Prefs.hermesUrl defaults to
     * HERMES_URL), so "configured" is always true and would starve provisioning forever.
     */
    @Synchronized
    internal fun claimProvisionAttempt(now: Long = System.currentTimeMillis()): Boolean {
        if (Prefs.hermesKey.isNotBlank() || !configured() || Prefs.hubToken.isBlank()) return false
        val hash = tokenHash(Prefs.hubToken)
        val tokenChanged = hash != Prefs.hermesProvisionTokenHash
        if (!tokenChanged && now - Prefs.hermesProvisionTriedAt < PROVISION_RETRY_MS) return false
        Prefs.hermesProvisionTriedAt = now
        Prefs.hermesProvisionTokenHash = hash
        return true
    }

    /** A failed attempt must not burn the 6h throttle: shorten it so the next claim is allowed ~60s later. */
    @Synchronized
    internal fun releaseFailedProvisionAttempt(now: Long = System.currentTimeMillis()) {
        Prefs.hermesProvisionTriedAt = now - (PROVISION_RETRY_MS - PROVISION_FAILED_RETRY_MS)
    }

    /** Blocking, silent on any failure. Fetches Hermes' endpoint and key from the hub when they aren't set yet. */
    fun provisionHermesNow() {
        try {
            if (!claimProvisionAttempt()) return
            val (code, body) = Net.get(base() + "/atlas/hermes-config", auth(), 5_000, connectTimeoutMs = 2_000)
            val cfg = if (code in 200..299) parseHermesConfig(body) else null
            if (cfg == null) {
                releaseFailedProvisionAttempt()
                return
            }
            Prefs.hermesUrl = cfg.url
            Prefs.hermesKey = cfg.key
            Prefs.hermesModel = cfg.model
            Store.logAction("Hermes provisioned from the hub")
        } catch (_: Exception) {
            releaseFailedProvisionAttempt()
        }
    }

    /** Background version of [provisionHermesNow]; a cheap no-op once the key has landed or when tried recently. */
    fun provisionHermesIfNeeded() {
        if (Prefs.hermesKey.isNotBlank()) return
        Atlas.scope.launch(Dispatchers.IO) { provisionHermesNow() }
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

    /** A pending trigger from the hub's registry (Life OS Phase 3). [dedupeKey] groups repeats of the same event. */
    data class Trigger(
        val id: Long, val source: String, val kind: String, val summary: String,
        val payload: JSONObject, val createdAt: String, val dedupeKey: String
    )

    /** The hub's /atlas/proactive-triggers payload. A malformed entry is skipped rather than failing the batch; a bad body is an empty list. */
    fun parseTriggers(body: String): List<Trigger> = try {
        val arr = JSONObject(body).optJSONArray("triggers") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                if (!o.has("id")) return@mapNotNull null
                val kind = o.optString("kind").trim()
                if (kind.isBlank()) return@mapNotNull null
                val source = o.optString("source").trim()
                Trigger(
                    o.getLong("id"), source, kind, o.optString("summary").trim(),
                    o.optJSONObject("payload") ?: JSONObject(), o.optString("created_at"),
                    o.optString("dedupe_key").trim().ifBlank { "$source:$kind" }
                )
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking. Triggers the hub is holding for a decision; empty if the hub can't be reached. */
    fun pendingTriggers(): List<Trigger> {
        if (!configured()) return emptyList()
        return try {
            val (code, body) = Net.get(base() + "/atlas/proactive-triggers?status=pending", auth(), 6_000, connectTimeoutMs = 2_000)
            needsToken = code == 401 || code == 403
            if (code in 200..299) parseTriggers(body) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Blocking. Records what was decided about a trigger (decision: fired, suppressed or gated). Silent on failure. */
    fun ackTrigger(id: Long, decision: String, reason: String, action: String) {
        if (!configured()) return
        try {
            val o = JSONObject().put("id", id).put("decision", decision).put("reason", reason).put("action", action)
            Net.postJson(base() + "/atlas/proactive-triggers/ack", auth(), o.toString(), 6_000)
        } catch (_: Exception) {
        }
    }

    /** Blocking. Registers a trigger on the hub (the hub dedupes on [dedupeKey] for 24h). True once the hub has it; false on any failure. */
    fun postTrigger(source: String, kind: String, dedupeKey: String, summary: String, payload: JSONObject): Boolean {
        if (!configured()) return false
        return try {
            val o = JSONObject().put("source", source).put("kind", kind).put("dedupe_key", dedupeKey)
                .put("summary", summary).put("payload", payload)
            val (code, _) = Net.postJson(base() + "/atlas/proactive-triggers", auth(), o.toString(), 6_000, connectTimeoutMs = 2_000)
            needsToken = code == 401 || code == 403
            code in 200..299
        } catch (e: Exception) {
            false
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

    data class Loop(val id: Long, val text: String, val source: String, val createdAt: Long, val ackedAt: Long, val spokenCount: Int)

    /** The loops the last brief spoke (at most two), so "that's done" can close them. */
    @Volatile
    var lastSpokenLoops: List<Loop> = emptyList()

    fun parseLoops(body: String, field: String = "open_loops"): List<Loop> = try {
        val arr = JSONObject(body).optJSONArray(field) ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Loop(it.optLong("id"), it.optString("text"), it.optString("source"),
                it.optLong("created_at"), it.optLong("acked_at", 0), it.optInt("spoken_count"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking. Open loops oldest first, or the whole history newest first; null if the hub can't be reached. */
    fun openLoops(includeClosed: Boolean = false): List<Loop>? {
        if (!configured()) return null
        return try {
            val (code, body) = Net.get(base() + "/atlas/open-loops?include_closed=$includeClosed&limit=100", auth(), 8_000)
            needsToken = code == 401 || code == 403
            if (code in 200..299) parseLoops(body) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking. Closes loops; true if the hub took it. */
    fun ackLoops(ids: List<Long>): Boolean {
        if (!configured() || ids.isEmpty()) return false
        return try {
            val (code, _) = Net.postJson(base() + "/atlas/open-loop/ack", auth(), JSONObject().put("ids", JSONArray(ids)).toString(), 8_000)
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /** The loop whose text best matches what he said ("the vet one"), or null. */
    fun matchLoop(loops: List<Loop>, said: String): Loop? {
        val words = said.lowercase().split(Regex("\\W+")).filter { it.length > 2 && it !in setOf("the", "that", "one", "done", "loop", "close", "finished", "with", "about") }
        if (words.isEmpty()) return null
        return loops.map { l -> l to words.count { w -> l.text.lowercase().contains(w) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** Blocking. Posts an open loop (it rides the brief until acked). */
    fun openLoop(text: String, source: String, key: String?) {
        if (!configured()) return
        try {
            val o = JSONObject().put("text", text).put("source", source)
            if (key != null) o.put("key", key)
            Net.postJson(base() + "/atlas/open-loop", auth(), o.toString(), 8_000)
        } catch (_: Exception) {
        }
    }

    /** Blocking. "Catch me up": what's been reported since he last asked, composed by the hub; null if unreachable. */
    fun catchMeUp(): String? {
        if (!configured()) return null
        return try {
            val (code, body) = Net.get(base() + "/atlas/catch-me-up", auth(), 60_000)
            needsToken = code == 401 || code == 403
            if (code in 200..299) JSONObject(body).optString("summary").takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking. A JSON call to any hub endpoint: (http code, body), or (-1, "") if unreachable. */
    fun call(method: String, path: String, json: JSONObject? = null, timeoutMs: Int = 30_000): Pair<Int, String> {
        if (!configured()) return -1 to ""
        return try {
            val r = if (method == "GET") Net.get(base() + path, auth(), timeoutMs)
            else Net.postJson(base() + path, auth(), (json ?: JSONObject()).toString(), timeoutMs)
            needsToken = r.first == 401 || r.first == 403
            r
        } catch (e: Exception) {
            -1 to ""
        }
    }

    // ---- Holodeck CAD jobs (v1.2.19): the same hub routes the Holodeck app uses ----

    /**
     * One CAD job as GET /atlas/cad-job/{id} reports it. The hub's stages are NAMES
     * (queued / writing / building / fetching / done / failed) with no x-of-y count —
     * so no honest percentage can be derived, and the tile shows status words only.
     */
    data class CadJob(
        val jobId: String, val status: String, val stage: String, val prompt: String,
        val name: String, val error: String, val attempts: Int
    ) {
        val active: Boolean get() = status == "queued" || status == "working"
        val done: Boolean get() = status == "done"
        val failed: Boolean get() = status == "failed"
    }

    fun parseCadJob(body: String): CadJob? = try {
        val o = JSONObject(body)
        val id = o.optString("job_id").trim()
        if (id.isBlank()) null
        else CadJob(
            id, o.optString("status").trim(), o.optString("stage").trim(),
            o.optString("prompt").trim(), o.optString("name").trim(),
            o.optString("error").trim(), o.optInt("attempts")
        )
    } catch (e: Exception) {
        null
    }

    /** Blocking. (http code, job): -1 = hub unreachable, 404 = no such job, 200 = [CadJob]. */
    fun cadJob(id: String): Pair<Int, CadJob?> {
        val (code, body) = call("GET", "/atlas/cad-job/$id", null, 8_000)
        return code to if (code in 200..299) parseCadJob(body) else null
    }

    /** The job id from POST /atlas/cad-job's reply; null when it's blank, missing or not JSON. */
    fun parseCadJobCreated(body: String): String? = try {
        JSONObject(body).optString("job_id").trim().ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** Blocking. Creates a CAD job from a build prompt: (http code, job_id) — -1 = hub unreachable, id null unless the hub accepted it. */
    fun createCadJob(prompt: String): Pair<Int, String?> {
        val (code, body) = call("POST", "/atlas/cad-job", JSONObject().put("prompt", prompt), 15_000)
        return code to if (code in 200..299) parseCadJobCreated(body) else null
    }

    /** One library entry: finished Holodeck builds and imported STLs, newest first as the hub sorts them. */
    data class LibEntry(val id: String, val kind: String, val name: String, val created: Double, val jobId: String?)

    fun parseLibrary(body: String): List<LibEntry> = try {
        val arr = JSONObject(body).optJSONArray("entries") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LibEntry(
                o.optString("id"), o.optString("kind"), o.optString("name"),
                o.optDouble("created", 0.0), o.optString("job_id").trim().ifBlank { null }
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking. (http code, entries): the hub's library — the only job-history surface the hub exposes (there is no cad-job list route). */
    fun library(): Pair<Int, List<LibEntry>> {
        val (code, body) = call("GET", "/atlas/library", null, 15_000)
        return code to if (code in 200..299) parseLibrary(body) else emptyList()
    }

    // ---- Skills Marketplace (v1.2.21): Hermes's Skills Hub through the house hub ----

    data class SkillHit(val name: String, val identifier: String, val source: String, val trustLevel: String, val description: String)

    data class SkillInstalled(val name: String, val category: String, val source: String, val trust: String, val status: String)

    data class SkillInstallResult(val ok: Boolean, val installed: String, val scan: String, val output: String)

    fun parseSkillSearch(body: String): List<SkillHit> = try {
        val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val identifier = o.optString("identifier").trim()
            if (identifier.isBlank()) null
            else SkillHit(
                o.optString("name").trim(), identifier, o.optString("source").trim(),
                o.optString("trust_level").trim(), o.optString("description").trim()
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun parseSkillInstalled(body: String): List<SkillInstalled> = try {
        val arr = JSONObject(body).optJSONArray("installed") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isBlank()) null
            else SkillInstalled(
                name, o.optString("category").trim(), o.optString("source").trim(),
                o.optString("trust").trim(), o.optString("status").trim()
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun parseSkillInspect(body: String): String? = try {
        val o = JSONObject(body)
        if (o.has("text") && !o.isNull("text")) o.optString("text") else null
    } catch (e: Exception) {
        null
    }

    fun parseSkillInstall(body: String): SkillInstallResult? = try {
        val o = JSONObject(body)
        if (!o.has("ok")) null
        else SkillInstallResult(o.optBoolean("ok"), o.optString("installed").trim(), o.optString("scan").trim(), o.optString("output").trim())
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Blocking. (http code, hits): the hub's Hermes CLI search can take 10-45s. */
    fun searchSkills(q: String, limit: Int = 25): Pair<Int, List<SkillHit>> {
        val (code, body) = call("GET", "/atlas/skills/search?q=${enc(q)}&limit=$limit", null, 150_000)
        return code to if (code in 200..299) parseSkillSearch(body) else emptyList()
    }

    /** Blocking. (http code, skills installed from the hub). */
    fun installedSkills(): Pair<Int, List<SkillInstalled>> {
        val (code, body) = call("GET", "/atlas/skills/installed", null, 90_000)
        return code to if (code in 200..299) parseSkillInstalled(body) else emptyList()
    }

    /** Blocking. (http code, preview text). */
    fun inspectSkill(identifier: String): Pair<Int, String?> {
        val (code, body) = call("GET", "/atlas/skills/inspect?identifier=${enc(identifier)}", null, 150_000)
        return code to if (code in 200..299) parseSkillInspect(body) else null
    }

    /** Blocking. Installs run Hermes's security scan and can take about two minutes. No force option exists. */
    fun installSkill(identifier: String): Pair<Int, SkillInstallResult?> {
        val (code, body) = call("POST", "/atlas/skills/install", JSONObject().put("identifier", identifier), 330_000)
        return code to if (code in 200..299) parseSkillInstall(body) else null
    }

    /** Blocking. True only when the hub's reply parses and says ok. */
    fun uninstallSkill(name: String): Pair<Int, Boolean> {
        val (code, body) = call("POST", "/atlas/skills/uninstall", JSONObject().put("name", name), 150_000)
        val ok = code in 200..299 && try {
            JSONObject(body).optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
        return code to ok
    }

    /** Plain words for a failed hub call. */
    fun failure(code: Int): String = when (code) {
        -1 -> "I can't reach the hub on Obelisk right now."
        401, 403 -> "The hub wants its token first. It goes in Settings, Obelisk."
        404 -> "The hub doesn't know that one."
        else -> "The hub had trouble with that ($code)."
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
