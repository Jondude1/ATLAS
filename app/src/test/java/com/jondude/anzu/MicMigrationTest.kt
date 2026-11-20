package com.jondude.anzu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher phone-mic migration (Jon, v1.2.17): "it needs to listen in the phone."
 * The one-time launcherMicV1 migration points the voice loop at the phone's own mics,
 * exactly once — a mic source Jon later picks in Settings is his and survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicMigrationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Prefs.init(ctx)
        Prefs.sp.edit().clear().commit()
    }

    @Test
    fun migrationSetsPhoneMicOnceAndSettingsChoiceSurvives() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Fresh state (flags wiped in setUp): micSource is at its Auto default.
        assertEquals(0, Prefs.micSource)
        // First init under v1.2.17: the migration fires and picks the phone mic.
        Prefs.init(ctx)
        assertEquals(2, Prefs.micSource)
        // Jon later picks a different source in Settings (e.g. back to Auto)…
        Prefs.micSource = 0
        // …and re-init must NOT stomp it: the migration already fired its one time.
        Prefs.init(ctx)
        assertEquals(0, Prefs.micSource)
    }

    @Test
    fun headphonesOnlySpeechDefaultsOnAndChoiceSurvives() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Jon's rule is the default: speech only into earphones/headphones.
        assertTrue(Prefs.headphonesOnlySpeech)
        // Turning it off is a real choice, not something init resets.
        Prefs.headphonesOnlySpeech = false
        Prefs.init(ctx)
        assertFalse(Prefs.headphonesOnlySpeech)
    }
}
