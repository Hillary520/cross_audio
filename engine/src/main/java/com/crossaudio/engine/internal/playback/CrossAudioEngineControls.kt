package com.crossaudio.engine

import android.util.Log

internal fun CrossAudioEngine.playImpl() {
    syncQueueFromState()
    inhibitTransitions = false
    val item = queue.getOrNull(index)
    if (item == null) {
        _state.value = PlayerState.Error("Queue is empty")
        emitter.playbackError("Queue is empty", null)
        return
    }

    val currentState = _state.value
    val running = playback.isRunning()
    val paused = playback.isPaused()
    if (currentState is PlayerState.Paused) {
        Log.d(tag, "Play() from Paused posMs=${currentState.positionMs} snapshot=${debugSnapshot()}")
        if (running && paused) {
            playback.resume()
            _state.value = PlayerState.Playing(item, positionMs = currentState.positionMs)
            maybeStartControlLoopImpl()
            return
        }
        _state.value = PlayerState.Buffering
        stopInternalImpl(updateState = false)
        startCurrentTrackImpl(item, startPositionMs = currentState.positionMs) { startedOk ->
            if (!startedOk) return@startCurrentTrackImpl
            _state.value = PlayerState.Playing(item, positionMs = currentState.positionMs)
            maybeStartControlLoopImpl()
        }
        return
    }
    if (currentState is PlayerState.Playing && running && !paused) return

    _state.value = PlayerState.Buffering
    stopInternalImpl(updateState = false)
    startCurrentTrackImpl(item, startPositionMs = 0L) { startedOk ->
        if (!startedOk) return@startCurrentTrackImpl
        _state.value = PlayerState.Playing(item, positionMs = 0L)
        maybeStartControlLoopImpl()
    }
}

internal fun CrossAudioEngine.pauseImpl() {
    syncQueueFromState()
    val item = queue.getOrNull(index) ?: return
    inhibitTransitions = true
    controlJob?.cancel()
    controlJob = null
    cancelTransitionsSyncImpl(cancelPreload = true)
    val fmt = currentFormat
    val pos = if (fmt != null) {
        val framesIntoTrack = (playback.playedFrames() - currentTrackStartFrames).coerceAtLeast(0L)
        currentBasePositionMs + (framesIntoTrack * 1000L) / fmt.sampleRate.toLong()
    } else {
        0L
    }
    Log.d(tag, "Pause() posMs=$pos snapshot=${debugSnapshotImpl()}")
    playback.pause()
    _state.value = PlayerState.Paused(item, pos)
}

internal fun CrossAudioEngine.seekToImpl(positionMs: Long) {
    syncQueueFromState()
    val item = queue.getOrNull(index) ?: return
    val wasPlaying = _state.value is PlayerState.Playing

    _state.value = PlayerState.Buffering
    inhibitTransitions = true
    cancelTransitionsSyncImpl(cancelPreload = true)
    stopInternalImpl(updateState = false)
    startCurrentTrackImpl(item, startPositionMs = positionMs.coerceAtLeast(0L)) { startedOk ->
        if (!startedOk) return@startCurrentTrackImpl
        if (wasPlaying) {
            _state.value = PlayerState.Playing(item, positionMs = positionMs)
            inhibitTransitions = false
            maybeStartControlLoopImpl()
        } else {
            playback.pause()
            _state.value = PlayerState.Paused(item, positionMs = positionMs)
        }
    }
}

internal fun CrossAudioEngine.skipNextImpl() {
    if (queue.isEmpty()) return
    inhibitTransitions = true
    cancelTransitionsSyncImpl(cancelPreload = true)
    val from = index
    val ni = queueState.nextIndexForSkip() ?: return
    syncQueueFromState()
    stop()
    emitter.trackTransition(from, ni, TrackTransitionReason.SKIP)
}

internal fun CrossAudioEngine.skipPreviousImpl() {
    if (queue.isEmpty()) return
    inhibitTransitions = true
    cancelTransitionsSyncImpl(cancelPreload = true)
    val from = index
    val ni = queueState.prevIndexForSkip() ?: return
    syncQueueFromState()
    stop()
    emitter.trackTransition(from, ni, TrackTransitionReason.SKIP)
}
