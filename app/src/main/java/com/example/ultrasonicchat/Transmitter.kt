package com.example.ultrasonicchat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Transmitter {
    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        val encoded = Encoder.encodeText(text)
        val signal = Modulator.modulate(encoded)
        val padSamples = (0.25 * Constants.SAMPLE_RATE).toInt()
        val tx = AudioUtils.padSignal(signal, padSamples)
        val pcm = AudioUtils.toPcm16(tx)

        val minBuffer = AudioTrack.getMinBufferSize(
            Constants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2))
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) {
                    break
                }
                offset += written
            }
            track.stop()
        } finally {
            track.release()
        }
    }
}
