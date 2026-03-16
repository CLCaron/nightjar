package com.example.nightjar.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists metronome settings in SharedPreferences.
 * These are global (not per-idea) since metronome preferences
 * are a user workflow choice, not a project property.
 */
@Singleton
class MetronomePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME)
        set(value) = prefs.edit().putFloat(KEY_VOLUME, value).apply()

    var countInBars: Int
        get() = prefs.getInt(KEY_COUNT_IN_BARS, DEFAULT_COUNT_IN_BARS)
        set(value) = prefs.edit().putInt(KEY_COUNT_IN_BARS, value).apply()

    companion object {
        private const val PREFS_NAME = "nightjar_metronome"
        private const val KEY_ENABLED = "metronome_enabled"
        private const val KEY_VOLUME = "metronome_volume"
        private const val KEY_COUNT_IN_BARS = "metronome_count_in_bars"
        const val DEFAULT_VOLUME = 0.7f
        const val DEFAULT_COUNT_IN_BARS = 0
    }
}
