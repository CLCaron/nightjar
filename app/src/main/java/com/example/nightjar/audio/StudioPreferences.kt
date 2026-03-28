package com.example.nightjar.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Studio transport preferences in SharedPreferences.
 * These are global (not per-idea) since transport behavior
 * is a user workflow choice, not a project property.
 */
@Singleton
class StudioPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var returnToCursor: Boolean
        get() = prefs.getBoolean(KEY_RETURN_TO_CURSOR, true)
        set(value) = prefs.edit().putBoolean(KEY_RETURN_TO_CURSOR, value).apply()

    companion object {
        private const val PREFS_NAME = "nightjar_studio"
        private const val KEY_RETURN_TO_CURSOR = "return_to_cursor"
    }
}
