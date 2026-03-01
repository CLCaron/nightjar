package com.example.nightjar.di

import android.content.Context
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.repository.StudioRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt module providing database, DAOs, repositories, and audio infrastructure. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): NightjarDatabase =
            NightjarDatabase.getInstance(context)

        @Provides
        fun provideIdeaDao(db: NightjarDatabase): IdeaDao = db.ideaDao()

        @Provides
        fun provideTagDao(db: NightjarDatabase): TagDao = db.tagDao()

        @Provides
        fun provideRecordingStorage(@ApplicationContext context: Context): RecordingStorage =
            RecordingStorage(context)

        @Provides
        @Singleton
        fun provideIdeaRepository(
            ideaDao: IdeaDao,
            tagDao: TagDao,
            trackDao: TrackDao,
            storage: RecordingStorage,
            database: NightjarDatabase
        ): IdeaRepository = IdeaRepository(ideaDao, tagDao, trackDao, storage, database)

        @Provides
        fun provideTrackDao(db: NightjarDatabase): TrackDao = db.trackDao()

        @Provides
        fun provideTakeDao(db: NightjarDatabase): TakeDao = db.takeDao()

        @Provides
        @Singleton
        fun provideStudioRepository(
            trackDao: TrackDao,
            takeDao: TakeDao,
            storage: RecordingStorage
        ): StudioRepository = StudioRepository(trackDao, takeDao, storage)

    }
}