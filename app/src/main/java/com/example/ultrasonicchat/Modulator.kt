package com.example.ultrasonicchat

import kotlin.math.PI
import kotlin.math.sin

object Modulator {
    fun modulate(bits: String, config: AudioConfig): FloatArray {
        val samplesPerBit = config.chunkSize
        if (bits.isEmpty()) return FloatArray(0)

        val signal = FloatArray(bits.length * samplesPerBit)

        for (i in bits.indices) {
            val freq = if (bits[i] == '1') config.freq1 else config.freq0
            val baseIndex = i * samplesPerBit
            for (j in 0 until samplesPerBit) {
                val sampleIndex = baseIndex + j
                val t = sampleIndex.toDouble() / config.sampleRate
                signal[sampleIndex] = (config.txAmplitude * sin(2.0 * PI * freq * t)).toFloat()
            }
        }

        val fadeSamples = maxOf(1, (0.005 * config.sampleRate).toInt())
        val envelope = FloatArray(signal.size) { 1f }
        val rampSize = minOf(fadeSamples, signal.size / 2)
        for (i in 0 until rampSize) {
            val value = (i.toFloat() / rampSize.toFloat()).let { t -> t * t }
            envelope[i] = value
        }
        for (i in 0 until rampSize) {
            val value = ((rampSize - i).toFloat() / rampSize.toFloat()).let { t -> t * t }
            envelope[signal.lastIndex - i] = value
        }

        val out = FloatArray(signal.size)
        for (i in signal.indices) {
            out[i] = signal[i] * envelope[i]
        }
        return out
    }
}
