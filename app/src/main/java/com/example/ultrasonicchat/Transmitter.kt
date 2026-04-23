package com.example.ultrasonicchat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class Transmitter {
    suspend fun send(text: String, onLog: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onLog("Transmitter send: encoding message payload")
        val encoded = Encoder.encodeText(text)
        val signal = Modulator.modulate(encoded)
        val padSamples = (0.25 * Constants.SAMPLE_RATE).toInt()
        val tx = AudioUtils.padSignal(signal, padSamples)
        val pcm = AudioUtils.toPcm16(tx)
        onLog("Transmitter send params: sampleRate=${Constants.SAMPLE_RATE}Hz freq0=${Constants.FREQ_0}Hz freq1=${Constants.FREQ_1}Hz amp=${Constants.TX_AMPLITUDE}")

        val minBuffer = AudioTrack.getMinBufferSize(
            Constants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onLog("Transmitter send failure: invalid min buffer size=$minBuffer")
            throw IllegalStateException("AudioTrack min buffer unavailable")
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Constants.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2, Constants.CHUNK_SIZE * 4))
                .build()
        } catch (throwable: Throwable) {
            onLog("Transmitter send failure: ${throwable.message ?: "unable to build AudioTrack"}")
            throw throwable
        }

        try {
            onLog("Transmitter send: play start at ${Constants.FREQ_0}Hz/${Constants.FREQ_1}Hz")
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) {
                    onLog("Transmitter send failure: write failed at offset=$offset")
                    break
                }
                offset += written
            }
            track.stop()
            onLog("Transmitter send: play end")
        } finally {
            track.release()
        }
    }

    suspend fun playAudibleTestSweep(onLog: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val startHz = 500
        val endHz = 4000
        val durationSeconds = 3.0
        val sampleCount = (Constants.SAMPLE_RATE * durationSeconds).toInt()
        val pcm = ShortArray(sampleCount)
        onLog("Test sweep frequencies: start=${startHz}Hz end=${endHz}Hz")

        for (i in pcm.indices) {
            val progress = i.toDouble() / (pcm.size - 1).coerceAtLeast(1)
            val frequency = startHz + (endHz - startHz) * progress
            val amplitude = 0.35
            pcm[i] = (sin(2.0 * PI * frequency * i / Constants.SAMPLE_RATE) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            Constants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onLog("Transmitter send failure: invalid min buffer size=$minBuffer")
            throw IllegalStateException("AudioTrack min buffer unavailable")
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Constants.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2, Constants.CHUNK_SIZE * 4))
                .build()
        } catch (throwable: Throwable) {
            onLog("Transmitter send failure: ${throwable.message ?: "unable to build AudioTrack"}")
            throw throwable
        }

        try {
            onLog("Test sweep start: 500Hz -> 2250Hz -> 4000Hz")
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) {
                    onLog("Test sweep write failed at offset=$offset")
                    break
                }
                offset += written
            }
            track.stop()
            onLog("Test sweep end")
        } finally {
            track.release()
        }
    }
}
