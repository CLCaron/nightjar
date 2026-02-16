package com.example.nightjar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity

@Database(
    entities = [IdeaEntity::class, TagEntity::class, IdeaTagCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class NightjarDatabase : RoomDatabase() {

    abstract fun ideaDao(): IdeaDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile private var INSTANCE: NightjarDatabase? = null

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

        fun getInstance(context: Context): NightjarDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    NightjarDatabase::class.java,
                    "nightjar.db"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = db
                db
            }
        }


    }
}