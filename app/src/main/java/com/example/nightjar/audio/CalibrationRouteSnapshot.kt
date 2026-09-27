package com.example.nightjar.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CalibrationRouteSnapshot(val key: CalibrationRouteKey, val inputLabel: String,
    val outputLabel: String, val identityScope: String, val sessionId: String)

/** Inventory is joined to actual opened IDs. An unknown accessory never gets a stable identity. */
@Singleton
class CalibrationRouteSnapshots @Inject constructor(@ApplicationContext context: Context,
    private val routes: AudioRouteMonitor) {
    private val manager = context.getSystemService(AudioManager::class.java)
    val sessionId: String = UUID.randomUUID().toString()

    fun read(evidence: AudioRouteEvidence): CalibrationRouteSnapshot? {
        if (!evidence.input.open || !evidence.output.open) return null
        val devices = try { manager.getDevices(AudioManager.GET_DEVICES_ALL).toList() }
        catch (error: SecurityException) {
            Log.w("CalibrationRoutes", "Cannot identify calibration route", error)
            return null
        }
        val input = devices.firstOrNull { it.id == evidence.input.deviceId && it.isSource } ?: return null
        val output = devices.firstOrNull { it.id == evidence.output.deviceId && it.isSink } ?: return null
        fun identity(device: AudioDeviceInfo): Pair<String, Boolean> {
            val builtin = device.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            if (builtin && devices.count { it.type == device.type && it.isSource == device.isSource } == 1)
                return "builtin:${device.type}" to true
            val bluetooth = device.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
            if (bluetooth && Build.VERSION.SDK_INT >= 28) {
                val address = try { device.address } catch (error: SecurityException) {
                    Log.w("CalibrationRoutes", "Bluetooth identity is unavailable", error)
                    ""
                }
                if (address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
                    address != "00:00:00:00:00:00" && address != "02:00:00:00:00:00")
                    return "bluetooth:${device.type}:${address.lowercase()}" to true
            }
            // USB bus/port addresses and product names do not establish hardware identity.
            return "session:$sessionId:${device.type}:${device.id}" to false
        }
        val (inputIdentity, inputStable) = identity(input)
        val (outputIdentity, outputStable) = identity(output)
        val i = evidence.input
        val o = evidence.output
        val transport = when (output.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "le"
            AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin"
            else -> "other"
        }
        val key = CalibrationRouteKey(inputIdentity, outputIdentity, i.sampleRate, o.sampleRate,
            i.channels, o.channels, i.backend, o.backend, i.bufferFrames, o.bufferFrames,
            i.inputPreset, manager.mode, transport, 1, Build.FINGERPRINT,
            i.format, o.format, i.sharingMode, o.sharingMode, i.performanceMode, o.performanceMode,
            i.burstFrames, o.burstFrames)
        val readout = routes.read()
        // Reopening while inventory was inspected invalidates the identity snapshot.
        if (readout.evidence.input.epoch != i.epoch || readout.evidence.output.epoch != o.epoch) return null
        return CalibrationRouteSnapshot(key, readout.inputName, readout.outputName,
            if (inputStable && outputStable) "stable" else "session", sessionId)
    }
}
