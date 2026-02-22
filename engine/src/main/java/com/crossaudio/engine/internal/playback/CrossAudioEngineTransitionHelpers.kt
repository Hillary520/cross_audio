package com.crossaudio.engine

import android.util.Log
import com.crossaudio.engine.internal.playback.PipeSource
import com.crossaudio.engine.internal.playback.RenderSource

internal fun CrossAudioEngine.setRendererSourceImpl(mode: CrossAudioEngine.RenderMode, src: RenderSource) {
    if (renderMode != mode) {
        Log.d(tag, "Renderer source mode: $renderMode -> $mode")
    }
    renderMode = mode
    playback.setSource(src)
}

internal fun CrossAudioEngine.cancelTransitionsSyncImpl(cancelPreload: Boolean) {
    if (renderMode != CrossAudioEngine.RenderMode.PIPE) {
        Log.d(tag, "Cancelling transitions (mode=$renderMode) snapshot=${debugSnapshotImpl()}")
        val pf = currentFormat
        val pp = currentPipe
        if (pf != null && pp != null) {
            setRendererSourceImpl(CrossAudioEngine.RenderMode.PIPE, PipeSource(pf, pp))
        }
    }

    crossfadeSource = null
    concatSource = null
    fadeOutSource = null

    if (cancelPreload) {
        cancelPreloadSyncImpl()
    }
}

internal fun CrossAudioEngine.cancelPreloadSyncImpl() {
    if (nextDecoder == null && nextPipe == null) return
    Log.d(tag, "Cancelling preload snapshot=${debugSnapshotImpl()}")
    runCatching {
        nextDecoder?.stopAndEos()
        nextPipe?.markEos()
    }
    runCatching { nextDecoder?.join(200) }

    nextDecoder = null
    nextPipe = null
    nextFormat = null
    nextDurationUs = 0L
    nextFailed = false
    nextQueueIndex = -1
}
