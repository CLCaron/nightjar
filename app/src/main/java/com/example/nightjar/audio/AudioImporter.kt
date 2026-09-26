package com.example.nightjar.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.nightjar.data.storage.RecordingStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: RecordingStorage
) {
    data class ImportedAudio(val file: File, val original: File, val durationMs: Long, val title: String)

    suspend fun import(uri: Uri): ImportedAudio {
        var original: File? = null
        var playback: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty()
                val sourceFile = storage.createRecordingFile("import_source", "source")
                original = sourceFile
                val outputFile = storage.createRecordingFile("import", "wav")
                playback = outputFile
                context.contentResolver.openInputStream(uri)?.use { input ->
                    sourceFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            jobContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Could not open this audio file.")
                val duration = WavImportConverter.convert(sourceFile, outputFile) { jobContext.ensureActive() }
                ImportedAudio(outputFile, sourceFile, duration,
                    name.substringBeforeLast('.', name).filter { !it.isISOControl() }.trim().take(80).ifBlank { "Imported Idea" })
            }
        } catch (error: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                for (file in listOfNotNull(original, playback)) {
                    if (file.exists() && !file.delete()) android.util.Log.e("AudioImporter", "Could not remove partial import: ${file.name}")
                }
            }
            throw error
        }
    }
}
