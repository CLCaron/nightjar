package com.example.nightjar.data.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Abstraction over the app-private file system for audio recordings.
 *
 * All audio files are stored in a single `recordings/` directory under
 * [Context.getFilesDir].
 */
class RecordingStorage(private val context: Context) {

    private fun recordingsDir(): File =
        File(context.filesDir, "recordings").apply { mkdirs() }

    fun createRecordingFile(prefix: String = "nightjar", extension: String = "wav"): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(), "${prefix}_${ts}.${extension}")
    }

    fun getAudioFile(fileName: String): File =
        File(recordingsDir(), fileName)

    fun deleteAudioFile(fileName: String) {
        val f = getAudioFile(fileName)
        if (f.exists()) f.delete()
    }
}
