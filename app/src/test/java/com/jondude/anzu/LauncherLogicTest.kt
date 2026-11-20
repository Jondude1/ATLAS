package com.jondude.anzu

import com.jondude.anzu.launcher.LauncherLogic
import com.jondude.anzu.launcher.LauncherLogic.DrawerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure launcher decisions: sorting, battery pause, tier and house labels, stale text. */
class LauncherLogicTest {

    @Test
    fun drawerSortsAlphabeticallyAndDedupes() {
        val apps = listOf(
            DrawerApp("zebra", "com.z"),
            DrawerApp("Apple", "com.a"),
            DrawerApp("apple duplicate", "com.a"),
            DrawerApp("No package", ""),
            DrawerApp("Mango", "com.m")
        )
        assertEquals(
            listOf(DrawerApp("Apple", "com.a"), DrawerApp("Mango", "com.m"), DrawerApp("zebra", "com.z")),
            LauncherLogic.sortDrawerApps(apps)
        )
    }

    @Test
    fun drawerFilterMatchesLabelAndPackage() {
        val apps = LauncherLogic.sortDrawerApps(
            listOf(
                DrawerApp("Home Assistant", "io.homeassistant.companion.android"),
                DrawerApp("Muse", "ai.muse.app"),
                DrawerApp("Camera", "com.android.camera2"),
                DrawerApp("Calendar", "com.google.android.calendar")
            )
        )
        // Blank query = the whole drawer.
        assertEquals(apps, LauncherLogic.filterDrawerApps(apps, "  "))
        // Label match, case-insensitive.
        assertEquals(listOf(DrawerApp("Camera", "com.android.camera2")), LauncherLogic.filterDrawerApps(apps, "cam"))
        assertEquals(listOf(DrawerApp("Muse", "ai.muse.app")), LauncherLogic.filterDrawerApps(apps, "MUSE"))
        // Package match.
        assertEquals(
            listOf(DrawerApp("Home Assistant", "io.homeassistant.companion.android")),
            LauncherLogic.filterDrawerApps(apps, "homeassistant")
        )
        // No match = empty, never a fabricated entry.
        assertTrue(LauncherLogic.filterDrawerApps(apps, "zzz").isEmpty())
        // Filtering preserves alphabetical order.
        assertEquals(
            listOf("Calendar", "Camera"),
            LauncherLogic.filterDrawerApps(apps, "ca").map { it.label }
        )
    }

    @Test
    fun orbPausesUnderTwentyPercent() {
        assertFalse(LauncherLogic.orbAnimates(19))
        assertFalse(LauncherLogic.orbAnimates(0))
        assertTrue(LauncherLogic.orbAnimates(20))
        assertTrue(LauncherLogic.orbAnimates(85))
        // Unknown reading never pauses the core.
        assertTrue(LauncherLogic.orbAnimates(-1))
    }

    @Test
    fun brainTierMirrorsReplyOrder() {
        // Hermes primary when configured and on-device not preferred.
        assertEquals(
            "HERMES · hermes-agent",
            LauncherLogic.brainTierLabel(true, false, true, "", "claude-x", true, "atlas:14b")
        )
        // On-device preferred drops Hermes from primary; the API key answers.
        assertEquals(
            "CLAUDE · claude-x",
            LauncherLogic.brainTierLabel(true, true, true, "hermes-agent", "claude-x", true, "atlas:14b")
        )
        // Local only.
        assertEquals(
            "LOCAL · atlas:14b",
            LauncherLogic.brainTierLabel(false, false, false, "", "", true, "")
        )
        // Nothing configured: honest, not a guessed tier.
        assertEquals(
            "BRAIN NOT SET UP",
            LauncherLogic.brainTierLabel(false, false, false, "", "", false, "")
        )
    }

    @Test
    fun houseModeFromPersonEntitiesOnly() {
        assertEquals("House · unknown", LauncherLogic.houseModeLabel(emptyList()))
        assertEquals(
            "House · unknown",
            LauncherLogic.houseModeLabel(listOf("lock.front" to "locked", "person.jon" to ""))
        )
        assertEquals(
            "Someone home",
            LauncherLogic.houseModeLabel(listOf("person.jon" to "not_home", "person.jenna" to "home"))
        )
        assertEquals(
            "Nobody home",
            LauncherLogic.houseModeLabel(listOf("person.jon" to "not_home", "person.jenna" to "away"))
        )
    }

    @Test
    fun hubLabelStates() {
        assertEquals("Hub · checking…", LauncherLogic.hubLabel(null))
        assertEquals("Hub · connected", LauncherLogic.hubLabel(true))
        assertEquals("Hub · offline", LauncherLogic.hubLabel(false))
    }

    @Test
    fun linkStateClassifiesTailnetDown() {
        // Both hardwired services down = the phone is off-tailnet, not two failures.
        assertEquals("TAILNET_DOWN", LauncherLogic.linkState(false, false))
        assertEquals("TAILNET — DOWN · CHECK TAILSCALE", LauncherLogic.linkLabel("TAILNET_DOWN"))
        // Single-service failures stay single.
        assertEquals("HUB_DOWN", LauncherLogic.linkState(false, true))
        assertEquals("HERMES_DOWN", LauncherLogic.linkState(true, false))
        assertEquals("HUB — OFFLINE", LauncherLogic.linkLabel("HUB_DOWN"))
        assertEquals("HERMES — OFFLINE", LauncherLogic.linkLabel("HERMES_DOWN"))
        // Either side unchecked = still checking, never a premature verdict.
        assertEquals("CHECKING", LauncherLogic.linkState(null, false))
        assertEquals("CHECKING", LauncherLogic.linkState(true, null))
        assertEquals("CHECKING", LauncherLogic.linkState(null, null))
        assertEquals("UP", LauncherLogic.linkState(true, true))
        assertEquals("TAILNET — UP", LauncherLogic.linkLabel("UP"))
    }

    @Test
    fun staleTextAfterFiveMinutes() {
        assertNull(LauncherLogic.staleText(0))
        assertNull(LauncherLogic.staleText(4 * 60_000L))
        assertEquals("Stale · 5 min old", LauncherLogic.staleText(5 * 60_000L))
        assertEquals("Stale · 47 min old", LauncherLogic.staleText(47 * 60_000L + 30_000L))
    }
}
