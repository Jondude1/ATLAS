package com.jondude.anzu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jondude.anzu.assist.Hub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hermes hardwiring (2026-11-20): the address and model are built in, the key is not —
 * it must still be provisioned from the hub even though the URL is now preset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesHardwireTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Prefs.init(ctx)
        Prefs.sp.edit().clear().commit()
    }

    @Test
    fun hermesDefaultsAreTheHardwiredAthenaTailnetAddress() {
        assertEquals("http://100.67.98.121:8642/v1", HERMES_URL)
        assertEquals(HERMES_URL, Prefs.hermesUrl)
        assertEquals("hermes-agent", Prefs.hermesModel)
        // The key is never a default and never in source: it starts blank.
        assertEquals("", Prefs.hermesKey)
    }

    @Test
    fun migrationForcesHardwiredUrlOverSavedBlankOrTyped() {
        Prefs.sp.edit().putString("hermesUrl", "http://example.test:9999/v1").commit()
        Prefs.sp.edit().putString("hermesModel", "").commit()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Prefs.init(ctx) // migration flags were wiped by the clear in setUp, so migrations rerun
        assertEquals(HERMES_URL, Prefs.hermesUrl)
        assertEquals("hermes-agent", Prefs.hermesModel)
    }

    @Test
    fun claimFiresWithPresetUrlAndBlankKey() {
        // The regression this guards: Hermes.configured() is true from the preset URL,
        // so a URL-based gate would never provision the key.
        Prefs.hubToken = "test-token"
        Prefs.hermesKey = ""
        Prefs.hermesProvisionTriedAt = 0L
        val now = System.currentTimeMillis()
        assertTrue(Hub.claimProvisionAttempt(now))
        // The attempt is stamped, so an immediate second claim is throttled.
        assertEquals(now, Prefs.hermesProvisionTriedAt)
        assertFalse(Hub.claimProvisionAttempt(now))
        // After the 6h throttle window it may try again.
        assertTrue(Hub.claimProvisionAttempt(now + 7 * 60 * 60 * 1000L))
    }

    @Test
    fun failedAttemptBacksOffSecondsNotHours() {
        Prefs.hubToken = "test-token"
        Prefs.hermesKey = ""
        Prefs.hermesProvisionTriedAt = 0L
        val now = System.currentTimeMillis()
        assertTrue(Hub.claimProvisionAttempt(now))
        Hub.releaseFailedProvisionAttempt(now)
        assertFalse(Hub.claimProvisionAttempt(now + 30_000L))
        assertTrue(Hub.claimProvisionAttempt(now + 61_000L))
        // A failure at +61s resets the short backoff again; still nowhere near 6h.
        Hub.releaseFailedProvisionAttempt(now + 61_000L)
        assertFalse(Hub.claimProvisionAttempt(now + 61_000L + 30_000L))
        assertTrue(Hub.claimProvisionAttempt(now + 61_000L + 61_000L))
    }

    @Test
    fun changingHubTokenRearmsProvisioningInsideTheWindow() {
        Prefs.hubToken = "mistyped-token"
        Prefs.hermesKey = ""
        Prefs.hermesProvisionTriedAt = 0L
        val now = System.currentTimeMillis()
        assertTrue(Hub.claimProvisionAttempt(now))
        assertFalse(Hub.claimProvisionAttempt(now + 1_000L))
        Prefs.hubToken = "corrected-token"
        assertTrue(Hub.claimProvisionAttempt(now + 2_000L))
        // The new hash was recorded, so the corrected token is throttled like any other.
        assertFalse(Hub.claimProvisionAttempt(now + 3_000L))
        // Only a hash is stored, never the token itself.
        assertFalse(Prefs.hermesProvisionTokenHash.contains("corrected-token"))
        assertEquals(64, Prefs.hermesProvisionTokenHash.length)
    }

    @Test
    fun unchangedTokenWithRecentAttemptStillThrottlesForSixHours() {
        Prefs.hubToken = "test-token"
        Prefs.hermesKey = ""
        Prefs.hermesProvisionTriedAt = 0L
        val now = System.currentTimeMillis()
        assertTrue(Hub.claimProvisionAttempt(now))
        assertFalse(Hub.claimProvisionAttempt(now + 5 * 60 * 60 * 1000L))
        assertTrue(Hub.claimProvisionAttempt(now + 6 * 60 * 60 * 1000L + 1L))
    }

    @Test
    fun claimRefusesWhenKeyPresentOrTokenMissing() {
        val now = System.currentTimeMillis()
        // Key already landed: no claim, and no attempt stamp is consumed.
        Prefs.hubToken = "test-token"
        Prefs.hermesKey = "provisioned-key"
        Prefs.hermesProvisionTriedAt = 0L
        assertFalse(Hub.claimProvisionAttempt(now))
        assertEquals(0L, Prefs.hermesProvisionTriedAt)
        // No hub token: the hub would refuse the config anyway.
        Prefs.hermesKey = ""
        Prefs.hubToken = ""
        assertFalse(Hub.claimProvisionAttempt(now))
        assertEquals(0L, Prefs.hermesProvisionTriedAt)
    }
}
