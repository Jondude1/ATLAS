package com.jondude.anzu.assist

import com.jondude.anzu.Prefs
import com.jondude.anzu.overlay.OverlayTile
import com.jondude.anzu.overlay.TileKind

/**
 * The Holodeck tile's data (v1.2.19): the hub's CAD job state, shown as a tile.
 *
 * HONESTY, from the hub's real API (atlas_voice.py, verified against the source):
 * - There is NO cad-job list route. Jobs are found two honest ways: the job id this
 *   app last saw (persisted), and GET /atlas/library — finished builds land there
 *   with their job_id, newest first. An in-flight job the app has never seen and
 *   that hasn't finished yet is not discoverable from the phone; the tile says what
 *   it actually knows rather than guessing.
 * - Stages are NAMES (queued/writing/building/fetching/done) with no x-of-y count,
 *   and repair attempts make even the stage count variable — so NO percentage is
 *   ever shown. Status words only.
 */
object Holodeck {

    sealed interface Lookup {
        data class Found(val job: Hub.CadJob) : Lookup
        object None : Lookup
        object Unreachable : Lookup
    }

    /** The tile's stable identity: its display title changes with status, its id never does. */
    const val TILE_TITLE = "Holodeck"
    val TILE_ID: String = OverlayTile.idFor(TileKind.DATA, TILE_TITLE)

    private val leadIns = listOf(
        "make me a ", "make me an ", "make a ", "make an ", "make me ", "make ",
        "build me a ", "build me an ", "build a ", "build an ", "build me ", "build ",
        "create a ", "create an ", "create ", "print me a ", "print a ", "print ",
        "model a ", "model an ", "model "
    )
    private val stopWords = setOf("with", "that", "for", "and", "then", "in", "on", "to", "of")

    /** A short ALL-CAPS summary of a build prompt: "make me a shelf bracket with two holes" → "SHELF BRACKET". */
    fun summary(prompt: String): String {
        var p = prompt.trim().lowercase()
        for (lead in leadIns) if (p.startsWith(lead)) { p = p.removePrefix(lead); break }
        val words = ArrayList<String>()
        for (w in p.split(Regex("\\s+"))) {
            if (w in stopWords || words.size >= 4) break
            words += w.trim(',', '.', '!', '?')
        }
        val s = words.joinToString(" ").uppercase()
        return if (s.length <= 26) s else s.take(25).trimEnd() + "…"
    }

    /** The one status word for a job: queued / the live stage / done / failed. */
    fun statusWord(job: Hub.CadJob): String = when {
        job.done -> "DONE"
        job.failed -> "FAILED"
        job.status == "queued" -> "QUEUED"
        job.stage.isNotBlank() -> job.stage.uppercase()
        else -> "BUILDING"
    }

    /** "BRACKET — BUILDING": the summary (or the hub's own part name) plus the status word. */
    fun displayTitle(job: Hub.CadJob): String {
        val base = job.name.ifBlank { summary(job.prompt) }.uppercase()
        return (if (base.isBlank()) "HOLODECK JOB" else base) + " — " + statusWord(job)
    }

    /** The tile for a real job. [stale] marks data shown while the hub can't be reached. */
    fun tile(job: Hub.CadJob, stale: Boolean = false): OverlayTile {
        val lines = ArrayList<String>()
        when {
            job.done -> lines += "DONE — READY IN HOLODECK"
            job.failed -> {
                lines += "FAILED"
                lines += job.error.ifBlank { "The build failed on the hub." }.take(140)
            }
            else -> {
                lines += "Status: " + statusWord(job)
                if (job.attempts > 1) lines += "Attempt ${job.attempts}"
            }
        }
        if (job.prompt.isNotBlank() && !job.done) lines += job.prompt.take(110)
        if (stale) lines += "STALE — hub unreachable, last known state"
        return OverlayTile.data(TILE_TITLE, lines).copy(
            title = displayTitle(job),
            sourceLabel = if (stale) "STALE" else ""
        )
    }

    /** The honest tile for "no job": nothing running, or the hub unreachable with only a stale summary kept. */
    fun noJobTile(staleSummary: String): OverlayTile =
        if (staleSummary.isBlank()) {
            OverlayTile.data(TILE_TITLE, listOf("NO HOLODECK JOB RUNNING"))
        } else {
            OverlayTile.data(TILE_TITLE, listOf(staleSummary, "STALE — hub unreachable, last known state"))
                .copy(sourceLabel = "STALE")
        }

    private fun remember(job: Hub.CadJob) {
        Prefs.lastCadJobId = job.jobId
        Prefs.lastCadJobSummary = displayTitle(job)
    }

    /**
     * Blocking. The current job: an ACTIVE remembered job wins; otherwise the newest
     * library job that resolves; otherwise the remembered finished job. [Lookup.None]
     * only when the hub answered and there is genuinely nothing; [Lookup.Unreachable]
     * when no hub call got through (or the token is missing — Hub.needsToken says which).
     */
    fun currentJob(): Lookup {
        if (!Hub.configured()) return Lookup.Unreachable
        val remembered = Prefs.lastCadJobId
        if (remembered.isNotBlank()) {
            val (code, job) = Hub.cadJob(remembered)
            if (code == -1) return Lookup.Unreachable
            if (job != null) {
                if (job.active) return Lookup.Found(job)
                // Finished: show something newer from the library if there is any.
                val (lcode, entries) = Hub.library()
                if (lcode == -1) return Lookup.Found(job)
                if (lcode in 200..299) {
                    for (e in entries.take(3)) {
                        val jid = e.jobId ?: continue
                        if (jid == remembered) break
                        val (_, newer) = Hub.cadJob(jid)
                        if (newer != null) {
                            remember(newer)
                            return Lookup.Found(newer)
                        }
                    }
                }
                return Lookup.Found(job)
            }
            // 404: the remembered job is gone from the hub; fall through to the library.
        }
        val (lcode, entries) = Hub.library()
        if (lcode == -1) return Lookup.Unreachable
        if (lcode !in 200..299) return Lookup.Unreachable
        for (e in entries.take(3)) {
            val jid = e.jobId ?: continue
            val (_, job) = Hub.cadJob(jid)
            if (job != null) {
                remember(job)
                return Lookup.Found(job)
            }
        }
        return Lookup.None
    }

    /** Blocking. Re-read one job by id (the tile's poll); null when it's gone or unreachable. */
    fun poll(jobId: String): Hub.CadJob? {
        val (_, job) = Hub.cadJob(jobId)
        if (job != null) remember(job)
        return job
    }
}
