package com.example.ultrasonicchat

object Decoder {
    fun decodeBits(bits: List<Char>): String {
        if (bits.isEmpty()) return ""

        val bitstream = bits.joinToString(separator = "")
        val start = bitstream.indexOf(Constants.START_MARKER)
        if (start == -1) return ""
        val payloadStart = start + Constants.START_MARKER.length
        val end = bitstream.indexOf(Constants.END_MARKER, payloadStart)
        if (end == -1) return ""

        val payload = bitstream.substring(payloadStart, end)
        val chars = StringBuilder()
        var index = 0
        while (index + 8 <= payload.length) {
            val byte = payload.substring(index, index + 8)
            chars.append(byte.toInt(2).toChar())
            index += 8
        }
        return chars.toString()
    }

    fun decodeAudio(audio: FloatArray, config: AudioConfig, onDebug: (String) -> Unit = {}): String {
        return decodeBits(Demodulator.demodulateBits(audio, config, onDebug))
    }
}
