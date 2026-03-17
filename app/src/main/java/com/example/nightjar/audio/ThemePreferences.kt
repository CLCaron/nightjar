package com.example.nightjar.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's selected color theme in SharedPreferences.
 * Global setting (not per-idea).
 */
@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var themeKey: String
        get() = prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    companion object {
        private const val PREFS_NAME = "nightjar_theme"
        private const val KEY_THEME = "selected_theme"
        const val DEFAULT_THEME = "indigo"
        const val WARM_PLUM = "warm_plum"
    }
}
