package com.jondude.anzu

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The headphones-only speech gate (Jon, v1.2.17): Atlas speaks out loud only into
 * earphones/headphones. The accepted device types are pinned exactly — adding a
 * speaker-like type here would silently break the rule.
 */
class SpeakerGateTest {

    @Test
    fun personalTypeSetIsPinnedExactly() {
        // The literal set, pinned: wired headset/headphones, BT SCO/A2DP, USB, BLE.
        assertEquals(setOf(3, 4, 7, 8, 22, 26), SpeakerGate.PERSONAL_TYPES)
        // And the named constants are the real AudioDeviceInfo codes.
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, SpeakerGate.WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, SpeakerGate.WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, SpeakerGate.BT_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, SpeakerGate.BT_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, SpeakerGate.USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, SpeakerGate.BLE_HEADSET)
    }

    @Test
    fun wiredHeadphonesAllowSpeech() {
        assertTrue(SpeakerGate.maySpeak(setOf(SpeakerGate.WIRED_HEADPHONES), headphonesOnly = true))
        assertTrue(SpeakerGate.maySpeak(setOf(SpeakerGate.WIRED_HEADSET), headphonesOnly = true))
    }

    @Test
    fun bluetoothOutputsAllowSpeech() {
        assertTrue(SpeakerGate.maySpeak(setOf(SpeakerGate.BT_A2DP), headphonesOnly = true))
        assertTrue(SpeakerGate.maySpeak(setOf(SpeakerGate.BT_SCO), headphonesOnly = true))
    }

    @Test
    fun usbAndBleHeadsetsAllowSpeech() {
        assertTrue(SpeakerGate.maySpeak(setOf(SpeakerGate.USB_HEADSET), headphonesOnly = true))
        assertTrue(SpeakerGate.maySpeak(setOf(SpeakerGate.BLE_HEADSET), headphonesOnly = true))
    }

    @Test
    fun speakerAndEarpieceDoNotCount() {
        // Built-in speaker (2) and earpiece (1) are "the room", not his ears.
        assertFalse(SpeakerGate.maySpeak(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), headphonesOnly = true))
        assertFalse(SpeakerGate.maySpeak(setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), headphonesOnly = true))
        // A speaker alongside headphones still speaks — the personal device wins.
        assertTrue(
            SpeakerGate.maySpeak(
                setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, SpeakerGate.BT_A2DP),
                headphonesOnly = true
            )
        )
    }

    @Test
    fun noOutputsBlockSpeech() {
        assertFalse(SpeakerGate.maySpeak(emptySet(), headphonesOnly = true))
        assertFalse(SpeakerGate.hasPersonal(emptyList()))
    }

    @Test
    fun toggleOffAlwaysSpeaks() {
        assertTrue(SpeakerGate.maySpeak(emptySet(), headphonesOnly = false))
        assertTrue(SpeakerGate.maySpeak(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), headphonesOnly = false))
    }
}
