package com.example.ultrasonicchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class UiState(
    val inputText: String = "",
    val receivedText: String = "Received message appears here.",
    val status: String = "Status: Idle",
    val isListening: Boolean = false,
    val recentLogs: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val transmitter = Transmitter()
    private val receiver = Receiver()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var receiveJob: Job? = null

    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun log(message: String) {
        val entry = "[${timestamp()}] $message"
        _state.update { state ->
            val updatedLogs = (state.recentLogs + entry).takeLast(50)
            state.copy(recentLogs = updatedLogs)
        }
    }

    fun setStatus(message: String) {
        _state.update { it.copy(status = message) }
    }

    fun sendMessage() {
        val text = state.value.inputText.trim()
        if (text.isEmpty()) {
            setStatus("Status: Enter a message first")
            log("Send blocked: empty message")
            return
        }

        viewModelScope.launch {
            setStatus("Status: Sending")
            log("Send start: textLength=${text.length}")
            try {
                transmitter.send(
                    text = text,
                    onLog = ::log,
                )
                setStatus("Status: Sent")
                log("Send success")
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "send failed"
                setStatus("Status: Error: $message")
                log("Send failure: $message")
            }
        }
    }

    fun playTestSweep() {
        viewModelScope.launch {
            setStatus("Status: Playing test sweep")
            log("Test sweep start")
            try {
                transmitter.playAudibleTestSweep(onLog = ::log)
                setStatus("Status: Test sweep complete")
                log("Test sweep end")
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "test sweep failed"
                setStatus("Status: Error: $message")
                log("Test sweep failure: $message")
            }
        }
    }

    fun toggleReceiving() {
        if (receiveJob?.isActive == true) {
            stopReceiving()
        } else {
            startReceiving()
        }
    }

    fun startReceiving() {
        if (receiveJob?.isActive == true) return
        receiveJob = receiver.start(
            scope = viewModelScope,
            onStatus = { status -> setStatus("Status: $status") },
            onLog = ::log,
            onMessage = { message ->
                _state.update {
                    it.copy(
                        receivedText = message,
                        status = "Status: Received",
                        isListening = true,
                    )
                }
            },
        )
        _state.update { it.copy(isListening = true, status = "Status: Listening") }
        log("Receive start")
    }

    fun stopReceiving() {
        log("Receive stop requested")
        receiver.stop()
        receiveJob = null
        _state.update { it.copy(isListening = false, status = "Status: Idle") }
        log("Receive stopped")
    }

    override fun onCleared() {
        receiver.stop()
        super.onCleared()
    }

    private fun timestamp(): String {
        val now = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return now.format(java.util.Date())
    }
}
