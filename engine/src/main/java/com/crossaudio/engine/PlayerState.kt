package com.crossaudio.engine

sealed class PlayerState {
    data object Idle : PlayerState()
    data object Buffering : PlayerState()
    data class Playing(
        val item: MediaItem,
        val positionMs: Long,
    ) : PlayerState()
    data class Paused(
        val item: MediaItem,
        val positionMs: Long,
    ) : PlayerState()
    data class Ended(
        val item: MediaItem,
    ) : PlayerState()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : PlayerState()
}

