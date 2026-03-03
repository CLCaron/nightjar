package com.example.nightjar.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts the bundled SoundFont from APK assets on first launch and
 * caches it in internal storage so FluidSynth can read it by file path.
 *
 * FluidSynth's `fluid_synth_sfload()` requires a filesystem path --
 * it can't read from an Android AssetManager stream. This class handles
 * the one-time extraction to `filesDir/soundfonts/GeneralUser-GS.sf2`.
 */
@Singleton
class SoundFontManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val soundFontsDir = File(context.filesDir, "soundfonts")
    private val cachedFile = File(soundFontsDir, SF2_FILENAME)

    /**
     * Returns the absolute path to the cached SoundFont file.
     * Extracts from assets on first call (or if the cached file is missing).
     * Returns null if extraction fails.
     */
    suspend fun getSoundFontPath(): String? = withContext(Dispatchers.IO) {
        if (cachedFile.exists() && cachedFile.length() > 0) {
            Log.d(TAG, "SoundFont already cached: ${cachedFile.absolutePath}")
            return@withContext cachedFile.absolutePath
        }

        try {
            soundFontsDir.mkdirs()
            context.assets.open("$ASSET_DIR/$SF2_FILENAME").use { input ->
                cachedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "SoundFont extracted to: ${cachedFile.absolutePath} (${cachedFile.length()} bytes)")
            cachedFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SoundFont", e)
            cachedFile.delete()
            null
        }
    }

    companion object {
        private const val TAG = "SoundFontManager"
        private const val ASSET_DIR = "soundfonts"
        private const val SF2_FILENAME = "GeneralUser-GS.sf2"
    }
}
