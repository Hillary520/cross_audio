package com.crossaudio.engine.internal.playback

import android.content.Context
import android.net.Uri
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.SourceType
import com.crossaudio.engine.StreamingConfig
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.drm.DrmSessionManager
import com.crossaudio.engine.internal.audio.PcmPipe16
import com.crossaudio.engine.internal.network.MediaSourceResolver
import com.crossaudio.engine.internal.network.ResolvedMediaSource
import java.util.concurrent.atomic.AtomicBoolean

internal class DecoderSession(
    context: Context,
    resolver: MediaSourceResolver,
    resolvedSource: ResolvedMediaSource?,
    item: MediaItem,
    sourceType: SourceType,
    streamingConfig: StreamingConfig,
    drmSessionManager: DrmSessionManager?,
    manifestInitDataBase64: String?,
    onSegmentFetched: (String, Long) -> Unit = { _, _ -> },
    startPositionMs: Long,
    outputSampleRate: Int,
    capacitySamples: Int,
    onFormat: (PcmFormat, Long) -> Unit,
    onError: (Throwable) -> Unit,
) {
    private val stopFlag = AtomicBoolean(false)
    val pipe = PcmPipe16(capacitySamples = capacitySamples, stop = stopFlag)
    private val actualSource = resolvedSource ?: resolver.resolve(item)

    private val decoder: DecoderBackend = if (
        streamingConfig.segmentPipelineEnabled &&
        sourceType != SourceType.PROGRESSIVE &&
        actualSource is ResolvedMediaSource.RemoteHttp
    ) {
        SegmentBackend(
            SegmentTrackDecoder16(
                context = context,
                stop = stopFlag,
                source = actualSource,
                item = item,
                pipe = pipe,
                startPositionMs = startPositionMs,
                outputSampleRate = outputSampleRate,
                streamingConfig = streamingConfig,
                drmSessionManager = drmSessionManager,
                manifestInitDataBase64 = manifestInitDataBase64,
                onSegmentFetched = onSegmentFetched,
                onFormat = onFormat,
                onError = onError,
            ),
        )
    } else {
        LegacyBackend(
            TrackDecoder16(
                context = context,
                stop = stopFlag,
                source = actualSource,
                uriForMetadata = if (isLocal(item.uri)) item.uri else null,
                pipe = pipe,
                startPositionMs = startPositionMs,
                outputSampleRate = outputSampleRate,
                drmSessionManager = drmSessionManager,
                drmRequest = item.drm,
                drmMediaKey = item.cacheGroupKey?.takeIf { it.isNotBlank() } ?: item.uri.toString(),
                manifestInitDataBase64 = manifestInitDataBase64,
                onFormat = onFormat,
                onError = onError,
            ),
        )
    }

    fun start() {
        decoder.start()
    }

    fun stopAndEos() {
        stopFlag.set(true)
        pipe.markEos()
    }

    fun join(timeoutMs: Long) {
        decoder.join(timeoutMs)
    }

    private fun isLocal(uri: Uri): Boolean {
        val s = uri.scheme?.lowercase() ?: return true
        return s != "http" && s != "https"
    }

    private interface DecoderBackend {
        fun start()
        fun join(timeoutMs: Long)
    }

    private class LegacyBackend(
        private val delegate: TrackDecoder16,
    ) : DecoderBackend {
        override fun start() = delegate.start()
        override fun join(timeoutMs: Long) = delegate.join(timeoutMs)
    }

    private class SegmentBackend(
        private val delegate: SegmentTrackDecoder16,
    ) : DecoderBackend {
        override fun start() = delegate.start()
        override fun join(timeoutMs: Long) = delegate.join(timeoutMs)
    }
}
