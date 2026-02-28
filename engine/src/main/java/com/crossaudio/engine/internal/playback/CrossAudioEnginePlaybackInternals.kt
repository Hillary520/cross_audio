package com.crossaudio.engine

import android.media.AudioManager
import android.util.Log
import com.crossaudio.engine.internal.network.ResolvedMediaSource
import com.crossaudio.engine.internal.playback.DecoderSession
import com.crossaudio.engine.internal.playback.PipeSource
import kotlinx.coroutines.launch

internal fun CrossAudioEngine.stopInternalImpl(updateState: Boolean) {
    generation.incrementAndGet()
    activeGeneration = 0L
    inhibitTransitions = true
    renderStop.set(true)
    controlJob?.cancel()
    controlJob = null
    playback.stopRenderer()
    currentDecoder?.stopAndEos()
    nextDecoder?.stopAndEos()
    currentPipe?.markEos()
    nextPipe?.markEos()
    currentDecoder?.join(300)
    nextDecoder?.join(300)
    currentDecoder = null
    currentPipe = null
    currentFormat = null
    currentDurationUs = 0L
    currentBasePositionMs = 0L

    nextDecoder = null
    nextPipe = null
    nextFormat = null
    nextDurationUs = 0L
    nextFailed = false
    nextQueueIndex = -1
    crossfadeSource = null
    concatSource = null
    fadeOutSource = null
    renderMode = CrossAudioEngine.RenderMode.PIPE
    currentMimeType = null
    qualityInfo = qualityInfo.copy(
        bitrateKbps = null,
        representationId = null,
    )

    if (updateState) {
        val item = queue.getOrNull(index)
        _state.value = if (item == null) PlayerState.Idle else PlayerState.Paused(item, positionMs = 0L)
    }
}

