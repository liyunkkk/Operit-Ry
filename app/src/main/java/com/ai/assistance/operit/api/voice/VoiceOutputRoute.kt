package com.ai.assistance.operit.api.voice

import android.media.AudioDeviceInfo

internal fun AudioDeviceInfo?.isPrivateVoiceOutput(): Boolean = this?.type in setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
)
