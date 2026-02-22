package com.crossaudio.engine

sealed class PlayerEvent {
    data class TrackTransition(
        val fromIndex: Int,
        val toIndex: Int,
        val reason: TrackTransitionReason,
    ) : PlayerEvent()

    data class QueueChanged(
        val size: Int,
        val currentIndex: Int,
        val shuffleEnabled: Boolean,
    ) : PlayerEvent()

    data class ShuffleChanged(val enabled: Boolean) : PlayerEvent()

    data class FocusChanged(val changeCode: Int) : PlayerEvent()

    data object BecomingNoisy : PlayerEvent()

    data class PlaybackError(
        val message: String,
        val causeClass: String? = null,
    ) : PlayerEvent()

    data class CacheProgress(
        val cacheKey: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val pinned: Boolean,
    ) : PlayerEvent()

    data class CacheStateChanged(
        val cacheKey: String,
        val state: CacheState,
    ) : PlayerEvent()
}

enum class TrackTransitionReason {
    END,
    GAPLESS,
    CROSSFADE,
    SKIP,
    QUEUE_MUTATION,
}
