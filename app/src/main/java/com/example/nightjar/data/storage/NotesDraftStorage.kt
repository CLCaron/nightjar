package com.example.nightjar.data.storage

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotesDraft(val base: String, val text: String)

/** Recovery journal, not a second notes document. Room remains the acknowledged source. */
class NotesDraftStorage @Inject constructor(@ApplicationContext private val context: Context) {
    private fun file(id: Long): AtomicFile {
        require(id > 0)
        val directory = File(context.filesDir, "notes-recovery")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare writing recovery storage." }
        return AtomicFile(File(directory, "$id.draft"))
    }

    suspend fun read(id: Long): NotesDraft? = withContext(Dispatchers.IO) {
        val atomic = file(id)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@withContext null
        DataInputStream(atomic.openRead()).use { input ->
            check(input.readInt() == 1) { "Unsupported writing recovery format." }
            fun readText(): String {
                val length = input.readInt()
                require(length in 0..MAX_BYTES) { "Invalid writing recovery length." }
                return ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
            }
            NotesDraft(readText(), readText())
        }
    }

    suspend fun write(id: Long, draft: NotesDraft) = withContext(Dispatchers.IO) {
        val atomic = file(id)
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(1)
            listOf(draft.base, draft.text).forEach { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES) { "This note is too large to save safely." }
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.flush()
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    suspend fun clear(id: Long) = withContext(Dispatchers.IO) {
        val atomic = file(id)
        atomic.delete()
        check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
            "Words were written, but recovery cleanup needs a retry."
        }
    }

    private companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
