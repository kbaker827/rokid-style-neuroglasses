package com.rokid.style.neuroglasses

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Sequentially plays MP3 byte-array chunks using MediaPlayer.
 * Chunks are enqueued via [enqueue] and played one after another in FIFO order.
 * Call [release] when done to stop playback and free resources.
 *
 * Mirrors iOS StreamingAudioPlayer: chunks are appended while a response streams in,
 * and each chunk is played as soon as the previous one finishes.
 */
class AudioQueuePlayer {

    companion object {
        private const val TAG = "AudioQueuePlayer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    @Volatile private var released = false
    private var tempDir: File? = null

    init {
        scope.launch { drainQueue() }
    }

    /**
     * Set a temp directory for writing MP3 files before playback.
     * Must be called before the first [enqueue].
     */
    fun setTempDir(dir: File) {
        tempDir = dir
    }

    /** Enqueue an MP3 byte array for playback. */
    fun enqueue(mp3Bytes: ByteArray) {
        if (!released) {
            queue.trySend(mp3Bytes)
        }
    }

    /** Stop all playback and release resources. */
    fun release() {
        released = true
        queue.close()
    }

    // -----------------------------------------------------------------------

    private suspend fun drainQueue() {
        for (mp3 in queue) {
            if (released) break
            playMp3(mp3)
        }
    }

    private suspend fun playMp3(mp3Bytes: ByteArray) {
        val dir = tempDir ?: return
        val tmpFile = File(dir, "tts_${System.currentTimeMillis()}.mp3")
        try {
            FileOutputStream(tmpFile).use { it.write(mp3Bytes) }
            playFile(tmpFile)
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}", e)
        } finally {
            tmpFile.delete()
        }
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()

            player.setOnCompletionListener {
                it.release()
                if (cont.isActive) cont.resume(Unit)
            }

            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                mp.release()
                if (cont.isActive) cont.resume(Unit)
                true
            }

            cont.invokeOnCancellation {
                try { player.stop() } catch (_: Exception) {}
                player.release()
            }

            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start player: ${e.message}", e)
            player.release()
            if (cont.isActive) cont.resume(Unit)
        }
    }
}
