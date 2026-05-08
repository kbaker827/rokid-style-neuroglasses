package com.rokid.style.neuroglasses

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records raw PCM audio via AudioRecord and packages it as a valid WAV byte array.
 * Recording runs on the IO dispatcher; call start() / stop() from a coroutine.
 *
 * WAV specification used:
 *   - PCM 16-bit little-endian
 *   - 16 000 Hz sample rate
 *   - 1 channel (mono)
 */
class AudioRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderManager"
        private const val SAMPLE_RATE = 16_000        // 16 kHz — best for Whisper
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
    }

    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null

    /**
     * Starts recording. Returns when [stop] is called.
     * @return WAV-encoded bytes ready for upload to Whisper, or null on error.
     */
    suspend fun recordUntilStopped(): ByteArray? = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(8192)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            return@withContext null
        }

        audioRecord = recorder
        val pcmBuffer = ByteArrayOutputStream()
        val readBuf = ByteArray(bufferSize)

        isRecording = true
        recorder.startRecording()
        Log.d(TAG, "Recording started")

        try {
            while (isRecording) {
                val bytesRead = recorder.read(readBuf, 0, readBuf.size)
                if (bytesRead > 0) {
                    pcmBuffer.write(readBuf, 0, bytesRead)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            audioRecord = null
            Log.d(TAG, "Recording stopped; PCM bytes = ${pcmBuffer.size()}")
        }

        val pcmData = pcmBuffer.toByteArray()
        return@withContext buildWav(pcmData)
    }

    /** Signal the recording loop to stop. */
    fun stop() {
        isRecording = false
    }

    val isCurrentlyRecording: Boolean get() = isRecording

    // -----------------------------------------------------------------------
    // WAV header construction
    // -----------------------------------------------------------------------

    private fun buildWav(pcm: ByteArray): ByteArray {
        val totalDataLen = pcm.size + 36
        val byteRate = SAMPLE_RATE * 1 * BYTES_PER_SAMPLE
        val blockAlign = (1 * BYTES_PER_SAMPLE).toShort()
        val bitsPerSample: Short = 16

        val out = ByteArrayOutputStream(44 + pcm.size)

        fun writeInt(v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            out.write(b)
        }
        fun writeShort(v: Short) {
            val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
            out.write(b)
        }

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeInt(totalDataLen)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeInt(16)                       // PCM chunk size
        writeShort(1)                      // AudioFormat = PCM
        writeShort(1)                      // NumChannels = mono
        writeInt(SAMPLE_RATE)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(bitsPerSample)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeInt(pcm.size)
        out.write(pcm)

        return out.toByteArray()
    }

    /**
     * Convenience: write WAV bytes to a temp file so OkHttp can read it as a file body.
     * Caller is responsible for deleting the file afterwards.
     */
    fun writeTempWav(wavBytes: ByteArray): File {
        val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { it.write(wavBytes) }
        return file
    }
}
