package com.example.songseed.player

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {

    private val player: ExoPlayer =
        ExoPlayer.Builder(context).build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private var currentFile: File? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    _durationMs.value = player.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    _positionMs.value = player.duration.coerceAtLeast(0L)
                }
            }
        })

        viewModelScope.launch {
            while (true) {
                if (player.isPlaying) {
                    _positionMs.value = player.currentPosition
                }
                delay(200)
            }
        }
    }

    fun playFile(file: File) {
        val sameFile = currentFile?.absolutePath == file.absolutePath

        if (!sameFile) {
            player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            player.prepare()
            currentFile = file
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
        }

        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
