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
            val blockFrames = maxOf(4096, config.hopSize * 2)
            val tailKeep = (config.sampleRate * config.postDetectTailSeconds).toInt()
            val maxBuffer = (config.sampleRate * config.receiveWindowSeconds).toInt()
            val readBuffer = ShortArray(blockFrames)
            var rolling = FloatArray(0)
            var cooldownUntil = 0L
            val frameLogs = ArrayDeque<String>()

            val inputSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }

            record = AudioRecord.Builder()
                .setAudioSource(inputSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, blockFrames * 4, config.chunkSize * 8))
                .build()
            if (record == null) {
                onStatus("Unable to open microphone")
                onLog("Receive failure: AudioRecord builder returned null")
                running.set(false)
                return@launch
            }

            val recorder = record ?: run {
                onStatus("Unable to open microphone")
                onLog("Receive failure: unable to open microphone")
                running.set(false)
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onStatus("Unable to initialize microphone")
                onLog("Receive failure: AudioRecord state=${recorder.state}")
                recorder.release()
                record = null
                running.set(false)
                return@launch
            }

            try {
                onLog("Receive start: AudioRecord starting source=$inputSource sampleRate=${config.sampleRate}Hz minDb=${config.minSignalDbfs}")
                recorder.startRecording()
                onStatus("Listening")

                while (running.get() && isActive) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) {
                        onLog("Receive read returned $read")
                        continue
                    }

                    val block = AudioUtils.toFloats(readBuffer, read)
                    rolling = AudioUtils.appendBounded(rolling, block, maxBuffer)

                    val now = System.currentTimeMillis()
                    if (now < cooldownUntil) {
                        continue
                    }

                    if (rolling.size < config.chunkSize * 3) {
                        continue
                    }

                    frameLogs.clear()
                    val rollingSeconds = rolling.size.toDouble() / config.sampleRate.toDouble()
                    onLog(
                        "Receive decode scope: rolling=${"%.2f".format(rollingSeconds)}s retained=${"%.2f".format(config.receiveWindowSeconds)}s samples=${rolling.size}",
                    )

                    val message = Decoder.decodeAudio(rolling, config) { debug ->
                        if (frameLogs.size >= 12) {
                            frameLogs.removeFirst()
                        }
                        frameLogs.addLast(debug)
                    }
                    frameLogs.forEach(onLog)
                    if (message.isNotEmpty()) {
                        onMessage(message)
                        onLog("Decode hit: message length=${message.length}")
                        rolling = AudioUtils.takeTail(rolling, tailKeep)
                        cooldownUntil = now + (config.postDetectCooldownSeconds * 1000).toLong()
                        onStatus("Message received")
                    } else {
                        onLog("Decode miss: no complete frame recovered from rolling buffer")
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                    onLog("Receive stop failed: recorder.stop threw")
                }
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
        } catch (_: Throwable) {
            onStopLog?.invoke("Receive stop failed: recorder.stop threw")
        }
        record?.release()
        record = null
        job?.cancel()
        job = null
    }

    private var onStopLog: ((String) -> Unit)? = null
}
