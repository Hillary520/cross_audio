package com.crossaudio.engine.internal.audio

internal data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
)

