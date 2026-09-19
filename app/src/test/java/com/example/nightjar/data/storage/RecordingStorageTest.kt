package com.example.nightjar.data.storage

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStorageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `rapid path allocation preserves earlier audio`() {
        val context = mockk<Context>()
        every { context.filesDir } returns directory.root
        val storage = RecordingStorage(context)
        val first = storage.createRecordingFile()
        first.writeText("irreplaceable audio")
        val later = List(100) { storage.createRecordingFile() }
        assertEquals(101, (later + first).map { it.name }.toSet().size)
        assertTrue(later.all { it.exists() && it.extension == "wav" })
        assertEquals("irreplaceable audio", first.readText())
    }
}
