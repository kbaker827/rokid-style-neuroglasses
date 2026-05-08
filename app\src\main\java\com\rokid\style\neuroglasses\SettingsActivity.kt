package com.rokid.style.neuroglasses

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apiKey = EditText(this).apply {
            hint = "OpenAI API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@SettingsActivity))
        }
        val baseUrl = EditText(this).apply {
            hint = "Base URL"
            setText(Prefs.baseUrl(this@SettingsActivity))
        }
        val model = EditText(this).apply {
            hint = "Chat model"
            setText(Prefs.chatModel(this@SettingsActivity))
        }
        val voice = EditText(this).apply {
            hint = "TTS voice"
            setText(Prefs.ttsVoice(this@SettingsActivity))
        }
        val tts = CheckBox(this).apply {
            text = "Use OpenAI TTS"
            isChecked = Prefs.ttsEnabled(this@SettingsActivity)
        }
        val streaming = CheckBox(this).apply {
            text = "Stream responses"
            isChecked = Prefs.streamingEnabled(this@SettingsActivity)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(TextView(this@SettingsActivity).apply {
                text = "NeuroGlasses Settings"
                textSize = 20f
            })
            addView(apiKey)
            addView(baseUrl)
            addView(model)
            addView(voice)
            addView(tts)
            addView(streaming)
            addView(Button(this@SettingsActivity).apply {
                text = "Save"
                setOnClickListener {
                    Prefs.setApiKey(this@SettingsActivity, apiKey.text.toString().trim())
                    Prefs.setBaseUrl(this@SettingsActivity, baseUrl.text.toString().trim())
                    Prefs.setChatModel(this@SettingsActivity, model.text.toString().trim())
                    Prefs.setTtsVoice(this@SettingsActivity, voice.text.toString().trim())
                    Prefs.setTtsEnabled(this@SettingsActivity, tts.isChecked)
                    Prefs.setStreamingEnabled(this@SettingsActivity, streaming.isChecked)
                    Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
        })
    }
}
