package com.example.ultrasonicchat

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Demodulator {
    fun bandPassFilter(audio: FloatArray, config: AudioConfig): FloatArray {
        if (audio.size < 32) return audio.copyOf()

        var current = audio.copyOf()
        current = Biquad.highPass(config.sampleRate.toFloat(), config.bandpassLowCutoff, 0.9f).process(current)
        current = Biquad.lowPass(config.sampleRate.toFloat(), config.bandpassHighCutoff, 0.9f).process(current)
        return current
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

    private fun goertzelPower(chunk: FloatArray, targetFreq: Int, sampleRate: Int): Double {
        if (chunk.isEmpty()) return 0.0
        val omega = 2.0 * PI * targetFreq / sampleRate
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

    data class DetectionFrame(
        val bit: Char?,
        val dominantFrequency: Int,
        val power0: Double,
        val power1: Double,
        val decision: String,
    )

    fun detectFrequency(chunk: FloatArray, config: AudioConfig): DetectionFrame {
        if (chunk.isEmpty()) {
            return DetectionFrame(null, 0, 0.0, 0.0, "ignored")
        }
        val power0 = goertzelPower(chunk, config.freq0, config.sampleRate)
        val power1 = goertzelPower(chunk, config.freq1, config.sampleRate)
        val dominantFrequency = if (power1 > power0) config.freq1 else config.freq0
        val ratio = when {
            power0 >= power1 -> power0 / (power1 + 1e-12)
            else -> power1 / (power0 + 1e-12)
        }
        if (ratio < config.confidenceRatio) {
            return DetectionFrame(null, dominantFrequency, power0, power1, "ignored-low-confidence")
        }
        return when {
            power0 >= power1 -> DetectionFrame('0', dominantFrequency, power0, power1, "0")
            else -> DetectionFrame('1', dominantFrequency, power0, power1, "1")
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
        if (rawBits.isEmpty()) return rawBits
        val smoothed = ArrayList<Char?>(rawBits.size)
        for (i in rawBits.indices) {
            val windowStart = maxOf(0, i - 2)
            val window = rawBits.subList(windowStart, i + 1).filter { it == '0' || it == '1' }
            if (window.size < 2) {
                smoothed.add(rawBits[i])
                continue
            }
            val zeros = window.count { it == '0' }
            val ones = window.count { it == '1' }
            smoothed.add(
                when {
                    ones > zeros -> '1'
                    zeros > ones -> '0'
                    else -> null
                },
            )
        }
        return smoothed
    }

    private fun collapseRepeats(rawBits: List<Char?>, repeatBits: Int): String {
        var bestStream = ""
        var bestScore = -1

        for (phase in 0 until repeatBits) {
            val reduced = StringBuilder()
            var index = phase
            while (index + repeatBits <= rawBits.size) {
                val group = rawBits.subList(index, index + repeatBits)
                val valid = group.filter { it == '0' || it == '1' }
                if (valid.isNotEmpty()) {
                    val ones = valid.count { it == '1' }
                    val zeros = valid.size - ones
                    reduced.append(if (ones >= zeros) '1' else '0')
                }
                index += repeatBits
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

    fun demodulateBits(audio: FloatArray, config: AudioConfig, onDebug: (String) -> Unit = {}): List<Char> {
        val filtered = bandPassFilter(audio, config)
        if (filtered.size < config.chunkSize) return emptyList()

        val energyThreshold = adaptiveEnergyThreshold(filtered, config)
        val minSignalPower = dbfsToPower(config.minSignalDbfs)
        onDebug(
            "Demod start: size=${filtered.size} chunk=${config.chunkSize} hop=${config.hopSize} freq0=${config.freq0}Hz freq1=${config.freq1}Hz band=${config.bandpassLowCutoff}-${config.bandpassHighCutoff}Hz energyTh=${"%.3e".format(energyThreshold)} minPower=${"%.3e".format(minSignalPower)}",
        )

        val step = maxOf(1, config.hopSize / 8)
        var bestStream = ""
        var bestScore = -1

        var offset = 0
        while (offset < config.hopSize) {
            val rawBits = ArrayList<Char?>(filtered.size / config.hopSize + 1)
            var start = offset
            while (start + config.chunkSize <= filtered.size) {
                val chunk = filtered.copyOfRange(start, start + config.chunkSize)
                val energy = chunkEnergy(chunk)
                val frameThreshold = maxOf(energyThreshold, minSignalPower)
                val frame = detectFrequency(chunk, config)
                if (frame.bit == null && energy < frameThreshold) {
                    rawBits.add(null)
                    onDebug(
                        "Demod frame: energy=${"%.3e".format(energy)} frame=${frame.decision} rejected low-energy threshold=${"%.3e".format(frameThreshold)}",
                    )
                    start += config.hopSize
                    continue
                }

                rawBits.add(frame.bit)
                onDebug(
                    "Demod frame: energy=${"%.3e".format(energy)} dom=${frame.dominantFrequency}Hz p0=${"%.3e".format(frame.power0)} p1=${"%.3e".format(frame.power1)} decision=${frame.decision} accepted=${frame.bit != null || energy >= frameThreshold}",
                )
                start += config.hopSize
            }

            val stream = collapseRepeats(smoothDetections(rawBits), config.repeatBits)
            val score = scoreStream(stream)
            if (score > bestScore) {
                bestScore = score
                bestStream = stream
            }
            offset += step
        }

        return bestStream.toList()
    }

    private fun dbfsToPower(dbfs: Float): Double {
        val amplitude = Math.pow(10.0, dbfs / 20.0)
        return amplitude * amplitude
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
