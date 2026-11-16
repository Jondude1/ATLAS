package com.jondude.anzu.assist

import com.jondude.anzu.ATHENA
import com.jondude.anzu.HELIOS_RELAY
import com.jondude.anzu.OBELISK_HUB
import com.jondude.anzu.OLLAMA
import com.jondude.anzu.Prefs
import com.jondude.anzu.tailnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Can the phone reach home? Every target is a Tailscale address, so when all of them are silent the phone's
 * Tailscale link is the likely cause. Probes run on Dispatchers.IO with 3 s timeouts, in parallel, and never
 * touch Brain, voice or the overlay; they send nothing but a bare GET (or open and close a socket).
 */
object Links {
    enum class State { REACHABLE, HTTP_ERROR, UNREACHABLE }

    data class Target(val id: String, val name: String, val address: String)

    data class Result(val target: Target, val state: State, val ms: Long, val detail: String)

    const val TIMEOUT_MS = 3000

    const val TAILSCALE_DOWN =
        "Tailscale looks down. Atlas reaches home over Tailscale — open the Tailscale app and check it shows Connected. " +
            "If another VPN (like Mullvad) is on, it may be holding the phone's VPN slot."
    const val ALL_UP = "All links up."
    const val STRIP = "No link home — Tailscale looks down. Check the Tailscale app."

    private val _results = MutableStateFlow<List<Result>?>(null)
    /** The latest probe round, or null before the first one finishes. */
    val results: StateFlow<List<Result>?> = _results

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** Pure: the one-sentence verdict. Only UNREACHABLE counts as down; an HTTP error still proves the link. */
    fun verdict(results: List<Result>): String {
        if (results.isEmpty()) return "Checking the links home…"
        val down = results.filter { it.state == State.UNREACHABLE }
        return when {
            down.isEmpty() -> ALL_UP
            down.size == results.size -> TAILSCALE_DOWN
            else -> "${join(down.map { it.target.name })} ${if (down.size == 1) "is" else "are"} not answering; the rest are up."
        }
    }

    /** Pure: true only when every target is unreachable (and at least one was checked). */
    fun allDown(results: List<Result>?): Boolean =
        !results.isNullOrEmpty() && results.all { it.state == State.UNREACHABLE }

    private fun join(names: List<String>): String = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    private fun targets(): List<Pair<Target, suspend () -> Result>> {
        val ha = Prefs.haUrl.trim()
        val hub = Prefs.obeliskUrl.trim().trimEnd('/') + "/atlas/status"
        val ollama = Prefs.ollamaUrl.trim().trimEnd('/') + "/api/tags"
        return listOf(
            Target("ha", "Home Assistant", ha).let { it to suspend { http(it, ha, anyAnswer = true) } },
            Target("hub", "Obelisk hub", hub).let { it to suspend { http(it, hub, anyAnswer = false) } },
            Target("ollama", "Ollama", ollama).let { it to suspend { http(it, ollama, anyAnswer = false) } },
            Target("athena", "Athena", ATHENA).let { it to suspend { tcp(it, ATHENA, 8096) } },
            Target("relay", "Helios relay", HELIOS_RELAY).let { it to suspend { http(it, HELIOS_RELAY, anyAnswer = true) } }
        )
    }

    /** Probes every target in parallel and publishes the round. Safe to call from any thread. */
    suspend fun check(): List<Result> = withContext(Dispatchers.IO) {
        _checking.value = true
        try {
            val r = coroutineScope { targets().map { (_, probe) -> async { probe() } }.awaitAll() }
            _results.value = r
            r
        } finally {
            _checking.value = false
        }
    }

    /** HTTP: any answer proves the link. With [anyAnswer] false (hub, Ollama) a 4xx/5xx is flagged HTTP_ERROR. */
    private fun http(t: Target, url: String, anyAnswer: Boolean): Result {
        val start = System.nanoTime()
        return try {
            val c = URL(tailnet(url)).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS; c.instanceFollowRedirects = false
            val code = try { c.responseCode } finally { c.disconnect() }
            val ms = (System.nanoTime() - start) / 1_000_000
            if (code <= 0) Result(t, State.UNREACHABLE, ms, "no answer")
            else if (anyAnswer || code < 400) Result(t, State.REACHABLE, ms, "HTTP $code")
            else Result(t, State.HTTP_ERROR, ms, "HTTP $code")
        } catch (e: Exception) {
            Result(t, State.UNREACHABLE, (System.nanoTime() - start) / 1_000_000, e.javaClass.simpleName)
        }
    }

    /** TCP: a completed connect, or a refusal (the host itself answered), proves the link; a timeout does not. */
    private fun tcp(t: Target, host: String, port: Int): Result {
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
            Result(t, State.REACHABLE, ms(), "port $port open")
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", true) == true) Result(t, State.REACHABLE, ms(), "host answered")
            else Result(t, State.UNREACHABLE, ms(), "ConnectException")
        } catch (e: Exception) {
            Result(t, State.UNREACHABLE, ms(), e.javaClass.simpleName)
        }
    }
}
