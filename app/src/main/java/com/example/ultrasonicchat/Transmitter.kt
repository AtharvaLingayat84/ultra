package com.example.ultrasonicchat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class Transmitter {
    suspend fun send(text: String, config: AudioConfig, onLog: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onLog("Transmitter send: encoding message payload")
        val encoded = Encoder.encodeText(text, config)
        val signal = Modulator.modulate(encoded, config)
        val padSamples = (0.25 * config.sampleRate).toInt()
        val tx = AudioUtils.padSignal(signal, padSamples)
        val pcm = AudioUtils.toPcm16(tx)
        onLog("Transmitter send params: sampleRate=${config.sampleRate}Hz freq0=${config.freq0}Hz freq1=${config.freq1}Hz amp=${config.txAmplitude} pcmSize=${pcm.size}")

        val minBuffer = AudioTrack.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onLog("Transmitter send failure: invalid min buffer size=$minBuffer")
            throw IllegalStateException("AudioTrack min buffer unavailable")
        }
        
        val bufferSize = maxOf(minBuffer, config.chunkSize * 8)
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
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (throwable: Throwable) {
            onLog("Transmitter send failure: ${throwable.message ?: "unable to build AudioTrack"}")
            throw throwable
        }

        try {
            onLog("Transmitter send: play start at ${config.freq0}Hz/${config.freq1}Hz amplitude=${config.txAmplitude} bufferSize=$bufferSize")
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val toWrite = minOf(config.chunkSize, pcm.size - offset)
                val written = track.write(pcm, offset, toWrite)
                if (written <= 0) {
                    onLog("Transmitter send failure: write failed at offset=$offset written=$written")
                    break
                }
                offset += written
            }
            val durationMs = ((pcm.size.toDouble() / config.sampleRate) * 1000.0).toLong()
            Thread.sleep(durationMs + 250L)
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
        
        val bufferSize = maxOf(minBuffer, pcm.size * 2)
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
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (throwable: Throwable) {
            onLog("Transmitter send failure: ${throwable.message ?: "unable to build AudioTrack"}")
            throw throwable
        }

        try {
            onLog("Test sweep start: 500Hz -> 4000Hz")
            track.setVolume(AudioTrack.getMaxVolume())
            var offset = 0
            while (offset < pcm.size) {
                val toWrite = pcm.size - offset
                val written = track.write(pcm, offset, toWrite)
                if (written <= 0) {
                    onLog("Test sweep write failed at offset=$offset")
                    break
                }
                offset += written
            }
            track.play()
            val durationMs = ((pcm.size.toDouble() / Constants.SAMPLE_RATE) * 1000.0).toLong()
            Thread.sleep(durationMs + 250L)
            track.stop()
            onLog("Test sweep end")
        } finally {
            track.release()
        }
    }
}
