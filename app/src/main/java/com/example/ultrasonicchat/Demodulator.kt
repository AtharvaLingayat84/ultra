package com.example.ultrasonicchat

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Demodulator {
    fun bandPassFilter(audio: FloatArray): FloatArray {
        if (audio.size < 32) return audio.copyOf()

        var current = audio.copyOf()
        repeat(3) {
            current = Biquad.highPass(Constants.SAMPLE_RATE.toFloat(), 900f, 0.707f).process(current)
            current = Biquad.lowPass(Constants.SAMPLE_RATE.toFloat(), 2600f, 0.707f).process(current)
        }
        return current
    }

    private fun goertzelPower(chunk: FloatArray, targetFreq: Int): Double {
        if (chunk.isEmpty()) return 0.0
        val omega = 2.0 * PI * targetFreq / Constants.SAMPLE_RATE
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (sample in chunk) {
            val s = sample + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        return sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
    }

    private fun chunkEnergy(chunk: FloatArray): Double {
        if (chunk.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in chunk) {
            sum += sample * sample
        }
        return sum / chunk.size
    }

    private fun adaptiveEnergyThreshold(audio: FloatArray): Double {
        if (audio.isEmpty()) return 0.0
        val noiseWindow = minOf(audio.size, (0.5 * Constants.SAMPLE_RATE).toInt())
        if (noiseWindow <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until noiseWindow) {
            val sample = audio[i]
            sum += sample * sample
        }
        val noiseFloor = sum / noiseWindow
        return noiseFloor * Constants.NOISE_FLOOR_MULTIPLIER
    }

    fun detectFrequency(chunk: FloatArray): Char? {
        if (chunk.isEmpty()) return null
        val power0 = goertzelPower(chunk, Constants.FREQ_0)
        val power1 = goertzelPower(chunk, Constants.FREQ_1)
        if (power0 <= 0.0 && power1 <= 0.0) return null
        return if (power0 >= power1) {
            val ratio = power0 / (power1 + 1e-12)
            if (ratio > Constants.CONFIDENCE_RATIO) '0' else null
        } else {
            val ratio = power1 / (power0 + 1e-12)
            if (ratio > Constants.CONFIDENCE_RATIO) '1' else null
        }
    }

    private fun scoreStream(bitstream: String): Int {
        if (bitstream.isEmpty()) return -1
        var score = 0
        var start = bitstream.indexOf(Constants.START_MARKER)
        if (start != -1) {
            score += 1000
            start += Constants.START_MARKER.length
            val end = bitstream.indexOf(Constants.END_MARKER, start)
            if (end != -1) {
                score += 1000
                val payload = bitstream.substring(start, end)
                if (payload.length >= 8) {
                    score += payload.length
                    if (payload.length % 8 == 0) {
                        score += 100
                    }
                }
            }
        }
        return score
    }

    private fun smoothDetections(rawBits: List<Char?>): List<Char?> {
        if (rawBits.size < 3) return rawBits
        val smoothed = ArrayList<Char?>(rawBits.size - 2)
        for (i in 0 until rawBits.size - 2) {
            val window = rawBits.subList(i, i + 3).filter { it == '0' || it == '1' }
            if (window.size < 2) {
                smoothed.add(null)
                continue
            }
            val ones = window.count { it == '1' }
            val zeros = window.size - ones
            when {
                ones > zeros -> smoothed.add('1')
                zeros > ones -> smoothed.add('0')
                else -> smoothed.add(null)
            }
        }
        return smoothed
    }

    private fun collapseRepeats(rawBits: List<Char?>): String {
        var bestStream = ""
        var bestScore = -1

        for (phase in 0 until Constants.REPEAT_BITS) {
            val reduced = StringBuilder()
            var index = phase
            while (index + Constants.REPEAT_BITS <= rawBits.size) {
                val group = rawBits.subList(index, index + Constants.REPEAT_BITS)
                val valid = group.filter { it == '0' || it == '1' }
                if (valid.isNotEmpty()) {
                    val ones = valid.count { it == '1' }
                    val zeros = valid.size - ones
                    reduced.append(if (ones >= zeros) '1' else '0')
                }
                index += Constants.REPEAT_BITS
            }
            val stream = reduced.toString()
            val score = scoreStream(stream)
            if (score > bestScore) {
                bestScore = score
                bestStream = stream
            }
        }

        return bestStream
    }

    fun demodulateBits(audio: FloatArray): List<Char> {
        val filtered = bandPassFilter(audio)
        if (filtered.size < Constants.CHUNK_SIZE) return emptyList()

        val energyThreshold = adaptiveEnergyThreshold(filtered)
        val step = maxOf(1, Constants.HOP_SIZE / 8)
        var bestStream = ""
        var bestScore = -1

        var offset = 0
        while (offset < Constants.HOP_SIZE) {
            val rawBits = ArrayList<Char?>(filtered.size / Constants.HOP_SIZE + 1)
            var start = offset
            while (start + Constants.CHUNK_SIZE <= filtered.size) {
                val chunk = filtered.copyOfRange(start, start + Constants.CHUNK_SIZE)
                if (chunkEnergy(chunk) < energyThreshold) {
                    rawBits.add(null)
                } else {
                    rawBits.add(detectFrequency(chunk))
                }
                start += Constants.HOP_SIZE
            }

            val stream = collapseRepeats(smoothDetections(rawBits))
            val score = scoreStream(stream)
            if (score > bestScore) {
                bestScore = score
                bestStream = stream
            }
            offset += step
        }

        return bestStream.toList()
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: FloatArray): FloatArray {
            val output = FloatArray(input.size)
            for (i in input.indices) {
                val x = input[i].toDouble()
                val y = x * b0 + z1
                z1 = x * b1 + z2 - a1 * y
                z2 = x * b2 - a2 * y
                output[i] = y.toFloat()
            }
            return output
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
