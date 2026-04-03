package com.crossaudio.engine.internal.events

import com.crossaudio.engine.CacheState
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.PlayerEvent
import com.crossaudio.engine.RepeatMode
import com.crossaudio.engine.TrackTransitionReason

internal class EventEmitter(
    private val bus: EventBus,
) {
    fun queueChanged(
        items: List<MediaItem>,
        playOrder: IntArray,
        size: Int,
        currentIndex: Int,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
    ) {
        bus.emit(
            PlayerEvent.QueueChanged(
                items = items,
                playOrder = playOrder,
                size = size,
                currentIndex = currentIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            ),
        )
    }

    fun shuffleChanged(enabled: Boolean) {
        bus.emit(PlayerEvent.ShuffleChanged(enabled))
    }

    fun trackTransition(from: Int, to: Int, reason: TrackTransitionReason) {
        bus.emit(PlayerEvent.TrackTransition(from, to, reason))
    }

    fun focusChanged(changeCode: Int) {
        bus.emit(PlayerEvent.FocusChanged(changeCode))
    }

    fun becomingNoisy() {
        bus.emit(PlayerEvent.BecomingNoisy)
    }

    fun playbackError(message: String, cause: Throwable?) {
        bus.emit(PlayerEvent.PlaybackError(message, cause?.javaClass?.simpleName))
    }

    fun cacheProgress(cacheKey: String, downloaded: Long, total: Long, pinned: Boolean) {
        bus.emit(PlayerEvent.CacheProgress(cacheKey, downloaded, total, pinned))
    }

    fun cacheState(cacheKey: String, state: CacheState) {
        bus.emit(PlayerEvent.CacheStateChanged(cacheKey, state))
    }
}
