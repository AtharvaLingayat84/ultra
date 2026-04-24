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

    fun describeEncodedText(text: String, config: AudioConfig): String {
        val payload = textToPayloadBits(text)
        val framed = buildFramedBits(payload)
        val repeated = repeatBits(framed, config.repeatBits)
        val preview = if (repeated.length <= 256) repeated else repeated.take(128) + "..." + repeated.takeLast(64)
        return "framedBits len=${repeated.length} payloadBits=${payload.length} repeatBits=${config.repeatBits} preview=$preview"
    }

    fun textToPayloadBits(text: String): String {
        return buildString {
            text.forEach { ch -> append(ch.code.toString(2).padStart(8, '0')) }
        }
    }

    fun buildFramedBits(payloadBits: String): String {
        return buildString {
            append(Constants.START_MARKER)
            append(payloadBits)
            append(Constants.END_MARKER)
        }
    }

    fun repeatBits(bitstream: String, repeatBits: Int): String {
        return buildString {
            bitstream.forEach { bit -> repeat(repeatBits) { append(bit) } }
        }
    }
}
