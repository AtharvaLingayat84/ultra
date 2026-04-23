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

data class UiState(
    val inputText: String = "",
    val receivedText: String = "Received message appears here.",
    val status: String = "Status: Idle",
    val isListening: Boolean = false,
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

    fun sendMessage() {
        val text = state.value.inputText.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(status = "Status: Enter a message first") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(status = "Status: Sending") }
            try {
                transmitter.send(text)
                _state.update { it.copy(status = "Status: Sent") }
            } catch (throwable: Throwable) {
                _state.update { it.copy(status = "Status: Error: ${throwable.message ?: "send failed"}") }
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
            onStatus = { status -> _state.update { it.copy(status = "Status: $status") } },
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
    }

    fun stopReceiving() {
        receiver.stop()
        receiveJob = null
        _state.update { it.copy(isListening = false, status = "Status: Idle") }
    }

    override fun onCleared() {
        receiver.stop()
        super.onCleared()
    }
}
