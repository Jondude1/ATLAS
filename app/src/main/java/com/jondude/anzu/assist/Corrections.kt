package com.jondude.anzu.assist

/** Spots when Jon is correcting Atlas, so the lesson can be kept instead of repeated. Pure, so it can be tested. */
object Corrections {
    private val patterns = listOf(
        Regex("(?i)\\bi meant\\b"),
        Regex("(?i)\\bi said\\b"),
        Regex("(?i)\\bthat'?s not what i\\b"),
        Regex("(?i)\\bthat is not what i\\b"),
        Regex("(?i)\\bnot what i (asked|meant|said)\\b"),
        Regex("(?i)^\\s*no[,.!]?\\s+(it'?s|its|i|that|not|the)\\b"),
        Regex("(?i)\\byou (got|heard) (that|it) wrong\\b"),
        Regex("(?i)\\bwrong[,.]\\s")
    )

    fun detect(text: String): Boolean = text.isNotBlank() && patterns.any { it.containsMatchIn(text) }

    /** Added to the system prompt for a turn where Jon is correcting something. */
    fun hint(text: String): String =
        if (!detect(text)) "" else
            "\n\nJon appears to be correcting you. Work out what you got wrong. If it is a lasting thing (a name you misheard, a preference, a fact, how he wants something done), call learn_correction with what you had wrong and what is right, then carry on with the corrected answer. Don't make a fuss, and don't save one-off slips."
}
