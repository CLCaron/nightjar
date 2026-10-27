package com.example.nightjar.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.data.db.NightjarDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in installation check. Only opens the backed-up Capture Test database using app migrations. */
class InstalledCaptureDataCheck {
    @Test fun openInstalledDatabaseWithApplicationMigrations() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyInstalledCaptureTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.nightjar.capturetest", context.packageName)
        assertTrue(context.getDatabasePath("nightjar.db").isFile)
        val database = NightjarDatabase.getInstance(context).openHelper.writableDatabase
        database.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        database.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals(18, it.getInt(0))
        }
        // Counts come from the fresh pre-install backup, never hard-coded user fixtures.
        for (table in listOf("ideas", "takes")) {
            val expected = requireNotNull(arguments.getString("expected_$table")).toLong()
            database.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(expected, it.getLong(0))
            }
        }
    }
}
