package com.example.ultrasonicchat

import kotlin.math.PI
import kotlin.math.sin

object Modulator {
    fun modulate(bits: String): FloatArray {
        val samplesPerBit = Constants.CHUNK_SIZE
        if (bits.isEmpty()) return FloatArray(0)

        val timeStep = 1.0 / Constants.SAMPLE_RATE
        val chunk = FloatArray(samplesPerBit)
        val signal = FloatArray(bits.length * samplesPerBit)

        for (i in bits.indices) {
            val freq = if (bits[i] == '1') Constants.FREQ_1 else Constants.FREQ_0
            for (j in 0 until samplesPerBit) {
                val t = j * timeStep
                chunk[j] = (0.5 * sin(2.0 * PI * freq * t)).toFloat()
            }
            chunk.copyInto(signal, i * samplesPerBit)
        }

        val fadeSamples = maxOf(1, (0.005 * Constants.SAMPLE_RATE).toInt())
        val envelope = FloatArray(signal.size) { 1f }
        val rampSize = minOf(fadeSamples, signal.size)
        for (i in 0 until rampSize) {
            val value = i.toFloat() / rampSize.toFloat()
            envelope[i] = value
            envelope[signal.lastIndex - i] = value
        }

        val out = FloatArray(signal.size)
        for (i in signal.indices) {
            out[i] = signal[i] * envelope[i]
        }
        return out
    }
}
