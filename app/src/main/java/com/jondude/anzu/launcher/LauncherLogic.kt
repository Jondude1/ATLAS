package com.jondude.anzu.launcher

/**
 * Pure decisions for the launcher home screen, kept free of Android calls so they can be
 * unit-tested directly. Everything here only labels or sorts data the app already has;
 * nothing invents a value — unknowns come out as "unknown" / "not set up".
 */
object LauncherLogic {

    /** One launchable app for the drawer. */
    data class DrawerApp(val label: String, val pkg: String)

    /** Drawer order: no blank packages, one row per package, A–Z by label (case-insensitive). */
    fun sortDrawerApps(apps: List<DrawerApp>): List<DrawerApp> =
        apps.filter { it.pkg.isNotBlank() }
            .distinctBy { it.pkg }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))

    /**
     * Drawer search: a blank query keeps the full sorted list; otherwise keep apps whose
     * label (or package) contains the query, case-insensitive, preserving drawer order.
     */
    fun filterDrawerApps(apps: List<DrawerApp>, query: String): List<DrawerApp> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    /**
     * Battery saver from the spec: the orb animation pauses under 20% (the same threshold
     * the v1.2.15 battery producer watches). An unknown reading (-1) never pauses it.
     */
    fun orbAnimates(batteryPct: Int): Boolean = batteryPct < 0 || batteryPct >= 20

    /**
     * The brain tier line, mirroring Brain.reply's actual order: Hermes is primary when
     * READY (unless the on-device model is preferred), then the Anthropic key, then
     * the local model. "Ready" means the hardwired URL plus the hub-provisioned key —
     * the URL alone is always present now and must not claim the tier before the key
     * lands. Hermes is still named when ready-but-not-primary, since Brain.reply can
     * hand jobs to it.
     */
    fun brainTierLabel(
        hermesReady: Boolean,
        onDevicePreferred: Boolean,
        hasApiKey: Boolean,
        hermesModel: String,
        claudeModel: String,
        localConfigured: Boolean,
        localModel: String
    ): String = when {
        hermesReady && !onDevicePreferred ->
            "HERMES · " + hermesModel.trim().ifBlank { "hermes-agent" }
        hasApiKey ->
            "CLAUDE · " + claudeModel.trim().ifBlank { "claude-sonnet-5-5" }
        localConfigured ->
            "LOCAL · " + localModel.trim().ifBlank { "atlas:14b" }
        hermesReady ->
            "HERMES · " + hermesModel.trim().ifBlank { "hermes-agent" }
        else -> "BRAIN NOT SET UP"
    }

    /**
     * Connectivity classification. Every hardwired service lives on the tailnet, so hub
     * AND Hermes both down means the phone is off-tailnet — a Tailscale problem, not two
     * service failures. One down is that service; either still unchecked is CHECKING.
     */
    fun linkState(hubUp: Boolean?, hermesUp: Boolean?): String = when {
        hubUp == null || hermesUp == null -> "CHECKING"
        !hubUp && !hermesUp -> "TAILNET_DOWN"
        !hubUp -> "HUB_DOWN"
        !hermesUp -> "HERMES_DOWN"
        else -> "UP"
    }

    /** The connectivity line for a [linkState] code. */
    fun linkLabel(state: String): String = when (state) {
        "TAILNET_DOWN" -> "TAILNET — DOWN · CHECK TAILSCALE"
        "HUB_DOWN" -> "HUB — OFFLINE"
        "HERMES_DOWN" -> "HERMES — OFFLINE"
        "CHECKING" -> "LINK — CHECKING…"
        else -> "TAILNET — UP"
    }

    /**
     * Header house line from (entityId, state) pairs out of Home Assistant. Only person.*
     * entities count. No person entities means unknown — never an assumed "someone home"
     * (Producers.anyoneHome makes that assumption for alert suppression, not for display).
     */
    fun houseModeLabel(entities: List<Pair<String, String>>): String {
        val people = entities.filter { it.first.startsWith("person.") && it.second.isNotBlank() }.map { it.second }
        return when {
            people.isEmpty() -> "House · unknown"
            people.any { it == "home" } -> "Someone home"
            else -> "Nobody home"
        }
    }

    /**
     * The WAKE diagnostics label (v1.2.17). The wake word being switched on is not the
     * same as it listening: without the microphone permission it can never hear, and the
     * listener itself pauses for music, calls and quiet time — those read as PAUSED.
     * Only the phone-mic route (micSource 2) earns the full "LISTENING · PHONE MIC";
     * any other live route is just LISTENING. [listenerState] is WakeListener's own
     * status line ("listening · …", "paused: …", "off", …).
     */
    fun wakeLabel(enabled: Boolean, micGranted: Boolean, listenerState: String, micSource: Int): String = when {
        !enabled -> "OFF"
        !micGranted -> "NO MIC PERMISSION"
        listenerState.startsWith("listening") && micSource == 2 -> "LISTENING · PHONE MIC"
        listenerState.startsWith("listening") -> "LISTENING"
        else -> "PAUSED"
    }

    /**
     * Whether the latest response still needs Jon's eyes (Jon, v1.2.17): its speech was
     * withheld by the headphones-only gate, so the short home line is all he has seen —
     * the full text stays on offer until he opens it. [readText] is the full text he
     * last opened in the detail view. A response that was spoken aloud never needs
     * reading, and one he already opened stops asking.
     */
    fun responseNeedsReading(fullText: String, speechSuppressed: Boolean, readText: String): Boolean =
        speechSuppressed && fullText.isNotBlank() && fullText != readText

    /** Hub reachability line: null while the first check is still running. */
    fun hubLabel(hubUp: Boolean?): String = when (hubUp) {
        true -> "Hub · connected"
        false -> "Hub · offline"
        null -> "Hub · checking…"
    }

    /**
     * A stale label for cached card data, or null while it is fresh. Anything five minutes
     * or older is stale; the launcher re-fetches once a minute while it is on screen.
     */
    fun staleText(ageMs: Long): String? =
        if (ageMs < 5 * 60_000L) null else "Stale · ${ageMs / 60_000} min old"
}
