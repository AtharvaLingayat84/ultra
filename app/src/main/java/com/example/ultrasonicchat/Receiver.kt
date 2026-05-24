package com.example.ultrasonicchat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class Receiver {
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        config: AudioConfig,
        onStatus: (String) -> Unit,
        onLog: (String) -> Unit,
        onMessage: (String) -> Unit,
    ): Job? {
        if (!running.compareAndSet(false, true)) {
            onLog("Receive start ignored: already running")
            return job
        }
        onStopLog = onLog

        val activeJob = scope.launch(Dispatchers.IO) {
            onLog("Receive init")
            val minBuffer = AudioRecord.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                onStatus("Unable to configure microphone")
                onLog("Receive failure: invalid min buffer size=$minBuffer")
                running.set(false)
                return@launch
            }

            val inputSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }

            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(inputSource)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, config.chunkSize * 8))
                    .build()
            } catch (e: Exception) {
                onStatus("Unable to open microphone")
                onLog("Receive failure: ${e.message}")
                running.set(false)
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onStatus("Unable to initialize microphone")
                onLog("Receive failure: AudioRecord state=${recorder.state}")
                recorder.release()
                running.set(false)
                return@launch
            }
            record = recorder

            onLog(
                "Receive config: requestedRate=${config.sampleRate}Hz actualRate=${recorder.sampleRate}Hz channelCount=${recorder.channelCount} encoding=${recorder.audioFormat} buffer=${recorder.bufferSizeInFrames} frames",
            )

            val processor = SignalProcessor(config)
            val readBuffer = ShortArray(4096)

            try {
                onLog("Receive start: AudioRecord starting source=$inputSource sampleRate=${config.sampleRate}Hz minDb=${config.minSignalDbfs}")
                recorder.startRecording()
                onStatus("Listening")

                while (running.get() && isActive) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) {
                        if (read < 0) onLog("Receive read error: $read")
                        continue
                    }

                    val block = AudioUtils.toFloats(readBuffer, read)
                    val message = processor.process(block, onLog)
                    if (message != null) {
                        onMessage(message)
                        onLog("Decode hit: message length=${message.length}")
                        onStatus("Message received")
                    }
                }
            } catch (e: Exception) {
                onLog("Receive loop error: ${e.message}")
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {}
                recorder.release()
                record = null
                running.set(false)
                onLog("Receive end")
            }
        }

        job = activeJob
        return activeJob
    }

    fun stop() {
        running.set(false)
        onStopLog?.invoke("Receive stop: requested")
        try {
            record?.stop()
        } catch (_: Throwable) {}
        record?.release()
        record = null
        job?.cancel()
        job = null
    }

    private var onStopLog: ((String) -> Unit)? = null
}
