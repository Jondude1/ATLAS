package com.jondude.anzu.assist

/**
 * "Make me a shelf bracket" (v1.2.20): a spoken build ask that becomes a hub CAD job.
 * Deterministic and conservative, like PutUpIntents — this runs before the brain tiers,
 * so a false positive steals a conversation turn. When in doubt: null, and the ask
 * flows on to the model untouched.
 */
object CadIntents {

    private val leadIns = listOf(
        "make me an ", "make me a ", "make an ", "make a ",
        "build me an ", "build me a ", "build an ", "build a ",
        "print me an ", "print me a ", "print an ", "print a ",
        "model an ", "model a ", "create an ", "create a ",
        "make me ", "build me ", "print me "
    )
    private val questionStarts = setOf("can", "could", "would", "how", "what", "why", "is", "does", "do")
    private val notBuilds = setOf(
        "note", "list", "call", "text", "message", "reminder", "email", "playlist", "song",
        "joke", "story", "plan", "reservation", "appointment", "coffee", "tea", "sandwich",
        "photo", "picture", "screenshot", "recording", "summary", "report"
    )
    private val stopWords = setOf("with", "that", "for", "and", "then", "in", "on", "to", "of")

    /** The build prompt to send the hub (the original utterance, trimmed), or null when this isn't a build ask. */
    fun match(text: String): String? {
        val original = text.trim()
        val t = original.lowercase()
        if (t.isEmpty() || t.endsWith("?")) return null
        if (t.substringBefore(' ') in questionStarts) return null
        val lead = leadIns.firstOrNull { t.startsWith(it) } ?: return null
        val words = t.removePrefix(lead).split(Regex("\\s+"))
            .map { it.trim(',', '.', '!', ';', ':') }.filter { it.isNotEmpty() }
        if (words.size < 2) return null
        // The object phrase runs up to the first connector ("shelf bracket" from "shelf bracket with two holes").
        val phrase = words.takeWhile { it !in stopWords }
        if (phrase.isEmpty() || phrase.any { it in notBuilds }) return null
        return original
    }
}
