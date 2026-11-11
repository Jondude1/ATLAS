package com.jondude.anzu.assist

import com.jondude.anzu.ATHENA
import com.jondude.anzu.OBELISK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every web service Jon runs that answers over the tailnet, built in (probed live by Helios, 2026-11-11).
 * A health check is a plain GET with no credentials: any HTTP answer below 500 means the service is up
 * (a 401 means up but wants a key). Nothing here ever sends or stores a key.
 */
object Systems {
    data class Service(val name: String, val machine: String, val host: String, val port: Int, val path: String = "/", val what: String) {
        val url get() = "http://$host:$port"
        val checkUrl get() = url + path
    }

    data class Check(val service: Service, val up: Boolean, val code: Int)

    val all = listOf(
        Service("The hub", "Obelisk", OBELISK, 8765, "/atlas/status", "ATLAS's hub: reports, briefs, Athena"),
        Service("Ollama", "Obelisk", OBELISK, 11434, "/api/tags", "local models"),
        Service("Home Assistant", "Home Assistant", "100.68.172.113", 80, "/", "the house"),
        Service("Jellyfin", "Athena", ATHENA, 8096, "/health", "movies and TV"),
        Service("Audiobookshelf", "Athena", ATHENA, 13378, "/healthcheck", "audiobooks and ebooks"),
        Service("Frigate", "Athena", ATHENA, 5000, "/api/version", "cameras"),
        Service("Jellyseerr", "Athena", ATHENA, 5056, "/", "media requests"),
        Service("Jellyseerr (main)", "Athena", ATHENA, 5057, "/", "media requests"),
        Service("Overseerr", "Athena", ATHENA, 5055, "/", "media requests"),
        Service("Nextcloud", "Athena", ATHENA, 8181, "/", "files"),
        Service("Node-RED", "Athena", ATHENA, 1880, "/", "automations"),
        Service("ESPHome", "Athena", ATHENA, 6052, "/", "ESP devices"),
        Service("Radarr", "Athena", ATHENA, 7878, "/", "movies"),
        Service("Sonarr", "Athena", ATHENA, 8989, "/", "TV shows"),
        Service("Lidarr", "Athena", ATHENA, 8686, "/", "music"),
        Service("Prowlarr", "Athena", ATHENA, 9696, "/", "indexers"),
        Service("LazyLibrarian", "Athena", ATHENA, 5299, "/", "books")
    )

    fun find(name: String): Service? {
        val q = name.trim().lowercase()
        return all.firstOrNull { it.name.lowercase() == q } ?: all.firstOrNull { q in it.name.lowercase() || it.name.lowercase() in q }
    }

    private fun probe(s: Service): Check = try {
        val c = URL(s.checkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 4000; c.instanceFollowRedirects = false
        val code = c.responseCode
        c.disconnect()
        Check(s, code in 100..499, code)
    } catch (e: Exception) {
        Check(s, false, -1)
    }

    /** Checks the given services in parallel. */
    suspend fun check(services: List<Service> = all): List<Check> = withContext(Dispatchers.IO) {
        coroutineScope { services.map { s -> async { probe(s) } }.awaitAll() }
    }

    /** The state in a sentence, as ATLAS would say it. */
    fun sentence(checks: List<Check>): String {
        if (checks.isEmpty()) return "I haven't checked your systems yet."
        val down = checks.filter { !it.up }
        return when {
            down.isEmpty() -> "Everything's answering: all ${checks.size} services are up."
            down.size == checks.size -> "I can't reach any of your services. Is Tailscale on?"
            down.size == 1 -> "All up except ${down[0].service.name} on ${down[0].service.machine}."
            else -> "${down.size} of ${checks.size} aren't answering: ${down.take(4).joinToString { it.service.name }}${if (down.size > 4) "…" else ""}."
        }
    }
}
