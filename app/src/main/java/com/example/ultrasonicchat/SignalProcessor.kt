package com.example.ultrasonicchat

import kotlin.math.log10

class SignalProcessor(val config: AudioConfig) {
    private val hp = Demodulator.Biquad.highPass(config.sampleRate.toFloat(), config.bandpassLowCutoff, 0.9f)
    private val lp = Demodulator.Biquad.lowPass(config.sampleRate.toFloat(), config.bandpassHighCutoff, 0.9f)
    
    private val stepSize = maxOf(1, config.chunkSize / 4)
    private val audioBuffer = FloatArray(config.chunkSize + stepSize)
    private var audioCount = 0
    
    private val bitHistory = StringBuilder()
    private val maxBitHistory = 2000
    
    fun process(samples: FloatArray, onLog: (String) -> Unit): String? {
        val filtered = FloatArray(samples.size)
        hp.process(samples, filtered)
        lp.process(filtered, filtered)
        
        for (sample in filtered) {
            audioBuffer[audioCount++] = sample
            if (audioCount == audioBuffer.size) {
                val bit = detectBit(audioBuffer, config)
                bitHistory.append(bit ?: '_')
                if (bitHistory.length > maxBitHistory) {
                    bitHistory.delete(0, bitHistory.length - maxBitHistory)
                }
                
                // Shift buffer
                audioBuffer.copyInto(audioBuffer, 0, stepSize, audioCount)
                audioCount -= stepSize
                
                val message = checkMessage()
                if (message != null) {
                    bitHistory.setLength(0) // Clear history after hit
                    return message
                }
            }
        }
        return null
    }
    
    private fun detectBit(buffer: FloatArray, config: AudioConfig): Char? {
        val energy = chunkEnergy(buffer)
        val minPower = dbfsToPower(config.minSignalDbfs)
        if (energy < minPower) return null
        
        val frame = Demodulator.detectFrequency(buffer, 0, config.chunkSize, config)
        return frame.bit
    }

    private fun chunkEnergy(buffer: FloatArray): Double {
        var sum = 0.0
        for (s in buffer) sum += s * s
        return sum / buffer.size
    }

    private fun dbfsToPower(dbfs: Float): Double {
        return Math.pow(10.0, dbfs / 10.0) // Power ratio
    }

    private fun checkMessage(): String? {
        val stream = bitHistory.toString()
        val oversample = 4
        val totalRepeat = oversample * config.repeatBits
        
        if (stream.length < Constants.START_MARKER.length * totalRepeat) return null
        
        // Try different phases for alignment
        for (phase in 0 until totalRepeat) {
            val downsampled = StringBuilder()
            var i = phase
            while (i + totalRepeat <= stream.length) {
                val group = stream.substring(i, i + totalRepeat)
                val ones = group.count { it == '1' }
                val zeros = group.count { it == '0' }
                if (ones + zeros > 0) {
                    downsampled.append(if (ones >= zeros) '1' else '0')
                } else {
                    downsampled.append('_')
                }
                i += totalRepeat
            }
            
            val s = downsampled.toString()
            if (s.contains(Constants.START_MARKER)) {
                val res = Decoder.decodeBits(s)
                if (res.isNotEmpty()) return res
            }
        }
        return null
    }
}
