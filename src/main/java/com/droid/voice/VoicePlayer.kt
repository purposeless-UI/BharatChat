package com.droid.voice

import android.media.MediaPlayer
import android.util.Log

class VoicePlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun play(filePath: String, onCompletion: (() -> Unit)? = null) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    onCompletion?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e("VoicePlayer", "Play failed", e)
        }
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}