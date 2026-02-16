package com.example.nightjar.data.storage

import android.content.Context
import java.io.File

class RecordingStorage(private val context: Context) {

    private fun recordingsDir(): File =
        File(context.filesDir, "recordings").apply { mkdirs() }

    fun getAudioFile(fileName: String): File =
        File(recordingsDir(), fileName)

    fun deleteAudioFile(fileName: String) {
        val f = getAudioFile(fileName)
        if (f.exists()) f.delete()
    }
}
