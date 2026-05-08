package com.rokid.style.neuroglasses

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rokid.style.neuroglasses.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main push-to-talk screen.
 *
 * UI:
 *   - Large PTT button (hold = record, release = send)
 *   - Spinner to select instruction prefix
 *   - Status label, transcript text, response text
 *   - Clear history and Settings menu items
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ------------------------------------------------------------------
    // Service binding
    // ------------------------------------------------------------------

    private var service: VoiceAssistantService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceAssistantService.LocalBinder).getService()
            bound   = true
            service?.refreshOpenAi()
            observeServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound   = false
        }
    }

    // ------------------------------------------------------------------
    // Permission launcher
    // ------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (!audioGranted) {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Activity lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInstructionSpinner()
        setupPttButton()
        setupMenuButtons()
        checkAndRequestPermissions()
        startAndBindService()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply settings changes if returning from SettingsActivity
        service?.refreshOpenAi()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(connection)
    }

    // ------------------------------------------------------------------
    // Setup helpers
    // ------------------------------------------------------------------

    private fun setupInstructionSpinner() {
        val labels = InstructionManager.instructions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerInstruction.adapter = adapter
        binding.spinnerInstruction.setSelection(0)
    }

    private fun setupPttButton() {
        binding.btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val svc = service ?: run {
                        Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (!hasAudioPermission()) {
                        checkAndRequestPermissions()
                        return@setOnTouchListener true
                    }
                    val instrIndex = binding.spinnerInstruction.selectedItemPosition
                    svc.selectedInstructionId =
                        InstructionManager.instructions[instrIndex].id
                    svc.startRecording()
                    binding.btnPtt.isActivated = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    service?.stopRecording()
                    binding.btnPtt.isActivated = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupMenuButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClear.setOnClickListener {
            service?.clearHistory()
            Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Service binding
    // ------------------------------------------------------------------

    private fun startAndBindService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    // ------------------------------------------------------------------
    // State observation
    // ------------------------------------------------------------------

    private fun observeServiceState() {
        val svc = service ?: return

        lifecycleScope.launch {
            svc.statusText.collectLatest { text ->
                binding.tvStatus.text = text
            }
        }

        lifecycleScope.launch {
            svc.transcript.collectLatest { text ->
                binding.tvTranscript.text = if (text.isBlank()) "" else "You: $text"
            }
        }

        lifecycleScope.launch {
            svc.response.collectLatest { text ->
                binding.tvResponse.text = if (text.isBlank()) "" else "AI: $text"
            }
        }

        lifecycleScope.launch {
            svc.state.collectLatest { state ->
                val isRecording = state == VoiceAssistantService.AssistantState.RECORDING
                val isIdle      = state == VoiceAssistantService.AssistantState.IDLE
                    || state == VoiceAssistantService.AssistantState.ERROR

                binding.btnPtt.isEnabled = isIdle || isRecording
                binding.recordingIndicator.alpha = if (isRecording) 1f else 0f

                binding.btnPtt.text = when (state) {
                    VoiceAssistantService.AssistantState.IDLE        -> getString(R.string.btn_hold_to_talk)
                    VoiceAssistantService.AssistantState.RECORDING   -> getString(R.string.btn_recording)
                    VoiceAssistantService.AssistantState.TRANSCRIBING-> getString(R.string.btn_processing)
                    VoiceAssistantService.AssistantState.THINKING    -> getString(R.string.btn_thinking)
                    VoiceAssistantService.AssistantState.SPEAKING    -> getString(R.string.btn_speaking)
                    VoiceAssistantService.AssistantState.ERROR       -> getString(R.string.btn_hold_to_talk)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
