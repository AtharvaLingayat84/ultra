package com.example.ultrasonicchat

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunicationTest {
    @Test
    fun testEndToEnd() {
        val config = AudioConfig(repeatBits = 1)
        val text = "Hello"
        val encodedBits = Encoder.encodeText(text, config)
        
        // Manual decode
        val decoded = Decoder.decodeBits(encodedBits.toList())
        assertEquals(text, decoded)
    }

    @Test
    fun testSignalProcessor() {
        val config = AudioConfig(repeatBits = 1)
        val text = "Hi"
        val encodedBits = Encoder.encodeText(text, config)
        val signal = Modulator.modulate(encodedBits, config)
        
        val processor = SignalProcessor(config)
        var result: String? = null
        
        // Process in small chunks to simulate streaming
        val chunkSize = 1024
        var offset = 0
        while (offset < signal.size) {
            val end = minOf(offset + chunkSize, signal.size)
            val chunk = signal.copyOfRange(offset, end)
            val res = processor.process(chunk) { }
            if (res != null) result = res
            offset += chunkSize
        }
        
        assertEquals(text, result)
    }
}
