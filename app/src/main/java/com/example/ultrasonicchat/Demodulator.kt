package com.example.ultrasonicchat

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Demodulator {
    fun bandPassFilter(audio: FloatArray, config: AudioConfig): FloatArray {
        if (audio.size < 32) return audio.copyOf()

        fun cascade(input: FloatArray): FloatArray {
            var current = input
            current = Biquad.highPass(config.sampleRate.toFloat(), config.bandpassLowCutoff, 0.9f).process(current)
            current = Biquad.lowPass(config.sampleRate.toFloat(), config.bandpassHighCutoff, 0.9f).process(current)
            return current
        }

        val forward = cascade(audio.copyOf())
        val reversed = forward.copyOf().apply { reverse() }
        val backward = cascade(reversed)
        return backward.apply { reverse() }
    }

    private fun chunkEnergy(chunk: FloatArray): Double {
        if (chunk.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in chunk) {
            sum += sample * sample
        }
        return sum / chunk.size
    }

    private fun adaptiveEnergyThreshold(audio: FloatArray, config: AudioConfig): Double {
        if (audio.isEmpty()) return 0.0
        val noiseWindow = minOf(audio.size, (0.5 * config.sampleRate).toInt())
        if (noiseWindow <= 0) return 0.0
        return chunkEnergy(audio.copyOfRange(0, noiseWindow)) * config.noiseFloorMultiplier
    }

    private fun chunkEnergy(audio: FloatArray, start: Int, length: Int): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        val end = minOf(audio.size, start + length)
        for (i in start until end) {
            val s = audio[i]
            sum += s * s
        }
        return sum / length
    }

    private fun goertzelPower(audio: FloatArray, start: Int, length: Int, targetFreq: Int, sampleRate: Int): Double {
        if (length <= 0) return 0.0
        val omega = 2.0 * PI * targetFreq / sampleRate
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        val end = minOf(audio.size, start + length)
        for (i in start until end) {
            val sample = audio[i]
            val s = sample + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        return sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
    }

    fun detectFrequency(audio: FloatArray, start: Int, length: Int, config: AudioConfig): DetectionFrame {
        val power0 = goertzelPower(audio, start, length, config.freq0, config.sampleRate)
        val power1 = goertzelPower(audio, start, length, config.freq1, config.sampleRate)
        val dominantFrequency = if (power1 > power0) config.freq1 else config.freq0
        val ratio = when {
            power0 >= power1 -> power0 / (power1 + 1e-12)
            else -> power1 / (power0 + 1e-12)
        }
        if (ratio <= config.confidenceRatio) {
            return DetectionFrame(null, dominantFrequency, power0, power1, "ignored-low-confidence")
        }
        return when {
            power0 >= power1 -> DetectionFrame('0', dominantFrequency, power0, power1, "0")
            else -> DetectionFrame('1', dominantFrequency, power0, power1, "1")
        }
    }

    data class DetectionFrame(
        val bit: Char?,
        val dominantFrequency: Int,
        val power0: Double,
        val power1: Double,
        val decision: String,
    )

    class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: FloatArray, output: FloatArray = FloatArray(input.size)): FloatArray {
            for (i in input.indices) {
                val x = input[i].toDouble()
                val y = x * b0 + z1
                z1 = x * b1 + z2 - a1 * y
                z2 = x * b2 - a2 * y
                output[i] = y.toFloat()
            }
            return output
        }

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        companion object {
            fun lowPass(sampleRate: Float, frequency: Float, q: Float): Biquad {
                val omega = 2.0 * PI * frequency / sampleRate
                val alpha = sin(omega) / (2.0 * q)
                val cosOmega = cos(omega)
                val b0 = (1.0 - cosOmega) / 2.0
                val b1 = 1.0 - cosOmega
                val b2 = (1.0 - cosOmega) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosOmega
                val a2 = 1.0 - alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun highPass(sampleRate: Float, frequency: Float, q: Float): Biquad {
                val omega = 2.0 * PI * frequency / sampleRate
                val alpha = sin(omega) / (2.0 * q)
                val cosOmega = cos(omega)
                val b0 = (1.0 + cosOmega) / 2.0
                val b1 = -(1.0 + cosOmega)
                val b2 = (1.0 + cosOmega) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosOmega
                val a2 = 1.0 - alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }
}
