package com.example.ultrasonicchat

object Encoder {
    fun encodeText(text: String, config: AudioConfig): String {
        val payload = buildString {
            text.forEach { ch -> append(ch.code.toString(2).padStart(8, '0')) }
        }
        val framed = buildString {
            append(Constants.START_MARKER)
            append(payload)
            append(Constants.END_MARKER)
        }
        return buildString {
            framed.forEach { bit -> repeat(config.repeatBits) { append(bit) } }
        }
    }
}
