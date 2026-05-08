package com.rokid.style.neuroglasses

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wraps the three OpenAI endpoints used by NeuroGlasses:
 *   1. Whisper STT  — POST /audio/transcriptions
 *   2. Chat (stream)— POST /chat/completions (SSE)
 *   3. TTS-1        — POST /audio/speech → mp3 bytes
 */
class OpenAiService(
    private var apiKey: String,
    private var baseUrl: String,
    private var chatModel: String,
    private var ttsVoice: String
) {

    companion object {
        private const val TAG = "OpenAiService"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /** Update settings without recreating the service instance. */
    fun updateSettings(
        apiKey: String = this.apiKey,
        baseUrl: String = this.baseUrl,
        chatModel: String = this.chatModel,
        ttsVoice: String = this.ttsVoice
    ) {
        this.apiKey = apiKey
        this.baseUrl = baseUrl.trimEnd('/')
        this.chatModel = chatModel
        this.ttsVoice = ttsVoice
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // -----------------------------------------------------------------------
    // 1. Whisper STT
    // -----------------------------------------------------------------------

    /**
     * Uploads [wavFile] to Whisper and returns the transcription string.
     * @throws IOException on network or API error.
     */
    suspend fun transcribe(wavFile: File): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart(
                "file",
                "audio.wav",
                wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("${baseUrl}/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Whisper API error ${response.code}: $body")
            }
            JSONObject(body).getString("text").trim()
        }
    }

    // -----------------------------------------------------------------------
    // 2. Chat completions — streaming SSE
    // -----------------------------------------------------------------------

    /**
     * Streams a chat completion.
     * [onToken] is called for every incremental content token (on the IO thread).
     * [onComplete] is called once with the full assembled response text.
     * [onError] is called if the request fails.
     */
    suspend fun streamChat(
        history: ConversationHistory,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", chatModel)
            put("messages", JSONArray(history.toJsonArray()))
            put("stream", true)
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    throw IOException("Chat API error ${response.code}: $errBody")
                }

                val source = response.body?.source()
                    ?: throw IOException("Empty response body")

                val fullText = StringBuilder()

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break

                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val delta = json
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("delta")

                        if (delta.has("content")) {
                            val token = delta.getString("content")
                            fullText.append(token)
                            onToken(token)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SSE parse skip: $data — ${e.message}")
                    }
                }

                onComplete(fullText.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "streamChat error: ${e.message}", e)
            onError(e)
        }
    }

    /**
     * Non-streaming chat completion (fallback when streaming is disabled).
     * Returns the full assistant message text.
     */
    suspend fun chat(history: ConversationHistory): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", chatModel)
            put("messages", JSONArray(history.toJsonArray()))
            put("stream", false)
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Chat error ${response.code}: $respBody"))
                }
                val text = JSONObject(respBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -----------------------------------------------------------------------
    // 3. TTS-1
    // -----------------------------------------------------------------------

    /**
     * Synthesizes [text] using TTS-1 and returns the raw MP3 bytes.
     * @throws IOException on network or API error.
     */
    suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", "tts-1")
            put("input", text)
            put("voice", ttsVoice)
            put("response_format", "mp3")
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl}/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()
            if (!response.isSuccessful || bytes == null) {
                throw IOException("TTS API error ${response.code}")
            }
            bytes
        }
    }

    // -----------------------------------------------------------------------
    // Helper: split long text into TTS-friendly chunks (≤ 200 chars each)
    // -----------------------------------------------------------------------

    /**
     * Splits [text] at sentence boundaries so each chunk is at most [maxChars] characters.
     * Mirrors iOS splitForTTS().
     */
    fun splitForTts(text: String, maxChars: Int = 200): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val chunks = mutableListOf<String>()
        val sentenceRegex = Regex("[.!?]+\\s*")

        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                chunks.add(remaining)
                break
            }

            // Find the last sentence boundary within maxChars
            val window = remaining.substring(0, maxChars)
            val match = sentenceRegex.findAll(window).lastOrNull()

            val cutPoint = if (match != null) {
                match.range.last + 1
            } else {
                // No sentence boundary — cut at last space
                val lastSpace = window.lastIndexOf(' ')
                if (lastSpace > 0) lastSpace + 1 else maxChars
            }

            chunks.add(remaining.substring(0, cutPoint).trim())
            remaining = remaining.substring(cutPoint).trim()
        }

        return chunks.filter { it.isNotBlank() }
    }
}
