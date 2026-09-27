package com.example.nightjar.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

data class MicrophoneOption(val key: String, val label: String, val deviceId: Int)

/** Route preference is a request. Opened native stream evidence is the actual route. */
@Singleton
class AudioInputPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("nightjar_audio_input", Context.MODE_PRIVATE)
    // Runtime device IDs cannot identify accessories across process restarts.
    private val sessionId = UUID.randomUUID().toString()
    val selectedKey: String get() = prefs.getString("microphone", DEFAULT) ?: DEFAULT

    fun options(): List<MicrophoneOption> = listOf(MicrophoneOption(DEFAULT, "System default", 0)) +
        devices().map { device ->
            MicrophoneOption(key(device), label(device), device.id)
        }.distinctBy { it.key }

    fun select(key: String) {
        require(options().any { it.key == key }) { "That microphone is no longer available." }
        prefs.edit().putString("microphone", key).apply()
    }

    fun resolveDeviceId(): Int {
        if (selectedKey == DEFAULT) return 0
        return devices().firstOrNull { key(it) == selectedKey }?.id
            ?: error("The selected microphone is disconnected. Choose another microphone in Settings.")
    }

    fun devices(): List<AudioDeviceInfo> = try {
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    } catch (e: SecurityException) {
        Log.w("AudioInputPreferences", "Cannot inspect microphone inventory", e)
        emptyList()
    }

    private fun key(device: AudioDeviceInfo): String =
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "phone"
        else "session:$sessionId:${device.id}:${device.type}:${device.productName}"

    companion object {
        const val DEFAULT = "default"
        fun label(device: AudioDeviceInfo): String = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset microphone"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone: ${device.productName}"
            else -> "${device.productName} microphone"
        }
    }
}
