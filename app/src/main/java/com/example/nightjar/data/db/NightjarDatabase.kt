package com.example.nightjar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.db.entity.TrackEntity

/**
 * Room database for Nightjar.
 *
 * ## Schema history
 * - **v1** — `ideas` table (core recording metadata).
 * - **v2** — Added `tags` and `idea_tags` tables for user-defined tagging.
 * - **v3** — Added `tracks` table for multi-track Studio projects.
 */
@Database(
    entities = [IdeaEntity::class, TagEntity::class, IdeaTagCrossRef::class, TrackEntity::class],
    version = 3,
    exportSchema = false
)
abstract class NightjarDatabase : RoomDatabase() {

    abstract fun ideaDao(): IdeaDao
    abstract fun tagDao(): TagDao
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile private var INSTANCE: NightjarDatabase? = null

        /** v1 → v2: Add tagging support (tags + junction table). */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                nameNormalized TEXT NOT NULL
            )
        """.trimIndent())

                db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS index_tags_nameNormalized
            ON tags(nameNormalized)
        """.trimIndent())

                db.execSQL("""
            CREATE TABLE IF NOT EXISTS idea_tags (
                ideaId INTEGER NOT NULL,
                tagId INTEGER NOT NULL,
                PRIMARY KEY(ideaId, tagId)
            )
        """.trimIndent())
            }
        }

        /** v2 → v3: Add multi-track support (tracks table with idea FK). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ideaId INTEGER NOT NULL,
                audioFileName TEXT NOT NULL,
                displayName TEXT NOT NULL,
                sortIndex INTEGER NOT NULL,
                offsetMs INTEGER NOT NULL DEFAULT 0,
                trimStartMs INTEGER NOT NULL DEFAULT 0,
                trimEndMs INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL,
                isMuted INTEGER NOT NULL DEFAULT 0,
                volume REAL NOT NULL DEFAULT 1.0,
                createdAtEpochMs INTEGER NOT NULL,
                FOREIGN KEY(ideaId) REFERENCES ideas(id) ON DELETE CASCADE
            )
        """.trimIndent())

                db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_tracks_ideaId ON tracks(ideaId)
        """.trimIndent())
            }
        }

        fun getInstance(context: Context): NightjarDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    NightjarDatabase::class.java,
                    "nightjar.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = db
                db
            }
        }


    }
}