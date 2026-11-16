package com.jondude.anzu.assist

import com.jondude.anzu.Glasses
import com.jondude.anzu.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** "…the kitchen light, no wait, the bedroom": Jon fixed himself mid-sentence, so act on the corrected version. */
object SelfRepair {
    private val markers = Regex("(?i)\\b(no,? wait|wait,? no|scratch that|actually,? no|i mean|sorry,? i meant|make that|or rather)\\b")

    fun detect(text: String): Boolean = markers.containsMatchIn(text)

    fun hint(text: String): String =
        if (!detect(text)) "" else
            "\n\nJon corrected himself mid-sentence. Act only on the corrected version (what he said after the correction), and don't mention the slip."
}

/** Short commands that lean on context: "again", "same as before", "put it back". */
object Terse {
    private val again = Regex("(?i)^\\W*(again|do (that|it) again|one more time|same again|same as (before|last time)|once more)\\W*$")
    private val undo = Regex("(?i)^\\W*(undo( that)?|put (it|that) back|never ?mind,? put (it|that) back|take that back|revert( that)?)\\W*$")

    fun isAgain(t: String) = again.matches(t.trim())
    fun isUndo(t: String) = undo.matches(t.trim())

    /** "Again" becomes what he asked last, so the model and the tools see a full command. */
    fun expand(text: String, lastUser: String?): String =
        if (isAgain(text) && !lastUser.isNullOrBlank()) "$lastUser [Jon then said \"${text.trim()}\": do exactly that again]" else text
}

/** A rough text-only read of how Jon is doing, so tone can follow. Not a diagnosis, and never stored. */
object Mood {
    enum class Kind { NONE, STRESSED, LOW, UP }

    private val stressed = Regex("(?i)\\b(stressed|overwhelmed|swamped|panick\\w*|freaking out|so much to do|behind on|slammed|drowning)\\b")
    private val low = Regex("(?i)\\b(exhausted|so tired|wiped|burn(ed|t) out|can'?t focus|drained)\\b")
    private val up = Regex("(?i)\\b(awesome|finally|nailed it|let'?s go|hell yes|so excited|it worked)\\b")

    fun detect(text: String): Kind = when {
        stressed.containsMatchIn(text) -> Kind.STRESSED
        low.containsMatchIn(text) -> Kind.LOW
        up.containsMatchIn(text) -> Kind.UP
        else -> Kind.NONE
    }

    fun hint(text: String): String = when (detect(text)) {
        Kind.STRESSED -> "\n\nJon sounds stretched. Be calm, brief and steady: one clear next step, no jokes."
        Kind.LOW -> "\n\nJon sounds worn out. Be gentle and short; no cheerfulness quota."
        Kind.UP -> "\n\nJon sounds pleased. Match his energy lightly."
        Kind.NONE -> ""
    }
}

/** Personal shorthand Jon taught ("the usual" means...), kept in memory as "Shorthand: ..." lines. */
object Shorthand {
    private val line = Regex("^Shorthand: \"(.+?)\" means (.+)$")

    fun entry(phrase: String, meaning: String) = "Shorthand: \"${phrase.trim().take(60)}\" means ${meaning.trim().take(160)}"

    /** Memory lines whose phrase appears in what Jon just said. */
    fun hint(text: String, memory: List<String>): String {
        val t = text.lowercase()
        val hits = memory.mapNotNull { m -> line.find(m)?.let { it.groupValues[1] to it.groupValues[2] } }
            .filter { t.contains(it.first.lowercase()) }
        return if (hits.isEmpty()) "" else "\n\nJon's shorthand: " + hits.joinToString("; ") { "\"${it.first}\" means ${it.second}" } + "."
    }
}

/** How long Atlas keeps listening for a reply without the wake word after it speaks. */
object Conversation {
    fun followWindowMs(answer: String): Long = if (answer.trimEnd().endsWith("?")) 60_000L else 25_000L
}

/** Atlas's manner of speaking: dry, brief, loyal, never grovelling. */
object Manner {
    fun prompt(honorific: String): String {
        val h = honorific.ifBlank { "sir" }
        return "\nManner: dry, understated wit, used sparingly: never a joke in a serious moment. You may address Jon as \"$h\" now and then, not every line. " +
            "When you carry out a command, confirm in a few words (\"Done.\", \"Right away.\", \"Very good, $h.\") and stop. Don't narrate how you did it. " +
            "When something he's about to do looks risky, irreversible or at odds with what he told you, say so once, in one sentence with the reason, then do what he decides. " +
            "When a running joke, a firm preference or a decision with its reasons comes up, keep it with keep_moment, and bring it back naturally later, rarely.\n"
    }
}

/** Builds a whole model reply out of server-sent events, and hands finished sentences on as they appear. */
class StreamAssembler(private val onSentence: (String) -> Boolean) {
    private val blocks = JSONArray()
    private var type = ""
    private val text = StringBuilder()
    private var toolId = ""
    private var toolName = ""
    private val json = StringBuilder()
    private val pending = StringBuilder()
    private var halted = false
    var stopReason = ""
        private set
    var inTokens = 0
        private set
    var outTokens = 0
        private set

