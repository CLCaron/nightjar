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
 * - **v4** — Removed `audioFileName` from `ideas`; IdeaEntity is now a pure metadata container.
 */
@Database(
    entities = [IdeaEntity::class, TagEntity::class, IdeaTagCrossRef::class, TrackEntity::class],
    version = 4,
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

        /**
         * v3 → v4: Remove `audioFileName` from `ideas`.
         *
         * IdeaEntity becomes a pure metadata container — all audio references
         * now live exclusively in TrackEntity. Orphaned ideas (created before
         * Step 2 added atomic idea+track creation) are promoted to Track 1
         * before the column is dropped. Duration is set to 0 for migrated
         * tracks; [StudioRepository.ensureProjectInitialized] resolves it on
         * first access.
         *
         * SQLite < 3.35.0 (minSdk 24) doesn't support DROP COLUMN, so we
         * recreate the table.
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Promote orphaned ideas (no tracks yet) → create Track 1 rows
                db.execSQL("""
                    INSERT INTO tracks (ideaId, audioFileName, displayName, sortIndex,
                        offsetMs, trimStartMs, trimEndMs, durationMs, isMuted, volume, createdAtEpochMs)
                    SELECT id, audioFileName, 'Track 1', 0,
                        0, 0, 0, 0, 0, 1.0, createdAtEpochMs
                    FROM ideas WHERE id NOT IN (SELECT DISTINCT ideaId FROM tracks)
                """.trimIndent())

                // Recreate ideas table without audioFileName
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ideas_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        notes TEXT NOT NULL DEFAULT '',
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO ideas_new (id, title, notes, isFavorite, createdAtEpochMs)
                    SELECT id, title, notes, isFavorite, createdAtEpochMs FROM ideas
                """.trimIndent())

                db.execSQL("DROP TABLE ideas")
                db.execSQL("ALTER TABLE ideas_new RENAME TO ideas")
            }
        }

        fun getInstance(context: Context): NightjarDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    NightjarDatabase::class.java,
                    "nightjar.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = db
                db
            }
        }


    }
}