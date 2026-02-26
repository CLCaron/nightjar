package com.example.nightjar

import android.app.Application
import com.example.nightjar.audio.OboeAudioEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt application entry point. */
@HiltAndroidApp
class NightjarApplication : Application() {

    @Inject lateinit var audioEngine: OboeAudioEngine

    override fun onCreate() {
        super.onCreate()
        audioEngine.initialize()
    }
}
