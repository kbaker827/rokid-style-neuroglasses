package com.rokid.style.neuroglasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ForegroundService that owns recording, AI pipeline, and TTS playback.
 * Bound by MainActivity via [LocalBinder].
 *
 * State machine:
 *   IDLE → RECORDING  (startRecording)
 *   RECORDING → TRANSCRIBING (stopRecording)
 *   TRANSCRIBING → THINKING  (after Whisper returns)
 *   THINKING → SPEAKING      (after first TTS chunk is enqueued)
 *   SPEAKING → IDLE          (after queue drains)
 */
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceAssistantService"
        private const val CHANNEL_ID = "neuroglasses_channel"
        private const val NOTIFICATION_ID = 1
    }

    // ------------------------------------------------------------------
    // Binder
    // ------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ------------------------------------------------------------------
    // Public observable state
    // ------------------------------------------------------------------

    enum class AssistantState { IDLE, RECORDING, TRANSCRIBING, THINKING, SPEAKING, ERROR }

    private val _state       = MutableStateFlow(AssistantState.IDLE)
    private val _statusText  = MutableStateFlow("Ready")
    private val _transcript  = MutableStateFlow("")
    private val _response    = MutableStateFlow("")

    val state:      StateFlow<AssistantState> = _state
    val statusText: StateFlow<String>         = _statusText
    val transcript: StateFlow<String>         = _transcript
    val response:   StateFlow<String>         = _response

    // ------------------------------------------------------------------
    // Core components
    // ------------------------------------------------------------------

    private lateinit var recorder: AudioRecorderManager
    private lateinit var openAi: OpenAiService
    private lateinit var player: AudioQueuePlayer
    private val history = ConversationHistory()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Recording coroutine; kept so we can verify it finished. */
    private var recordJob: Job? = null

    /** WAV bytes captured from the last recording session. */
    @Volatile private var capturedWav: ByteArray? = null

    /** Currently selected instruction id. */
    var selectedInstructionId: String = "none"

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("NeuroGlasses ready"))

        recorder = AudioRecorderManager(this)
        player   = AudioQueuePlayer().also { it.setTempDir(cacheDir) }
        refreshOpenAi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        recorder.stop()
        player.release()
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Reload OpenAI credentials/settings from SharedPreferences. */
    fun refreshOpenAi() {
        val k = Prefs.apiKey(this)
        val u = Prefs.baseUrl(this)
        val m = Prefs.chatModel(this)
        val v = Prefs.ttsVoice(this)
        if (::openAi.isInitialized) {
            openAi.updateSettings(apiKey = k, baseUrl = u, chatModel = m, ttsVoice = v)
        } else {
            openAi = OpenAiService(apiKey = k, baseUrl = u, chatModel = m, ttsVoice = v)
        }
    }

    /** Begin recording audio from the microphone. */
    fun startRecording() {
        if (_state.value != AssistantState.IDLE) return
        capturedWav = null
        _state.value      = AssistantState.RECORDING
        _statusText.value = "Listening…"
        _transcript.value = ""
        _response.value   = ""

        recordJob = scope.launch(Dispatchers.IO) {
            capturedWav = recorder.recordUntilStopped()
        }
    }

    /**
     * Stop the microphone and run the full AI pipeline:
     *   WAV → Whisper → chat → TTS
     */
    fun stopRecording() {
        if (_state.value != AssistantState.RECORDING) return
        recorder.stop()                     // signals the recording loop to exit
        scope.launch { awaitWavThenProcess() }
    }

    /** Wipe conversation memory. */
    fun clearHistory() {
        history.clear()
        _transcript.value = ""
        _response.value   = ""
    }

    // ------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------

    /** Wait for the recording coroutine to finish, then start transcription. */
    private suspend fun awaitWavThenProcess() {
        _state.value      = AssistantState.TRANSCRIBING
        _statusText.value = "Transcribing…"
        updateNotification("Transcribing…")

        // Wait until the recording job truly completes and capturedWav is set
        recordJob?.join()

        val wav = capturedWav
        if (wav == null || wav.size <= 44) {   // 44 bytes = WAV header only
            setError("No audio captured")
            return
        }

        val wavFile = try {
            recorder.writeTempWav(wav)
        } catch (e: Exception) {
            setError("Disk error: ${e.message}")
            return
        }

        val transcript = try {
            openAi.transcribe(wavFile)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            setError("Transcription error: ${e.message}")
            return
        } finally {
            wavFile.delete()
        }

        if (transcript.isBlank()) {
            setError("No speech detected")
            return
        }

        _transcript.value = transcript
        val prompt = InstructionManager.apply(selectedInstructionId, transcript)
        history.add("user", prompt)

        runChat()
    }

    private suspend fun runChat() {
        _state.value      = AssistantState.THINKING
        _statusText.value = "Thinking…"
        updateNotification("Thinking…")

        if (Prefs.streamingEnabled(this)) {
            runStreamingChat()
        } else {
            runBlockingChat()
        }
    }

    // ---- Streaming mode ------------------------------------------------

    private suspend fun runStreamingChat() {
        val ttsEnabled = Prefs.ttsEnabled(this)
        val sb         = StringBuilder()
        val ttsBuf     = StringBuilder()

        openAi.streamChat(
            history   = history,
            onToken   = { token ->
                sb.append(token)
                _response.value = sb.toString()

                if (ttsEnabled) {
                    ttsBuf.append(token)
                    flushTtsBuf(ttsBuf, force = false)
                }
            },
            onComplete = { fullText ->
                history.add("assistant", fullText)
                if (ttsEnabled) {
                    val tail = ttsBuf.toString().trim()
                    ttsBuf.clear()
                    scope.launch(Dispatchers.IO) {
                        if (tail.isNotBlank()) enqueueTts(tail)
                        finishSpeaking()
                    }
                    _state.value      = AssistantState.SPEAKING
                    _statusText.value = "Speaking…"
                } else {
                    toIdle()
                }
            },
            onError = { e -> setError("Chat error: ${e.message}") }
        )
    }

    /**
     * Flush completed sentences from [buf] to TTS.
     * If [force] is true, flushes everything.
     */
    private fun flushTtsBuf(buf: StringBuilder, force: Boolean) {
        val text = buf.toString()
        val idx  = if (force) text.length - 1
                   else text.indexOfLast { it == '.' || it == '!' || it == '?' }
        if (idx <= 0) return

        val chunk = text.substring(0, idx + 1).trim()
        buf.delete(0, idx + 1)

        if (chunk.isNotBlank()) {
            scope.launch(Dispatchers.IO) { enqueueTts(chunk) }
        }
    }

    // ---- Non-streaming mode --------------------------------------------

    private suspend fun runBlockingChat() {
        val result = openAi.chat(history)
        result.fold(
            onSuccess = { fullText ->
                history.add("assistant", fullText)
                _response.value = fullText
                if (Prefs.ttsEnabled(this)) {
                    _state.value      = AssistantState.SPEAKING
                    _statusText.value = "Speaking…"
                    scope.launch(Dispatchers.IO) {
                        openAi.splitForTts(fullText).forEach { chunk -> enqueueTts(chunk) }
                        finishSpeaking()
                    }
                } else {
                    toIdle()
                }
            },
            onFailure = { e -> setError("Chat error: ${e.message}") }
        )
    }

    // ---- TTS helpers ---------------------------------------------------

    private suspend fun enqueueTts(text: String) {
        try {
            val mp3 = openAi.synthesize(text)
            player.enqueue(mp3)
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesis failed: ${e.message}", e)
        }
    }

    private fun finishSpeaking() {
        scope.launch {
            delay(600)   // let the queue drain
            toIdle()
        }
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    private fun toIdle() {
        _state.value      = AssistantState.IDLE
        _statusText.value = "Ready"
        updateNotification("NeuroGlasses ready")
    }

    private fun setError(msg: String) {
        _state.value      = AssistantState.ERROR
        _statusText.value = msg
        Log.e(TAG, msg)
        scope.launch {
            delay(3_000)
            toIdle()
        }
    }

    // ------------------------------------------------------------------
    // Notification helpers
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "NeuroGlasses Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "AI voice assistant status" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeuroGlasses")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
