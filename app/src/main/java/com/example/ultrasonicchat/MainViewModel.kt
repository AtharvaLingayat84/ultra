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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

data class UiState(
    val inputText: String = "",
    val freq0Text: String = Constants.FREQ_0.toString(),
    val freq1Text: String = Constants.FREQ_1.toString(),
    val bitDurationMsText: String = (Constants.BIT_DURATION * 1000).toInt().toString(),
    val dbLimitText: String = Constants.MIN_SIGNAL_DBFS.toInt().toString(),
    val receivedText: String = "Received message appears here.",
    val status: String = "Status: Idle",
    val isListening: Boolean = false,
    val recentLogs: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val transmitter = Transmitter()
    private val receiver = Receiver()
    private val transmitInProgress = AtomicBoolean(false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var receiveJob: Job? = null

    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun updateTuning(
        freq0Text: String,
        freq1Text: String,
        bitDurationMsText: String,
        dbLimitText: String,
    ) {
        _state.update {
            it.copy(
                freq0Text = freq0Text,
                freq1Text = freq1Text,
                bitDurationMsText = bitDurationMsText,
                dbLimitText = dbLimitText,
            )
        }
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

        val config = parseAudioConfig() ?: return

        if (!transmitInProgress.compareAndSet(false, true)) {
            log("Send ignored: transmission already running")
            setStatus("Status: Sending already in progress")
            return
        }

        viewModelScope.launch {
            try {
                setStatus("Status: Sending")
                log("Send start: textLength=${text.length} freq0=${config.freq0}Hz freq1=${config.freq1}Hz bitMs=${(config.bitDurationSeconds * 1000).toInt()} dbLimit=${config.minSignalDbfs}")
                transmitter.send(
                    text = text,
                    config = config,
                    onLog = ::log,
                )
                setStatus("Status: Sent")
                log("Send success")
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "send failed"
                setStatus("Status: Error: $message")
                log("Send failure: $message")
            } finally {
                transmitInProgress.set(false)
            }
        }
    }

    fun playTestSweep() {
        if (!transmitInProgress.compareAndSet(false, true)) {
            log("Test sweep ignored: transmission already running")
            setStatus("Status: Sending already in progress")
            return
        }
        viewModelScope.launch {
            try {
                setStatus("Status: Playing test sweep")
                log("Test sweep start")
                transmitter.playAudibleTestSweep(onLog = ::log)
                setStatus("Status: Test sweep complete")
                log("Test sweep end")
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "test sweep failed"
                setStatus("Status: Error: $message")
                log("Test sweep failure: $message")
            } finally {
                transmitInProgress.set(false)
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
        val config = parseAudioConfig() ?: return
        receiveJob = receiver.start(
            scope = viewModelScope,
            config = config,
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
        log("Receive start: freq0=${config.freq0}Hz freq1=${config.freq1}Hz bitMs=${(config.bitDurationSeconds * 1000).toInt()} dbLimit=${config.minSignalDbfs}")
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

    private fun parseAudioConfig(): AudioConfig? {
        val ui = state.value
        val freq0 = ui.freq0Text.toIntOrNull()
        val freq1 = ui.freq1Text.toIntOrNull()
        val bitMs = ui.bitDurationMsText.toIntOrNull()
        val dbLimit = ui.dbLimitText.toFloatOrNull()

        if (freq0 == null || freq1 == null || bitMs == null || dbLimit == null) {
            setStatus("Status: Invalid tuning values")
            log("Tuning parse failed: freq0='${ui.freq0Text}' freq1='${ui.freq1Text}' bitMs='${ui.bitDurationMsText}' db='${ui.dbLimitText}'")
            return null
        }
        if (freq0 < 17000 || freq1 > 20000 || freq1 <= freq0) {
            setStatus("Status: Frequency range invalid")
            log("Tuning validation failed: require 17000 <= freq0 < freq1 <= 20000")
            return null
        }
        if (bitMs !in 20..400) {
            setStatus("Status: Bit duration must be 20-400ms")
            log("Tuning validation failed: bit duration=$bitMs ms")
            return null
        }
        if (dbLimit !in -100f..-10f) {
            setStatus("Status: dB limit must be -100 to -10")
            log("Tuning validation failed: db limit=$dbLimit dBFS")
            return null
        }

        val lowCut = (freq0 - 300).coerceAtLeast(15000).toFloat()
        val highCut = (freq1 + 500).coerceAtMost(21000).toFloat()
        return AudioConfig(
            freq0 = freq0,
            freq1 = freq1,
            bitDurationSeconds = bitMs / 1000.0,
            minSignalDbfs = dbLimit,
            bandpassLowCutoff = lowCut,
            bandpassHighCutoff = highCut,
        )
    }
}
