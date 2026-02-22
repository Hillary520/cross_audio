package com.crossaudio.engine

data class AudioProcessingConfig(
    val loudnessEnabled: Boolean = false,
    val targetLufs: Float = -16.0f,
    val limiterEnabled: Boolean = false,
    val limiterCeilingDbfs: Float = -1.0f,
    val limiterAttackMs: Int = 5,
    val limiterReleaseMs: Int = 60,
)
