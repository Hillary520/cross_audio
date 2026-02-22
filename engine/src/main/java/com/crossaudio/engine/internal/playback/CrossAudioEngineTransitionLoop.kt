package com.crossaudio.engine

import android.util.Log
import com.crossaudio.engine.internal.playback.ConcatSource
import com.crossaudio.engine.internal.playback.CrossfadeSource
import com.crossaudio.engine.internal.playback.FadeOutSource
import com.crossaudio.engine.internal.playback.PipeSource
import com.crossaudio.engine.internal.playback.TransitionController
import kotlinx.coroutines.launch

internal fun CrossAudioEngine.maybeStartControlLoopImpl() {
    if (controlJob?.isActive == true) return
    val gen = activeGeneration
    var lastUnderrunCount = rendererUnderrunCountImpl()
    controlJob = scope.launch {
        while (!renderStop.get() && activeGeneration == gen) {
            if (inhibitTransitions) {
                kotlinx.coroutines.delay(50)
                continue
            }
            if (playback.isPaused()) {
                kotlinx.coroutines.delay(100)
                continue
            }

            val fmt = currentFormat
            if (fmt == null) {
                kotlinx.coroutines.delay(50)
                continue
            }

            val item = queue.getOrNull(index) ?: return@launch
            val posMs = if (playback.isRunning()) {
                val framesIntoTrack = (playback.playedFrames() - currentTrackStartFrames).coerceAtLeast(0L)
                currentBasePositionMs + (framesIntoTrack * 1000L) / fmt.sampleRate.toLong()
            } else {
                0L
            }
            val st = _state.value
            if (st is PlayerState.Playing && st.item == item) {
                _state.value = st.copy(positionMs = posMs)
            }
            val currentUnderruns = rendererUnderrunCountImpl()
            if (currentUnderruns > lastUnderrunCount) {
                emitTelemetry(EngineTelemetryEvent.DecoderUnderrun(currentUnderruns))
                lastUnderrunCount = currentUnderruns
            }

            val fadeMs = crossfadeDurationMs.get()
            val autoNextIndex = queueState.peekNextIndexForAuto()
            if (fadeMs == 0L && currentDurationUs > 0 && autoNextIndex != null && autoNextIndex != index) {
                if (TransitionController.shouldPreload(posMs, currentDurationUs, fadeMs)) {
                    startNextTrackIfNeededImpl()
                }

                val nf = nextFormat
                val np = nextPipe
                val cp = currentPipe
                if (concatSource == null && !nextFailed && nf != null && np != null && cp != null) {
                    if (nf.sampleRate == fmt.sampleRate && nf.channelCount == fmt.channelCount) {
                        val minBufferedSamples = fmt.sampleRate / 10 * fmt.channelCount
                        if (np.availableSamples() >= minBufferedSamples) {
                            Log.d(tag, "Arming gapless concat at posMs=$posMs")
                            val targetIndex = nextQueueIndex
                            val cs = ConcatSource(fmt, cp, np) {
                                scope.launch {
                                    if (activeGeneration != gen) return@launch
                                    if (targetIndex < 0 || nextQueueIndex != targetIndex) return@launch
                                    Log.d(tag, "Gapless switch to next track index=$targetIndex")

                                    currentDecoder?.stopAndEos()
                                    currentPipe?.markEos()

                                    currentDecoder = nextDecoder
                                    currentPipe = nextPipe
                                    currentFormat = nextFormat
                                    currentDurationUs = nextDurationUs

                                    nextDecoder = null
                                    nextPipe = null
                                    nextFormat = null
                                    nextDurationUs = 0L
                                    nextFailed = false

                                    currentTrackStartFrames = playback.playedFrames()
                                    currentBasePositionMs = 0L

                                    queueState.setCurrentIndex(targetIndex)
                                    syncQueueFromState()
                                    nextQueueIndex = -1
                                    val newItem = queue.getOrNull(index)
                                    if (newItem != null) {
                                        _state.value = PlayerState.Playing(newItem, positionMs = 0L)
                                        emitter.trackTransition(-1, index, TrackTransitionReason.GAPLESS)
                                    }

                                    val pf = currentFormat
                                    val pp = currentPipe
                                    if (pf != null && pp != null) {
                                        setRendererSourceImpl(CrossAudioEngine.RenderMode.PIPE, PipeSource(pf, pp))
                                    }
                                    concatSource = null
                                }
                            }
                            concatSource = cs
                            setRendererSourceImpl(CrossAudioEngine.RenderMode.CONCAT, cs)
                        }
                    }
                }
            }
            if (fadeMs > 0 && currentDurationUs > 0 && autoNextIndex != null && autoNextIndex != index) {
                val startFadeMs = TransitionController.crossfadeStartMs(currentDurationUs, fadeMs)
                if (TransitionController.shouldPreload(posMs, currentDurationUs, fadeMs)) {
                    startNextTrackIfNeededImpl()
                }
                if (posMs >= startFadeMs) {
                    val nf = nextFormat
                    val np = nextPipe
                    val cp = currentPipe
                    if (nf != null && np != null && cp != null) {
                        if (nf.sampleRate == fmt.sampleRate && nf.channelCount == fmt.channelCount) {
                            val fadeFrames = (fadeMs * fmt.sampleRate) / 1000L
                            if (crossfadeSource == null) {
                                val minBufferedSamples = fmt.sampleRate / 10 * fmt.channelCount
                                if (nextFailed || (np.isEos() && np.availableSamples() == 0)) {
                                    if (fadeOutSource == null) {
                                        Log.d(tag, "Crossfade fallback: fading out (next unavailable)")
                                        fadeOutSource = FadeOutSource(fmt, cp, totalFadeFrames = fadeFrames)
                                        setRendererSourceImpl(CrossAudioEngine.RenderMode.FADEOUT, fadeOutSource!!)
                                    }
                                    kotlinx.coroutines.delay(100)
                                    continue
                                }
                                if (np.availableSamples() < minBufferedSamples) {
                                    kotlinx.coroutines.delay(20)
                                    continue
                                }
                                Log.d(
                                    tag,
                                    "Starting crossfade fadeMs=$fadeMs at posMs=$posMs nextBufferedSamples=${np.availableSamples()} snapshot=${debugSnapshotImpl()}",
                                )
                                val targetIndex = nextQueueIndex
                                if (targetIndex >= 0) {
                                    emitTelemetry(
                                        EngineTelemetryEvent.CrossfadeStarted(
                                            fromIndex = index,
                                            toIndex = targetIndex,
                                            durationMs = fadeMs,
                                        ),
                                    )
                                }
                                crossfadeSource = CrossfadeSource(
                                    fmt,
                                    cp,
                                    np,
                                    totalFadeFrames = fadeFrames,
                                    debugPan = debugCrossfadePan,
                                    headroom = crossfadeHeadroom,
                                )
                                setRendererSourceImpl(CrossAudioEngine.RenderMode.CROSSFADE, crossfadeSource!!)
                            }
                            if (crossfadeSource?.isDone() == true) {
                                val targetIndex = nextQueueIndex
                                if (targetIndex < 0) return@launch
                                Log.d(tag, "Crossfade complete; switching to next track index=$targetIndex snapshot=${debugSnapshotImpl()}")
                                val bFramesMixed = crossfadeSource?.bFramesMixed() ?: fadeFrames

                                currentDecoder?.stopAndEos()
                                currentPipe?.markEos()

                                currentDecoder = nextDecoder
                                currentPipe = nextPipe
                                currentFormat = nextFormat
                                currentDurationUs = nextDurationUs

                                nextDecoder = null
                                nextPipe = null
                                nextFormat = null
                                nextDurationUs = 0L
                                nextFailed = false

                                crossfadeSource = null
                                fadeOutSource = null
                                queueState.setCurrentIndex(targetIndex)
                                syncQueueFromState()
                                nextQueueIndex = -1
                                setRendererSourceImpl(CrossAudioEngine.RenderMode.PIPE, PipeSource(currentFormat!!, currentPipe!!))

                                currentTrackStartFrames = (playback.playedFrames() - bFramesMixed).coerceAtLeast(0L)
                                currentBasePositionMs = 0L
                                val bMs = (bFramesMixed * 1000L) / fmt.sampleRate.toLong()
                                val newItem = queue.getOrNull(index)
                                if (newItem != null) {
                                    _state.value = PlayerState.Playing(newItem, positionMs = bMs)
                                    emitter.trackTransition(-1, index, TrackTransitionReason.CROSSFADE)
                                }
                                emitTelemetry(EngineTelemetryEvent.CrossfadeCompleted(index))
                            }
                        } else {
                            val fadeFrames = (fadeMs * fmt.sampleRate) / 1000L
                            if (fadeOutSource == null) {
                                Log.d(tag, "Crossfade fallback: fading out (format mismatch)")
                                fadeOutSource = FadeOutSource(fmt, cp, totalFadeFrames = fadeFrames)
                                setRendererSourceImpl(CrossAudioEngine.RenderMode.FADEOUT, fadeOutSource!!)
                            }
                        }
                    }
                }
            }

            kotlinx.coroutines.delay(100)
        }
    }
}
