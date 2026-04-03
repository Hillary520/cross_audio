package com.crossaudio.engine

import android.util.Log

internal fun CrossAudioEngine.setQueueImpl(items: List<MediaItem>, startIndex: Int) {
    queueState.setQueue(items, startIndex)
    syncQueueFromState()
    Log.d(tag, "setQueue size=${queue.size} startIndex=$startIndex currentIndex=$index")
    stop()
    if (items.isEmpty()) {
        _state.value = PlayerState.Idle
    } else {
        _state.value = PlayerState.Paused(items[index], positionMs = 0L)
    }
    emitQueueChanged()
}

internal fun CrossAudioEngine.addToQueueImpl(items: List<MediaItem>, atIndex: Int?) {
    queueState.addToQueue(items, atIndex)
    syncQueueFromState()
    Log.d(tag, "addToQueue added=${items.size} atIndex=${atIndex ?: queue.size} size=${queue.size} currentIndex=$index")
    emitQueueChanged()
}

internal fun CrossAudioEngine.addNextToQueueImpl(items: List<MediaItem>) {
    queueState.addNextToQueue(items)
    syncQueueFromState()
    Log.d(tag, "addNextToQueue added=${items.size} size=${queue.size} currentIndex=$index")
    emitQueueChanged()
}

internal fun CrossAudioEngine.insertQueueItemsImpl(items: List<MediaItem>, atIndex: Int, playbackOrderIndex: Int?) {
    queueState.insertQueueItems(items, atIndex, playbackOrderIndex)
    syncQueueFromState()
    Log.d(tag, "insertQueueItems added=${items.size} atIndex=$atIndex playbackOrderIndex=$playbackOrderIndex size=${queue.size} currentIndex=$index")
    emitQueueChanged()
}

internal fun CrossAudioEngine.replaceQueueItemImpl(index: Int, item: MediaItem) {
    if (!queueState.replaceQueueItem(index, item)) return
    cancelTransitionsSyncImpl(cancelPreload = true)
    syncQueueFromState()
    Log.d(tag, "replaceQueueItem index=$index currentIndex=${this.index}")
    emitQueueChanged()
}

internal fun CrossAudioEngine.removeFromQueueImpl(indices: IntArray) {
    val wasPlaying = _state.value is PlayerState.Playing
    val result = queueState.removeFromQueue(indices)
    if (!result.removedAny) return

    syncQueueFromState()
    emitQueueChanged()

    if (queue.isEmpty()) {
        stopInternalImpl(updateState = false)
        _state.value = PlayerState.Idle
        return
    }
    if (!result.removedCurrent) return

    cancelTransitionsSyncImpl(cancelPreload = true)
    stopInternalImpl(updateState = false)
    val item = queue.getOrNull(index) ?: return
    if (wasPlaying) {
        _state.value = PlayerState.Buffering
        startCurrentTrackImpl(item, 0L) { ok ->
            if (!ok) return@startCurrentTrackImpl
            _state.value = PlayerState.Playing(item, 0L)
            maybeStartControlLoopImpl()
        }
        emitter.trackTransition(-1, index, TrackTransitionReason.QUEUE_MUTATION)
    } else {
        _state.value = PlayerState.Paused(item, 0L)
    }
}

internal fun CrossAudioEngine.moveQueueItemImpl(fromIndex: Int, toIndex: Int) {
    if (!queueState.moveQueueItem(fromIndex, toIndex)) return
    // Reordering can invalidate a preloaded "next" track. Drop transition/preload state so
    // auto-next and crossfade use the new queue order.
    cancelTransitionsSyncImpl(cancelPreload = true)
    syncQueueFromState()
    emitQueueChanged()
}

internal fun CrossAudioEngine.clearQueueImpl() {
    queueState.clearQueue()
    syncQueueFromState()
    stopInternalImpl(updateState = false)
    _state.value = PlayerState.Idle
    emitQueueChanged()
}

internal fun CrossAudioEngine.restoreQueueSnapshotImpl(
    items: List<MediaItem>,
    currentIndex: Int,
    repeatMode: RepeatMode,
    shuffleEnabled: Boolean,
    playOrder: IntArray,
    orderCursor: Int,
) {
    queueState.restoreSnapshot(
        newItems = items,
        startIndex = currentIndex,
        mode = repeatMode,
        shuffle = shuffleEnabled,
        savedOrder = playOrder,
        savedCursor = orderCursor,
    )
    syncQueueFromState()
    Log.d(tag, "restoreQueueSnapshot size=${queue.size} currentIndex=$index shuffle=$shuffleEnabled repeat=$repeatMode")
    stop()
    _state.value = if (items.isEmpty()) {
        PlayerState.Idle
    } else {
        PlayerState.Paused(queue[index], positionMs = 0L)
    }
    emitQueueChanged()
}

internal fun CrossAudioEngine.setShuffleEnabledImpl(enabled: Boolean) {
    queueState.setShuffleEnabled(enabled)
    syncQueueFromState()
    emitter.shuffleChanged(enabled)
    emitQueueChanged()
}

internal fun CrossAudioEngine.isShuffleEnabledImpl(): Boolean = shuffleEnabled
