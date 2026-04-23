package com.example.ultrasonicchat

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

    fun start(
        scope: CoroutineScope,
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
                Constants.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                onStatus("Unable to configure microphone")
                onLog("Receive failure: invalid min buffer size=$minBuffer")
                running.set(false)
                return@launch
            }
            val blockFrames = maxOf(1024, Constants.HOP_SIZE)
            val tailKeep = (Constants.SAMPLE_RATE * Constants.POST_DETECT_TAIL_SECONDS).toInt()
            val maxBuffer = (Constants.SAMPLE_RATE * Constants.RECEIVE_WINDOW_SECONDS).toInt()
            val readBuffer = ShortArray(blockFrames)
            var rolling = FloatArray(0)
            var cooldownUntil = 0L

            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Constants.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, blockFrames * 2))
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
                onLog("Receive start: AudioRecord starting")
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

                    if (rolling.size < Constants.CHUNK_SIZE * 3) {
                        continue
                    }

                    val windowSize = (Constants.SAMPLE_RATE * Constants.RECEIVE_WINDOW_SECONDS).toInt()
                    val latestWindow = if (rolling.size > windowSize) {
                        rolling.copyOfRange(rolling.size - windowSize, rolling.size)
                    } else {
                        rolling
                    }

                    val message = Decoder.decodeAudio(latestWindow)
                    if (message.isNotEmpty()) {
                        onMessage(message)
                        onLog("Decode hit: message length=${message.length}")
                        rolling = AudioUtils.takeTail(rolling, tailKeep)
                        cooldownUntil = now + (Constants.POST_DETECT_COOLDOWN_SECONDS * 1000).toLong()
                        onStatus("Message received")
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
