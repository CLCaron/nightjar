package com.example.nightjar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Short test sounds must yield immediately to calls and other focus owners. */
class AcousticCheckFocus @Inject constructor(@ApplicationContext context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("DEPRECATION")
    fun acquire(onLoss: () -> Unit): AutoCloseable {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) onLoss()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setOnAudioFocusChangeListener(listener).build()
            check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Audio is in use by another app. Try the timing check later."
            }
            return AutoCloseable { audio.abandonAudioFocusRequest(request) }
        }
        check(audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio is in use by another app. Try the timing check later."
        }
        return AutoCloseable { audio.abandonAudioFocus(listener) }
    }
}
