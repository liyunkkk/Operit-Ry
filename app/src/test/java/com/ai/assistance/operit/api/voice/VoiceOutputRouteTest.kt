package com.ai.assistance.operit.api.voice

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class VoiceOutputRouteTest {
    private fun device(type: Int): AudioDeviceInfo =
        Mockito.mock(AudioDeviceInfo::class.java).also { Mockito.`when`(it.type).thenReturn(type) }

    @Test fun bluetoothProtocolDoesNotProveHeadphones() {
        assertFalse(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).isPrivateVoiceOutput())
        assertFalse(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).isPrivateVoiceOutput())
        assertFalse(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).isPrivateVoiceOutput())
    }

    @Test fun explicitHeadphoneRoutesAreEligible() {
        assertTrue(device(AudioDeviceInfo.TYPE_WIRED_HEADSET).isPrivateVoiceOutput())
        assertTrue(device(AudioDeviceInfo.TYPE_USB_HEADSET).isPrivateVoiceOutput())
        assertTrue(device(AudioDeviceInfo.TYPE_BLE_HEADSET).isPrivateVoiceOutput())
    }

    @Test fun noReportedRouteNeverEnablesInterruption() {
        assertFalse((null as AudioDeviceInfo?).isPrivateVoiceOutput())
    }
}
