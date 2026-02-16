package com.example.nightjar.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun start(): File {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "nightjar_$ts.m4a")
        currentFile = file

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)

        r.prepare()
        r.start()
        recorder = r

        return file
    }

    fun stop(): File? {
        val r = recorder ?: return null
        return try {
            r.stop()
            currentFile
        } catch (e: Exception) {
            currentFile?.delete()
            null
        } finally {
            r.reset()
            r.release()
            recorder = null
        }
    }
}