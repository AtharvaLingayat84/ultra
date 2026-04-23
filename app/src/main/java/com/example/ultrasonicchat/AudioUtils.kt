package com.example.ultrasonicchat

object AudioUtils {
    fun toPcm16(samples: FloatArray): ShortArray {
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            pcm[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    fun toFloats(samples: ShortArray, length: Int = samples.size): FloatArray {
        val out = FloatArray(length)
        for (i in 0 until length) {
            out[i] = samples[i] / Short.MAX_VALUE.toFloat()
        }
        return out
    }

    fun appendBounded(existing: FloatArray, chunk: FloatArray, maxSize: Int): FloatArray {
        if (chunk.isEmpty()) return existing.copyOf()
        val mergedSize = existing.size + chunk.size
        val merged = FloatArray(mergedSize)
        existing.copyInto(merged, 0, 0, existing.size)
        chunk.copyInto(merged, existing.size, 0, chunk.size)
        return if (mergedSize <= maxSize) merged else merged.copyOfRange(mergedSize - maxSize, mergedSize)
    }

    fun takeTail(samples: FloatArray, count: Int): FloatArray {
        if (count <= 0) return floatArrayOf()
        if (samples.size <= count) return samples.copyOf()
        return samples.copyOfRange(samples.size - count, samples.size)
    }

    fun padSignal(signal: FloatArray, padSamples: Int): FloatArray {
        if (padSamples <= 0) return signal.copyOf()
        val out = FloatArray(signal.size + padSamples * 2)
        signal.copyInto(out, padSamples, 0, signal.size)
        return out
    }
}
