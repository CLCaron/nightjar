package com.example.songseed.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.songseed.data.db.dao.IdeaDao
import com.example.songseed.data.db.dao.TagDao
import com.example.songseed.data.db.entity.IdeaEntity
import com.example.songseed.data.db.entity.TagEntity

@Database(
    entities = [IdeaEntity::class, TagEntity::class, IdeaTagCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class SongSeedDatabase : RoomDatabase() {

    abstract fun ideaDao(): IdeaDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile private var INSTANCE: SongSeedDatabase? = null

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

        fun getInstance(context: Context): SongSeedDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    SongSeedDatabase::class.java,
                    "songseed.db"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = db
                db
            }
        }


    }
}
