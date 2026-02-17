package com.example.nightjar.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Utilities for sharing audio files via Android's share sheet. */
object ShareUtils {

    fun shareAudioFile(context: Context, file: File, title: String = "Nightjar idea") {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "Nightjar export: $title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share recording"))
    }
}