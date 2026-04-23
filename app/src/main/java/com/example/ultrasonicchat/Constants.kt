package com.example.ultrasonicchat

object Constants {
    const val SAMPLE_RATE = 44100
    const val BIT_DURATION = 0.1
    const val FREQ_0 = 17800
    const val FREQ_1 = 19000
    const val START_MARKER = "101010111100"
    const val END_MARKER = "001111010101"
    const val REPEAT_BITS = 2

    const val CONFIDENCE_RATIO = 1.2
    const val NOISE_FLOOR_MULTIPLIER = 3.0
    const val RECEIVE_WINDOW_SECONDS = 3.0
    const val POST_DETECT_COOLDOWN_SECONDS = 0.75
    const val POST_DETECT_TAIL_SECONDS = 0.5

    val CHUNK_SIZE: Int = (SAMPLE_RATE * BIT_DURATION).toInt()
    val HOP_SIZE: Int = CHUNK_SIZE / 2
}
