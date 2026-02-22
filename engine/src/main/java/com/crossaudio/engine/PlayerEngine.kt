package com.crossaudio.engine

import kotlinx.coroutines.flow.StateFlow

interface PlayerEngine {
    val state: StateFlow<PlayerState>

    fun setQueue(items: List<MediaItem>, startIndex: Int = 0)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()

    fun setCrossfadeDurationMs(durationMs: Long)

    fun setRepeatMode(mode: RepeatMode)

    /**
     * 0.0 (silent) .. 1.0 (full scale). Applies to the final output.
     */
    fun setVolume(volume: Float)

    fun release()
}
