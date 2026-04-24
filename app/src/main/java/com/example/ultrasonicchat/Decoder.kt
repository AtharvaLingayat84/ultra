package com.example.ultrasonicchat

object Decoder {
    fun decodeBits(bits: List<Char>, onDebug: (String) -> Unit = {}): String {
        if (bits.isEmpty()) {
            onDebug("Decode reject: empty bitstream")
            return ""
        }

        val bitstream = bits.joinToString(separator = "")
        onDebug("Decode stream: bits=${bits.size} bitstream=${bitstream.length}")
        val start = bitstream.indexOf(Constants.START_MARKER)
        if (start == -1) {
            onDebug("Decode reject: START marker missing")
            return ""
        }
        val payloadStart = start + Constants.START_MARKER.length
        val end = bitstream.indexOf(Constants.END_MARKER, payloadStart)
        if (end == -1) {
            onDebug("Decode reject: END marker missing after START marker at index=$start")
            return ""
        }

        val payload = bitstream.substring(payloadStart, end)
        onDebug("Decode frame: start=$start end=$end payloadBits=${payload.length}")
        val chars = StringBuilder()
        var index = 0
        while (index + 8 <= payload.length) {
            val byte = payload.substring(index, index + 8)
            chars.append(byte.toInt(2).toChar())
            index += 8
        }
        onDebug("Decode frame complete: decodedChars=${chars.length}")
        return chars.toString()
    }

    fun decodeAudio(audio: FloatArray, config: AudioConfig, onDebug: (String) -> Unit = {}): String {
        return decodeBits(Demodulator.demodulateBits(audio, config, onDebug), onDebug)
    }
}
