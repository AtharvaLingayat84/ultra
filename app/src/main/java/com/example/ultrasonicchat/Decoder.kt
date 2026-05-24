package com.example.ultrasonicchat

object Decoder {
    fun decodeBits(bitstream: String, onDebug: (String) -> Unit = {}): String {
        if (bitstream.isEmpty()) return ""
        val start = bitstream.indexOf(Constants.START_MARKER)
        if (start == -1) return ""
        
        val payloadStart = start + Constants.START_MARKER.length
        val end = bitstream.indexOf(Constants.END_MARKER, payloadStart)
        if (end == -1) return ""

        val payloadBits = bitstream.substring(payloadStart, end)
        if (payloadBits.length < 8 || payloadBits.length % 8 != 0) return ""

        return try {
            val chars = StringBuilder()
            var index = 0
            while (index + 8 <= payloadBits.length) {
                val byteStr = payloadBits.substring(index, index + 8)
                chars.append(byteStr.toInt(2).toChar())
                index += 8
            }
            chars.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun decodeAudio(audio: FloatArray, config: AudioConfig, onDebug: (String) -> Unit = {}): String {
        // This is now a legacy method as we use SignalProcessor for streaming.
        // But let's keep it for compatibility if needed.
        return ""
    }
}
