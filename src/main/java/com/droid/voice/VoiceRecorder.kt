package com.droid.voice

import android.media.MediaRecorder
import java.io.File

@Suppress("DEPRECATION")
class VoiceRecorder(private val cacheDir: File) {

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startTime: Long = 0
    var isRecording: Boolean = false
        private set

    fun startRecording(): File? {
        try {
            val file = File(cacheDir, "voice_${System.currentTimeMillis()}.3gp")
            recordingFile = file

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(8000)
                setAudioEncodingBitRate(32000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            startTime = System.currentTimeMillis()
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun stopRecording(): Pair<File?, Long> {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            val duration = (System.currentTimeMillis() - startTime) / 1000
            val file = recordingFile
            recordingFile = null
            return Pair(file, duration)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(null, 0)
        }
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            recordingFile?.delete()
            recordingFile = null
        } catch (_: Exception) {}
    }
}