    /** All text of this reply so far, untrimmed, and how much of it has been passed to [onSentence]. */
    val fullText = StringBuilder()
    var consumed = 0
        private set

    fun remainder(): String = fullText.substring(consumed.coerceAtMost(fullText.length)).trim()

    fun feed(ev: JSONObject) {
        when (ev.optString("type")) {
            "message_start" -> inTokens = ev.optJSONObject("message")?.optJSONObject("usage")?.optInt("input_tokens") ?: 0
            "content_block_start" -> {
                val b = ev.optJSONObject("content_block") ?: JSONObject()
                type = b.optString("type")
                text.setLength(0); json.setLength(0)
                toolId = b.optString("id"); toolName = b.optString("name")
            }
            "content_block_delta" -> {
                val d = ev.optJSONObject("delta") ?: return
                when (d.optString("type")) {
                    "text_delta" -> {
                        val t = d.optString("text")
                        text.append(t); fullText.append(t); pending.append(t)
                        flush(false)
                    }
                    "input_json_delta" -> json.append(d.optString("partial_json"))
                }
            }
            "content_block_stop" -> {
                if (type == "text") {
                    flush(true)
                    blocks.put(JSONObject().put("type", "text").put("text", text.toString()))
                } else if (type == "tool_use") {
                    val input = if (json.isBlank()) JSONObject() else JSONObject(json.toString())
                    blocks.put(JSONObject().put("type", "tool_use").put("id", toolId).put("name", toolName).put("input", input))
                }
                type = ""
            }
            "message_delta" -> {
                val r = ev.optJSONObject("delta")?.optString("stop_reason").orEmpty()
                if (r.isNotEmpty()) stopReason = r
                outTokens = ev.optJSONObject("usage")?.optInt("output_tokens") ?: outTokens
            }
        }
    }

    private val ender = Regex("[.!?][\"')\\]]*\\s")

    private fun flush(final: Boolean) {
        if (halted) return
        while (true) {
            if (pending.length < 15) break
            val m = ender.find(pending, 13) ?: break               // skip very short openers ("Ok. ") so they ride with the next sentence
            val s = pending.substring(0, m.range.last + 1)
            if (!onSentence(s.trim())) { halted = true; return }
            consumed += s.length
            pending.delete(0, s.length)
        }
        if (final && pending.isNotBlank()) {
            val s = pending.toString()
            if (!onSentence(s.trim())) { halted = true; return }
            consumed += s.length
            pending.setLength(0)
        } else if (final) {
            consumed += pending.length
            pending.setLength(0)
        }
    }

    fun result(): JSONObject = JSONObject()
        .put("content", blocks).put("stop_reason", stopReason)
        .put("usage", JSONObject().put("input_tokens", inTokens).put("output_tokens", outTokens))
}

/** Speaks sentences one after another as they stream in, up to a word budget; the rest is spoken by the caller. */
class SpeechPipe(private val volume: Float, private val scope: CoroutineScope, private val wordCap: Int = 120) {
    private val q = Channel<String>(Channel.UNLIMITED)
    private var job: Job? = null
    private var words = 0
    @Volatile var used = false
    /** What was not streamed out of the final reply; null when nothing was streamed (speak the whole answer). */
    @Volatile var remainder: String? = null

    fun offer(sentence: String): Boolean {
        val s = sentence.trim()
        if (s.isEmpty()) return true
        val n = s.split(Regex("\\s+")).size
        if (words + n > wordCap) return false
        words += n
        used = true
        if (job == null) {
            job = scope.launch {
                Glasses.atlasState.value = "speaking"
                for (x in q) Speaker.say(x, volume, conversation = true)
            }
        }
        q.trySend(s)
        return true
    }

    suspend fun finish() {
        q.close()
        job?.join()
    }
}

/** Slowly learns how Jon talks (short bursts or full sentences) so replies fit him. Counts only; nothing he says is kept here. */
object Adapt {
    private const val KEY_N = "adaptN"; private const val KEY_W = "adaptW"; private const val KEY_T = "adaptT"

    fun note(text: String) {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        if (words == 0) return
        val sp = com.jondude.anzu.Prefs.sp
        sp.edit().putInt(KEY_N, sp.getInt(KEY_N, 0) + 1).putInt(KEY_W, sp.getInt(KEY_W, 0) + minOf(words, 60))
            .putInt(KEY_T, sp.getInt(KEY_T, 0) + if (words <= 4) 1 else 0).apply()
    }

    /** One line for the system prompt, or empty until there is enough to go on. Pure. */
    fun style(n: Int, totalWords: Int, terse: Int): String {
        if (n < 20) return ""
        val avg = totalWords.toFloat() / n
        val terseShare = terse.toFloat() / n
        return when {
            terseShare > 0.5f -> "\nHow Jon talks: mostly short commands. Keep replies tight and skip pleasantries."
            avg <= 7f -> "\nHow Jon talks: brief. Keep replies short."
            avg >= 16f -> "\nHow Jon talks: in full sentences, and he likes some depth. You can give more room."
            else -> ""
        }
    }

    fun line(): String {
        val sp = com.jondude.anzu.Prefs.sp
        return style(sp.getInt(KEY_N, 0), sp.getInt(KEY_W, 0), sp.getInt(KEY_T, 0))
    }
}
