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
        onMessage: (String) -> Unit,
    ): Job? {
        if (!running.compareAndSet(false, true)) {
            return job
        }

        val activeJob = scope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(
                Constants.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
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

            val recorder = record ?: run {
                onStatus("Unable to open microphone")
                running.set(false)
                return@launch
            }

            try {
                recorder.startRecording()
                onStatus("Listening")

                while (running.get() && isActive) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) {
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
                        rolling = AudioUtils.takeTail(rolling, tailKeep)
                        cooldownUntil = now + (Constants.POST_DETECT_COOLDOWN_SECONDS * 1000).toLong()
                        onStatus("Message received")
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                recorder.release()
                record = null
                running.set(false)
            }
        }

        job = activeJob
        return activeJob
    }

    fun stop() {
        running.set(false)
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        record?.release()
        record = null
        job?.cancel()
        job = null
    }
}