internal fun CrossAudioEngine.startCurrentTrackImpl(
    item: MediaItem,
    startPositionMs: Long,
    onStarted: (Boolean) -> Unit,
) {
    val gen = generation.incrementAndGet()
    activeGeneration = gen
    inhibitTransitions = false

    renderStop.set(false)
    var started = false
    currentBasePositionMs = startPositionMs.coerceAtLeast(0L)
    var sessionRef: DecoderSession? = null

    Log.d(tag, "Starting track index=$index uri=${item.uri}")
    val resolved = sourceResolver.resolve(item)
    val sourceType = (resolved as? ResolvedMediaSource.RemoteHttp)?.sourceType
        ?: manifestResolver.detectSourceType(item)
    val manifestInitDataBase64 = (resolved as? ResolvedMediaSource.RemoteHttp)?.manifestInitDataBase64
    val streamId = when (resolved) {
        is ResolvedMediaSource.RemoteHttp -> resolved.url
        is ResolvedMediaSource.LocalFile -> resolved.path
        is ResolvedMediaSource.LocalUri -> resolved.uri.toString()
    }
    qualityInfo = qualityInfo.copy(
        sourceType = sourceType,
        representationId = streamId,
        bitrateKbps = null,
    )
    currentMimeType = null
    when (resolved) {
        is ResolvedMediaSource.LocalFile -> emitTelemetry(EngineTelemetryEvent.CacheHit(item.cacheKey ?: item.uri.toString()))
        is ResolvedMediaSource.RemoteHttp -> emitTelemetry(EngineTelemetryEvent.CacheMiss(item.cacheKey ?: item.uri.toString()))
        is ResolvedMediaSource.LocalUri -> Unit
    }

    val decoder = DecoderSession(
        context = appContext,
        resolver = sourceResolver,
        resolvedSource = resolved,
        item = item,
        sourceType = sourceType,
        streamingConfig = streamingConfig,
        drmSessionManager = drmManager,
        manifestInitDataBase64 = manifestInitDataBase64,
        onSegmentFetched = { segmentUrl, bytes, downloadMs ->
            cacheManager.recordSegmentResource(item, segmentUrl, bytes)
            onSegmentDownloadSample(bytes = bytes, downloadMs = downloadMs)
        },
        onAdaptiveBitrateRequest = { availableBitrates, bufferedMs, currentBitrate ->
            chooseAdaptiveBitrate(
                availableBitratesKbps = availableBitrates,
                bufferedMs = bufferedMs,
                currentBitrateKbps = currentBitrate,
            )
        },
        onAdaptiveSwitch = { streamUri, bitrateKbps, reason ->
            onAdaptiveStreamSwitch(streamUri, bitrateKbps, reason)
        },
        startPositionMs = startPositionMs,
        outputSampleRate = 48000,
        capacitySamples = currentPipeCapacitySamples,
        onSourceInfo = onSource@{ mimeType, bitrateKbps ->
            if (activeGeneration != gen) return@onSource
            currentMimeType = mimeType
            qualityInfo = qualityInfo.copy(
                bitrateKbps = bitrateKbps ?: qualityInfo.bitrateKbps,
            )
            bitrateKbps?.takeIf { it > 0 }?.let { selected ->
                emitTelemetry(
                    EngineTelemetryEvent.AbrDecision(
                        selectedBitrateKbps = selected,
                        estimatedBandwidthKbps = selected,
                        reason = "source_info",
                    ),
                )
            }
            Log.d(tag, "Source info mime=$mimeType bitrateKbps=${bitrateKbps ?: "unknown"}")
        },
        onFormat = onFormat@{ fmt, durUs ->
            val session = sessionRef ?: return@onFormat
            if (activeGeneration != gen) return@onFormat
            if (currentFormat != null) return@onFormat

            currentFormat = fmt
            currentDurationUs = durUs
            currentPipe = session.pipe

            Log.d(
                tag,
                "Track format sr=${currentFormat?.sampleRate} ch=${currentFormat?.channelCount} durationUs=$currentDurationUs",
            )

            val pf = currentFormat ?: return@onFormat
            if (started || renderStop.get()) return@onFormat

            started = true
            val src = PipeSource(pf, session.pipe)
            val requestedSession = outputAudioSessionId.get().takeIf { it > 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE
            playback.startRenderer(
                format = pf,
                source = src,
                volume = volume,
                requestedSessionId = requestedSession,
                onSessionId = { sid ->
                    if (sid > 0) {
                        val prev = outputAudioSessionId.getAndSet(sid)
                        _audioSessionId.value = sid
                        if (prev != sid) Log.d(tag, "Output audio session id updated: $prev -> $sid")
                    }
                },
                onEnded = { onRendererEndedImpl() },
                onError = { t ->
                    emitter.playbackError("Render error: ${t.message}", t)
                    _state.value = PlayerState.Error("Render error: ${t.message}", t)
                },
            )
            currentTrackStartFrames = 0L
            renderMode = CrossAudioEngine.RenderMode.PIPE
            Log.d(tag, "Renderer started for index=$index")
            onStarted(true)
        },
        onError = onErr@{ t ->
            if (activeGeneration != gen) return@onErr
            emitter.playbackError("Decode error: ${t.message}", t)
            _state.value = PlayerState.Error("Decode error: ${t.message}", t)
        },
    )
    sessionRef = decoder
    currentDecoder = decoder
    decoder.start()

    scope.launch {
        val startupTimeoutMs = when (resolved) {
            is ResolvedMediaSource.RemoteHttp -> 20_000L
            else -> 7_500L
        }
        kotlinx.coroutines.delay(startupTimeoutMs)
        if (activeGeneration == gen && !started && _state.value is PlayerState.Buffering) {
            stop()
            _state.value = PlayerState.Error("Timed out waiting for decoder output format")
            Log.w(tag, "Timed out waiting for decoder output format index=$index uri=${item.uri}")
            emitter.playbackError("Timed out waiting for decoder output format", null)
            onStarted(false)
        }
    }
}

internal fun CrossAudioEngine.startNextTrackIfNeededImpl() {
    syncQueueFromState()
    if (nextDecoder != null) return
    val ni = queueState.peekNextIndexForAuto()
    if (ni == null) return
    val nextItem = queue.getOrNull(ni) ?: return
    nextQueueIndex = ni

    Log.d(tag, "Preloading next track index=$ni")
    val resolved = sourceResolver.resolve(nextItem)
    val sourceType = (resolved as? ResolvedMediaSource.RemoteHttp)?.sourceType
        ?: manifestResolver.detectSourceType(nextItem)
    val manifestInitDataBase64 = (resolved as? ResolvedMediaSource.RemoteHttp)?.manifestInitDataBase64
    when (resolved) {
        is ResolvedMediaSource.LocalFile -> emitTelemetry(EngineTelemetryEvent.CacheHit(nextItem.cacheKey ?: nextItem.uri.toString()))
        is ResolvedMediaSource.RemoteHttp -> emitTelemetry(EngineTelemetryEvent.CacheMiss(nextItem.cacheKey ?: nextItem.uri.toString()))
        is ResolvedMediaSource.LocalUri -> Unit
    }

    nextFailed = false
    var sessionRef: DecoderSession? = null
    val decoder = DecoderSession(
        context = appContext,
        resolver = sourceResolver,
        resolvedSource = resolved,
        item = nextItem,
        sourceType = sourceType,
        streamingConfig = streamingConfig,
        drmSessionManager = drmManager,
        manifestInitDataBase64 = manifestInitDataBase64,
        onSegmentFetched = { segmentUrl, bytes, downloadMs ->
            cacheManager.recordSegmentResource(nextItem, segmentUrl, bytes)
            onSegmentDownloadSample(bytes = bytes, downloadMs = downloadMs)
        },
        onAdaptiveBitrateRequest = { availableBitrates, bufferedMs, currentBitrate ->
            chooseAdaptiveBitrate(
                availableBitratesKbps = availableBitrates,
                bufferedMs = bufferedMs,
                currentBitrateKbps = currentBitrate,
            )
        },
        onAdaptiveSwitch = { _, _, _ -> Unit },
        startPositionMs = 0L,
        outputSampleRate = 48000,
        capacitySamples = nextPipeCapacitySamples,
        onFormat = fmtCb@{ fmt, durUs ->
            val session = sessionRef ?: return@fmtCb
            if (nextDecoder !== session) return@fmtCb
            nextFormat = fmt
            nextDurationUs = durUs
            nextPipe = session.pipe
        },
        onError = errCb@{ t ->
            Log.w(tag, "Next decode failed; crossfade will be skipped", t)
            val session = sessionRef ?: return@errCb
            if (nextDecoder !== session) return@errCb
            nextFailed = true
            runCatching { session.stopAndEos() }
        },
    )
    sessionRef = decoder
    nextDecoder = decoder
    decoder.start()
}

internal fun CrossAudioEngine.onRendererEndedImpl() {
    val gen = activeGeneration
    val endedIndex = index
    val endedItem = queue.getOrNull(endedIndex)
    Log.d(tag, "Renderer ended at index=$endedIndex item=$endedItem")

    renderStop.set(true)

    scope.launch {
        if (activeGeneration != gen) return@launch
        stopInternalImpl(updateState = false)

        if (endedItem == null) {
            _state.value = PlayerState.Idle
            return@launch
        }

        queueState.setCurrentIndex(endedIndex)
        val ni = queueState.nextIndexForAuto()
        if (ni != null) {
            syncQueueFromState()
            val nextItem = queue[index]
            _state.value = PlayerState.Buffering
            startCurrentTrackImpl(nextItem, startPositionMs = 0L) { startedOk ->
                if (!startedOk) return@startCurrentTrackImpl
                _state.value = PlayerState.Playing(nextItem, positionMs = 0L)
                emitter.trackTransition(endedIndex, ni, TrackTransitionReason.END)
                maybeStartControlLoopImpl()
            }
        } else {
            _state.value = PlayerState.Ended(endedItem)
        }
    }
}
