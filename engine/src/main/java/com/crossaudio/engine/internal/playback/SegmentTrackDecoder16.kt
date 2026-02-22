package com.crossaudio.engine.internal.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.SourceType
import com.crossaudio.engine.StreamingConfig
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import com.crossaudio.engine.internal.drm.DrmSessionManager
import com.crossaudio.engine.internal.network.ResolvedMediaSource
import com.crossaudio.engine.internal.streaming.ContainerDemuxAdapter
import com.crossaudio.engine.internal.streaming.SegmentFetcher
import com.crossaudio.engine.internal.streaming.SegmentTimeline
import com.crossaudio.engine.internal.streaming.hls.HlsMediaParser
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class SegmentTrackDecoder16(
    private val context: Context,
    private val stop: AtomicBoolean,
    private val source: ResolvedMediaSource,
    private val item: MediaItem,
    private val pipe: PcmPipe16,
    private val startPositionMs: Long,
    private val outputSampleRate: Int,
    private val streamingConfig: StreamingConfig,
    private val drmSessionManager: DrmSessionManager?,
    private val manifestInitDataBase64: String?,
    private val onSegmentFetched: (String, Long) -> Unit,
    private val onFormat: (PcmFormat, durationUs: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val tag = "SegmentTrackDecoder16"
    private val fetcher = SegmentFetcher()
    private val demuxAdapter = ContainerDemuxAdapter()

    @Volatile
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ runLoop() }, "cross-audio-segment-decode").apply { start() }
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    private fun runLoop() {
        var assembled: File? = null
        try {
            val remote = source as? ResolvedMediaSource.RemoteHttp
                ?: throw IllegalArgumentException("Segment decoder expects RemoteHttp source")
            val segmentUrls = buildSegmentUrls(remote.url, item.headers, item.sourceType)
            if (segmentUrls.isEmpty()) {
                throw IllegalStateException("No segments resolved for ${remote.url}")
            }

            val root = File(context.cacheDir, "cross_audio_segment_pipeline").apply { mkdirs() }
            assembled = File(root, "seg_${System.currentTimeMillis()}_${Thread.currentThread().id}.bin")

            FileOutputStream(assembled).use { output ->
                for (url in segmentUrls) {
                    if (stop.get()) break
                    val payload = fetcher.fetchBytes(
                        url = url,
                        headers = item.headers,
                        retries = streamingConfig.segmentRetryCount.coerceAtLeast(0),
                    )
                    onSegmentFetched(url, payload.size.toLong())
                    val units = demuxAdapter.toDecoderInputUnits(payload)
                    for (unit in units) {
                        output.write(unit)
                    }
                }
            }

            if (stop.get()) {
                pipe.markEos()
                return
            }
            if (assembled.length() <= 0L) {
                throw IllegalStateException("Assembled segment payload is empty")
            }

            val delegate = TrackDecoder16(
                context = context,
                stop = stop,
                source = ResolvedMediaSource.LocalFile(assembled.absolutePath),
                uriForMetadata = Uri.fromFile(assembled),
                pipe = pipe,
                startPositionMs = startPositionMs,
                outputSampleRate = outputSampleRate,
                drmSessionManager = drmSessionManager,
                drmRequest = item.drm,
                drmMediaKey = item.cacheGroupKey?.takeIf { it.isNotBlank() } ?: item.uri.toString(),
                manifestInitDataBase64 = manifestInitDataBase64,
                onFormat = onFormat,
                onError = onError,
            )
            delegate.start()
            delegate.join(Long.MAX_VALUE)
        } catch (t: Throwable) {
            if (t is InterruptedException) return
            Log.w(tag, "Segment decoder failed", t)
            runCatching { pipe.markEos() }
            onError(t)
        } finally {
            runCatching { assembled?.delete() }
        }
    }

    private fun buildSegmentUrls(
        selectedUrl: String,
        headers: Map<String, String>,
        sourceType: SourceType,
    ): List<String> {
        val lowerUrl = selectedUrl.lowercase()
        if (sourceType == SourceType.HLS || lowerUrl.endsWith(".m3u8")) {
            val mediaText = fetcher.fetchText(selectedUrl, headers)
            val media = HlsMediaParser.parse(mediaText)
            if (media.segments.isEmpty()) return listOf(selectedUrl)
            return media.segments.map { seg -> SegmentTimeline.resolveUri(selectedUrl, seg.uri) }
        }
        return listOf(selectedUrl)
    }
}
