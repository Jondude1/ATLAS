package com.jondude.anzu.assist

/**
 * "Put it up" ask intents (v1.2.18): the show-asks that become overlay tiles, matched in
 * the converse path BEFORE any model call, alongside the other quick actions. Pure and
 * unit-tested: the matcher only decides; Brain does the fetching and the putting-up.
 *
 * Matching is deliberately narrow — anchored token patterns, not loose keyword hits —
 * so an ordinary question that merely mentions a camera or the brief flows on to the
 * model unchanged. Guards reject the classic near-misses (weather on the porch, camera
 * settings, the camera APP, photos).
 */
object PutUpIntents {

    sealed interface Result {
        /** One camera; [query] is the name-ish text to resolve against the real cameras ("" = unspecified). */
        data class Camera(val query: String) : Result

        /** Every camera at once (the multi-tile layout). */
        object CamerasAll : Result

        object Brief : Result
        object House : Result
        object NextEvent : Result
        object None : Result
    }

    private val showVerbs = setOf("show", "put", "bring", "display", "open")
    private val briefWords = setOf("show", "put", "bring", "display", "open", "me", "the", "my", "up", "brief")
    private val houseWords = setOf("show", "display", "open", "put", "me", "the", "up", "house")
    private val nextWords = setOf("show", "display", "me", "my", "the", "next", "event", "up")

    /** Words that make a camera-sounding ask NOT a put-up: he's asking about something else, or for an app. */
    private val cameraGuards = listOf("weather", "forecast", "temperature", "photo", "picture", "setting", "app")

    /** Name words too generic to identify a camera on their own ("front", "back"…). */
    private val genericNameWords = setOf("front", "back", "side", "main", "the", "camera", "cam", "top", "upper", "lower")

    /** Lowercase, punctuation stripped, whitespace collapsed, a leading "atlas" / trailing "please" removed. */
    fun normalize(text: String): String {
        var t = text.lowercase().replace("'", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("hey atlas ").removePrefix("okay atlas ").removePrefix("ok atlas ").removePrefix("atlas ")
        t = t.removeSuffix(" please").removePrefix("please ")
        return t.trim()
    }

    /**
     * Cheap gate so Brain only pays for a camera-names fetch when an ask could possibly
     * be a put-up: it starts with a show verb, or it is a "what's next" shape.
     */
    fun mightMatch(text: String): Boolean {
        val t = normalize(text)
        if (t == "whats next" || t == "what is next") return true
        val first = t.substringBefore(' ')
        return first in showVerbs
    }

    /**
     * The full match. [cameraNames] are the display names of the cameras the app actually
     * has (empty when Home Assistant is unset) — "show me the front porch" can only match
     * a camera that exists.
     */
    fun match(text: String, cameraNames: List<String>): Result {
        val t = normalize(text)
        if (t.isBlank()) return Result.None
        val tokens = t.split(' ')

        // All cameras: a show verb + the plural, nothing else of substance.
        if (Regex("^(show|put|bring|display|open)( me)?( up)?( all)?( the)? cameras( up)?$").matches(t)) return Result.CamerasAll

        // The brief / the house / next event: every token must belong to the phrase.
        if ("brief" in tokens && tokens.all { it in briefWords } && tokens.first() in showVerbs) return Result.Brief
        if ("house" in tokens && tokens.all { it in houseWords } && tokens.first() in showVerbs) return Result.House
        if (t in setOf("whats next", "what is next", "show me whats next", "show me what is next")) return Result.NextEvent
        if ("next" in tokens && tokens.all { it in nextWords } && tokens.first() in showVerbs) return Result.NextEvent

        // Cameras. Guards first: weather, photos, settings and the camera app are not put-ups.
        if (cameraGuards.any { g -> tokens.any { it.startsWith(g) } }) return Result.None

        // A real camera's name, said with a show verb (or the word camera): "show me the front porch".
        val named = cameraNames.filter { name ->
            val n = normalize(name)
            n.isNotBlank() && (t.contains(n) || distinctiveMatch(t, n))
        }
        if (named.isNotEmpty() && (tokens.first() in showVerbs || "camera" in tokens || "cameras" in tokens)) {
            // Prefer the longest (most specific) matching name.
            return Result.Camera(named.maxByOrNull { normalize(it).length }!!)
        }

        // Generic "<something> camera": "put the porch camera up", "show me the garage camera".
        Regex("^(show|put|bring|display)( me)?( up)?( the)? (.+?) cameras?( up)?$").find(t)?.let { m ->
            return Result.Camera(m.groupValues[5].trim())
        }
        // Bare camera: "show me the camera", "put the camera up".
        if (Regex("^(show|put|bring|display)( me)?( up)?( the)? cameras?( up)?$").matches(t)) return Result.Camera("")

        return Result.None
    }

    /** True when every distinctive word of the camera [name] appears in [t] ("porch" identifies Front Porch). */
    private fun distinctiveMatch(t: String, name: String): Boolean {
        val distinctive = name.split(' ').filter { it !in genericNameWords }
        if (distinctive.isEmpty()) return false
        val words = t.split(' ').toSet()
        return distinctive.all { it in words }
    }

    /**
     * Resolve a [Result.Camera] query against the real cameras: an exact/full name hit,
     * else a unique distinctive-word hit, else -1. A blank query resolves only when
     * there is exactly one camera to mean.
     */
    fun resolveCamera(query: String, cameras: List<HomeAssistant.HaCamera>): Int {
        if (cameras.isEmpty()) return -1
        val q = normalize(query)
        if (q.isBlank()) return if (cameras.size == 1) 0 else -1
        cameras.forEachIndexed { i, cam ->
            val n = normalize(cam.name)
            if (n == q || n.contains(q) || q.contains(n)) return i
        }
        val hits = cameras.indices.filter { distinctiveMatch(q, normalize(cameras[it].name)) }
        return if (hits.size == 1) hits[0] else -1
    }
}
