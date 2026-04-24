package com.example.ultrasonicchat

data class AudioConfig(
    val sampleRate: Int = Constants.SAMPLE_RATE,
    val bitDurationSeconds: Double = Constants.BIT_DURATION,
    val freq0: Int = Constants.FREQ_0,
    val freq1: Int = Constants.FREQ_1,
    val repeatBits: Int = Constants.REPEAT_BITS,
    val confidenceRatio: Double = Constants.CONFIDENCE_RATIO,
    val noiseFloorMultiplier: Double = Constants.NOISE_FLOOR_MULTIPLIER,
    val bandpassLowCutoff: Float = Constants.BANDPASS_LOW_CUTOFF,
    val bandpassHighCutoff: Float = Constants.BANDPASS_HIGH_CUTOFF,
    val receiveWindowSeconds: Double = Constants.RECEIVE_WINDOW_SECONDS,
    val postDetectCooldownSeconds: Double = Constants.POST_DETECT_COOLDOWN_SECONDS,
    val postDetectTailSeconds: Double = Constants.POST_DETECT_TAIL_SECONDS,
    val txAmplitude: Float = Constants.TX_AMPLITUDE,
    val minSignalDbfs: Float = Constants.MIN_SIGNAL_DBFS,
) {
    val chunkSize: Int = (sampleRate * bitDurationSeconds).toInt().coerceAtLeast(256)
    val hopSize: Int = (chunkSize / 2).coerceAtLeast(128)
}

