package com.example.nightjar.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AudioRouteReadout(
    val evidence: AudioRouteEvidence = AudioRouteEvidence(),
    val inputName: String = "Not checked yet",
    val outputName: String = "Not checked yet"
)

@Singleton
class AudioRouteMonitor @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: OboeAudioEngine
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    fun read(): AudioRouteReadout {
        val evidence = engine.getStreamEvidence()
        val devices = manager.getDevices(AudioManager.GET_DEVICES_ALL).associateBy { it.id }
        fun name(stream: AudioStreamEvidence, input: Boolean): String {
            if (stream.epoch == 0L) return "Not checked yet"
            val device = devices[stream.deviceId]
            val actual = if (device == null) "Route unavailable (device ${stream.deviceId})"
                else if (input) AudioInputPreferences.label(device) else when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth headphones: ${device.productName}"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio: ${device.productName}"
                    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE: ${device.productName}"
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio: ${device.productName}"
                    else -> device.productName.toString()
                }
            return if (stream.open) actual else "Last used: $actual"
        }
        return AudioRouteReadout(evidence, name(evidence.input, true), name(evidence.output, false))
    }
}
