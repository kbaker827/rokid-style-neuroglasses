package com.rokid.style.neuroglasses

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences for all persisted settings.
 */
object Prefs {

    private const val PREF_FILE = "neuroglasses_prefs"

    private const val KEY_API_KEY       = "api_key"
    private const val KEY_BASE_URL      = "base_url"
    private const val KEY_CHAT_MODEL    = "chat_model"
    private const val KEY_TTS_VOICE     = "tts_voice"
    private const val KEY_TTS_ENABLED   = "tts_enabled"
    private const val KEY_STREAMING     = "streaming_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ---------- getters ----------

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"

    fun chatModel(context: Context): String =
        prefs(context).getString(KEY_CHAT_MODEL, "gpt-4o") ?: "gpt-4o"

    fun ttsVoice(context: Context): String =
        prefs(context).getString(KEY_TTS_VOICE, "alloy") ?: "alloy"

    fun ttsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS_ENABLED, true)

    fun streamingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAMING, true)

    // ---------- setters ----------

    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API_KEY, value).apply()

    fun setBaseUrl(context: Context, value: String) =
        prefs(context).edit().putString(KEY_BASE_URL, value).apply()

    fun setChatModel(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CHAT_MODEL, value).apply()

    fun setTtsVoice(context: Context, value: String) =
        prefs(context).edit().putString(KEY_TTS_VOICE, value).apply()

    fun setTtsEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    fun setStreamingEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_STREAMING, value).apply()
}
