package com.example.ultrasonicchat

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var receiveButton: Button
    private lateinit var statusText: TextView
    private lateinit var receivedText: TextView

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            pendingAction?.invoke()
        } else {
            statusText.text = getString(R.string.status_error)
        }
        pendingAction = null
    }

    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        receiveButton = findViewById(R.id.receiveButton)
        statusText = findViewById(R.id.statusText)
        receivedText = findViewById(R.id.receivedText)

        messageInput.addTextChangedListener { text ->
            viewModel.updateInput(text?.toString().orEmpty())
        }

        sendButton.setOnClickListener {
            ensurePermissions { viewModel.sendMessage() }
        }

        receiveButton.setOnClickListener {
            ensurePermissions { viewModel.toggleReceiving() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (messageInput.text?.toString() != state.inputText) {
                        messageInput.setText(state.inputText)
                        messageInput.setSelection(state.inputText.length)
                    }
                    statusText.text = state.status
                    receivedText.text = state.receivedText
                    receiveButton.text = if (state.isListening) {
                        getString(R.string.stop_listening)
                    } else {
                        getString(R.string.start_listening)
                    }
                }
            }
        }
    }

    private fun ensurePermissions(action: () -> Unit) {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
        )
        val granted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(requiredPermissions)
        }
    }
}
