package com.crossaudio.engine.internal.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.SourceType
import com.crossaudio.engine.StreamingConfig
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import com.crossaudio.engine.internal.drm.DrmSessionManager
import com.crossaudio.engine.internal.network.ResolvedMediaSource
import com.crossaudio.engine.internal.streaming.SegmentFetcher
import com.crossaudio.engine.internal.streaming.SegmentTimeline
import com.crossaudio.engine.internal.streaming.dash.DashRepresentation
import com.crossaudio.engine.internal.streaming.dash.MpdParser
import com.crossaudio.engine.internal.streaming.hls.HlsMasterParser
import com.crossaudio.engine.internal.streaming.hls.HlsMediaParser
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashSet
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
    private val onSegmentFetched: (String, Long, Long) -> Unit,
    private val onAdaptiveBitrateRequest: (List<Int>, Long, Int?) -> Int?,
    private val onAdaptiveSwitch: (String, Int?, String) -> Unit,
    private val onSourceInfo: (mimeType: String, bitrateKbps: Int?) -> Unit,
    private val onFormat: (PcmFormat, durationUs: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val tag = "SegmentTrackDecoder16"
    private val fetcher = SegmentFetcher()

    @Volatile
    private var thread: Thread? = null
    @Volatile
    private var sourceInfoSent = false
    @Volatile
    private var formatSent = false

    fun start() {
        thread = Thread({ runLoop() }, "cross-audio-segment-decode").apply { start() }
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    private fun runLoop() {
        try {
            val remote = source as? ResolvedMediaSource.RemoteHttp
                ?: throw IllegalArgumentException("Segment decoder expects RemoteHttp source")
            val lowerUrl = remote.url.lowercase()
            when {
                item.sourceType == SourceType.HLS || lowerUrl.endsWith(".m3u8") -> decodeHls(remote)
                item.sourceType == SourceType.DASH || lowerUrl.endsWith(".mpd") -> decodeDash(remote)
                else -> decodeFallback(remote)
            }
            pipe.markEos()
        } catch (t: Throwable) {
            if (t is InterruptedException) return
            Log.w(tag, "Segment decoder failed", t)
            runCatching { pipe.markEos() }
            onError(t)
        }
    }

    private fun decodeHls(remote: ResolvedMediaSource.RemoteHttp) {
        val manifestUri = remote.manifest?.manifestUri ?: remote.url
        val masterText = fetcher.fetchText(manifestUri, item.headers)
        val master = HlsMasterParser.parse(masterText)
        val variants = if (master.variants.isEmpty()) {
            listOf(AdaptiveVariant(remote.url, remote.manifest?.selectedBitrateKbps))
        } else {
            master.variants.map { variant ->
                AdaptiveVariant(
                    playlistUri = SegmentTimeline.resolveUri(manifestUri, variant.uri),
                    bitrateKbps = variant.bandwidthKbps.takeIf { it > 0 },
                )
            }
        }
        var activeVariant = chooseInitialVariant(
            variants = variants,
            selectedUri = remote.url,
            selectedBitrateKbps = remote.manifest?.selectedBitrateKbps,
        )
        onAdaptiveSwitch(activeVariant.playlistUri, activeVariant.bitrateKbps, "initial_manifest")

        val seenSegments = LinkedHashSet<String>()
        val initBytesByPlaylist = LinkedHashMap<String, ByteArray?>()
        var remainingSeekUs = startPositionMs.coerceAtLeast(0L) * 1000L
        val decodeBudgetPerCycle = streamingConfig.segmentPrefetchCount.coerceAtLeast(1)

        while (!stop.get()) {
            if (variants.size > 1) {
                val availableBitrates = variants.mapNotNull { it.bitrateKbps }.distinct().sorted()
                if (availableBitrates.isNotEmpty()) {
                    val target = onAdaptiveBitrateRequest(
                        availableBitrates,
                        estimatedBufferedMs(),
                        activeVariant.bitrateKbps,
                    )
                    val selected = selectVariantForTarget(variants, target) ?: activeVariant
                    if (selected.playlistUri != activeVariant.playlistUri) {
                        activeVariant = selected
                        onAdaptiveSwitch(
                            activeVariant.playlistUri,
                            activeVariant.bitrateKbps,
                            "runtime_switch",
                        )
                    }
                }
            }

            val mediaText = fetcher.fetchText(activeVariant.playlistUri, item.headers)
            val media = HlsMediaParser.parse(mediaText)
            val initBytes = initBytesByPlaylist.getOrPut(activeVariant.playlistUri) {
                media.initializationSegmentUri
                    ?.let { SegmentTimeline.resolveUri(activeVariant.playlistUri, it) }
                    ?.let { fetchSegmentBytes(it) }
            }
            val entries = media.segments.mapIndexed { idx, seg ->
                val absolute = SegmentTimeline.resolveUri(activeVariant.playlistUri, seg.uri)
                HlsSegmentEntry(
                    sequence = media.mediaSequence + idx.toLong(),
                    url = absolute,
                    durationUs = seg.durationUs,
                )
            }

            var decodedCount = 0
            for (entry in entries) {
                if (stop.get()) break
                val key = "${entry.sequence}:${entry.url}"
                if (seenSegments.contains(key)) continue

                if (remainingSeekUs > 0L && entry.durationUs > 0L && remainingSeekUs >= entry.durationUs) {
                    remainingSeekUs -= entry.durationUs
                    rememberSeen(seenSegments, key)
                    continue
                }
                if (decodedCount >= decodeBudgetPerCycle) {
                    break
                }

                val payload = fetchSegmentBytes(entry.url)
                val segmentSeekMs = if (remainingSeekUs > 0L) {
                    (remainingSeekUs / 1000L).coerceAtLeast(0L)
                } else {
                    0L
                }
                decodeSegmentChunk(
                    payload = payload,
                    initializationBytes = initBytes,
                    seekMs = segmentSeekMs,
                    hintedBitrateKbps = activeVariant.bitrateKbps,
                )
                remainingSeekUs = 0L
                decodedCount++
                rememberSeen(seenSegments, key)
            }

            if (stop.get()) break
            if (decodedCount > 0) {
                continue
            }
            if (media.isEndList) {
                break
            }
            val targetSec = media.targetDurationSec.coerceAtLeast(1)
            val pollMs = (targetSec * 500L).coerceIn(500L, 4_000L)
            Thread.sleep(pollMs)
        }
    }

    private fun decodeDash(remote: ResolvedMediaSource.RemoteHttp) {
        val manifestUri = remote.manifest?.manifestUri?.takeIf { it.lowercase().endsWith(".mpd") }
            ?: remote.url.takeIf { it.lowercase().endsWith(".mpd") }
        if (manifestUri.isNullOrBlank()) {
            decodeDashFromResolvedManifest(remote)
            return
        }

        val seenSegments = LinkedHashSet<String>()
        val initBytesByRepresentation = LinkedHashMap<String, ByteArray?>()
        val decodeBudgetPerCycle = streamingConfig.segmentPrefetchCount.coerceAtLeast(1)
        var remainingSeekMs = startPositionMs.coerceAtLeast(0L)
        var activeRepresentationKey: String? = null
        var activeRepresentationId: String? = null
        var activeBitrateKbps: Int? = remote.manifest?.selectedBitrateKbps

        while (!stop.get()) {
            val mpdText = fetcher.fetchText(manifestUri, item.headers)
            val mpd = MpdParser.parse(mpdText)
            val representations = mpd.representations.filter { rep ->
                rep.segmentUrls.isNotEmpty() || !rep.baseUrl.isNullOrBlank()
            }
            if (representations.isEmpty()) {
                break
            }

            val selected = selectDashRepresentation(
                representations = representations,
                currentRepresentationId = activeRepresentationId,
                currentBitrateKbps = activeBitrateKbps,
            ) ?: break

            val base = selected.baseUrl
                ?.let { SegmentTimeline.resolveUri(manifestUri, it) }
                ?: manifestUri
            val repKey = "${selected.id}|$base"
            val repBitrate = selected.bandwidthKbps.takeIf { it > 0 }
            if (repKey != activeRepresentationKey) {
                onAdaptiveSwitch(
                    base,
                    repBitrate,
                    if (activeRepresentationKey == null) "initial_manifest" else "runtime_switch",
                )
                activeRepresentationKey = repKey
            }
            activeRepresentationId = selected.id
            activeBitrateKbps = repBitrate

            val initBytes = initBytesByRepresentation.getOrPut(selected.id) {
                selected.initializationUrl
                    ?.let { SegmentTimeline.resolveUri(base, it) }
                    ?.let { fetchSegmentBytes(it) }
            }
            val segmentUrls = selected.segmentUrls.map { SegmentTimeline.resolveUri(base, it) }

            var decodedCount = 0
            for (url in segmentUrls) {
                if (stop.get()) break
                if (seenSegments.contains(url)) continue

                val payload = fetchSegmentBytes(url)
                decodeSegmentChunk(
                    payload = payload,
                    initializationBytes = initBytes,
                    seekMs = remainingSeekMs,
                    hintedBitrateKbps = repBitrate,
                )
                remainingSeekMs = 0L
                decodedCount++
                rememberSeen(seenSegments, url)
                if (decodedCount >= decodeBudgetPerCycle) {
                    break
                }
            }

            if (stop.get()) break
            if (decodedCount > 0) {
                continue
            }
            if (!mpd.isDynamic) {
                break
            }
            val pollMs = (mpd.minimumUpdatePeriodMs ?: 1_500L).coerceIn(500L, 6_000L)
            Thread.sleep(pollMs)
        }
    }

    private fun decodeDashFromResolvedManifest(remote: ResolvedMediaSource.RemoteHttp) {
        val manifest = remote.manifest
        val base = manifest?.selectedStreamUri ?: remote.url
        val segmentUrls = manifest?.selectedSegmentUris.orEmpty()
            .map { SegmentTimeline.resolveUri(base, it) }
        if (segmentUrls.isEmpty()) {
            decodeFallback(remote)
            return
        }

        val initBytes = manifest?.selectedInitializationUri
            ?.let { SegmentTimeline.resolveUri(base, it) }
            ?.let { fetchSegmentBytes(it) }
        onAdaptiveSwitch(base, manifest?.selectedBitrateKbps, "initial_manifest")

        var seekMs = startPositionMs.coerceAtLeast(0L)
        for (url in segmentUrls) {
            if (stop.get()) break
            val payload = fetchSegmentBytes(url)
            decodeSegmentChunk(
                payload = payload,
                initializationBytes = initBytes,
                seekMs = seekMs,
                hintedBitrateKbps = manifest?.selectedBitrateKbps,
            )
            seekMs = 0L
        }
    }

    private fun selectDashRepresentation(
        representations: List<DashRepresentation>,
        currentRepresentationId: String?,
        currentBitrateKbps: Int?,
    ): DashRepresentation? {
        if (representations.isEmpty()) return null
        val current = currentRepresentationId?.let { id ->
            representations.firstOrNull { it.id == id }
        }
        val availableBitrates = representations.map { it.bandwidthKbps }.filter { it > 0 }.distinct().sorted()
        if (availableBitrates.isEmpty()) return current ?: representations.first()
        val targetBitrate = onAdaptiveBitrateRequest(
            availableBitrates,
            estimatedBufferedMs(),
            currentBitrateKbps,
        )
        val target = targetBitrate ?: currentBitrateKbps ?: availableBitrates.last()
        val chosenBitrate = availableBitrates.lastOrNull { it <= target } ?: availableBitrates.first()
        return representations
            .filter { it.bandwidthKbps > 0 }
            .minByOrNull { kotlin.math.abs(it.bandwidthKbps - chosenBitrate) }
            ?: current
            ?: representations.first()
    }

    private fun decodeFallback(remote: ResolvedMediaSource.RemoteHttp) {
        val local = writeChunkFile(
            initializationBytes = null,
            payload = fetchSegmentBytes(remote.url),
        )
        try {
            decodeChunkFile(local, seekMs = startPositionMs, hintedBitrateKbps = remote.manifest?.selectedBitrateKbps)
        } finally {
            runCatching { local.delete() }
        }
    }

    private fun decodeSegmentChunk(
        payload: ByteArray,
        initializationBytes: ByteArray?,
        seekMs: Long,
        hintedBitrateKbps: Int?,
    ) {
        val local = writeChunkFile(initializationBytes, payload)
        try {
            decodeChunkFile(local, seekMs, hintedBitrateKbps)
        } finally {
            runCatching { local.delete() }
        }
    }

    private fun decodeChunkFile(
        file: File,
        seekMs: Long,
        hintedBitrateKbps: Int?,
    ) {
        val delegate = TrackDecoder16(
            context = context,
            stop = stop,
            source = ResolvedMediaSource.LocalFile(file.absolutePath),
            uriForMetadata = Uri.fromFile(file),
            pipe = pipe,
            startPositionMs = seekMs.coerceAtLeast(0L),
            outputSampleRate = outputSampleRate,
            drmSessionManager = drmSessionManager,
            drmRequest = item.drm,
            drmMediaKey = item.cacheGroupKey?.takeIf { it.isNotBlank() } ?: item.uri.toString(),
            manifestInitDataBase64 = manifestInitDataBase64,
            markEosOnCompletion = false,
            onSourceInfo = { mime, bitrate ->
                if (!sourceInfoSent) {
                    sourceInfoSent = true
                    onSourceInfo(mime, bitrate ?: hintedBitrateKbps)
                }
            },
            onFormat = { format, _ ->
                // Segment-by-segment decode does not map to a stable whole-track duration.
                if (!formatSent) {
                    formatSent = true
                    onFormat(format, 0L)
                }
            },
            onError = { _ -> Unit },
        )
        delegate.start()
        delegate.join(Long.MAX_VALUE)
        delegate.failureOrNull()?.let { throw it }
    }

    private fun writeChunkFile(initializationBytes: ByteArray?, payload: ByteArray): File {
        val root = File(context.cacheDir, "cross_audio_segment_pipeline").apply { mkdirs() }
        val out = File(root, "seg_${System.currentTimeMillis()}_${Thread.currentThread().id}.bin")
        FileOutputStream(out).use { fos ->
            if (initializationBytes != null && initializationBytes.isNotEmpty()) {
                fos.write(initializationBytes)
            }
            fos.write(payload)
        }
        return out
    }

    private fun fetchSegmentBytes(url: String): ByteArray {
        val t0 = SystemClock.elapsedRealtime()
        val payload = fetcher.fetchBytes(
            url = url,
            headers = item.headers,
            retries = streamingConfig.segmentRetryCount.coerceAtLeast(0),
        )
        val elapsedMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
        onSegmentFetched(url, payload.size.toLong(), elapsedMs)
        return payload
    }

    private fun chooseInitialVariant(
        variants: List<AdaptiveVariant>,
        selectedUri: String,
        selectedBitrateKbps: Int?,
    ): AdaptiveVariant {
        variants.firstOrNull { it.playlistUri == selectedUri }?.let { return it }
        selectedBitrateKbps?.let { target ->
            return selectVariantForTarget(variants, target) ?: variants.first()
        }
        return variants.first()
    }

    private fun selectVariantForTarget(
        variants: List<AdaptiveVariant>,
        targetBitrateKbps: Int?,
    ): AdaptiveVariant? {
        if (variants.isEmpty()) return null
        val withBitrate = variants.filter { (it.bitrateKbps ?: 0) > 0 }
        if (withBitrate.isEmpty()) return variants.first()
        if (targetBitrateKbps == null || targetBitrateKbps <= 0) {
            return withBitrate.maxByOrNull { it.bitrateKbps ?: 0 }
        }
        val capped = withBitrate
            .filter { (it.bitrateKbps ?: 0) <= targetBitrateKbps }
            .maxByOrNull { it.bitrateKbps ?: 0 }
        return capped ?: withBitrate.minByOrNull { it.bitrateKbps ?: Int.MAX_VALUE }
    }

    private fun estimatedBufferedMs(): Long {
        val bufferedSamples = pipe.availableSamples().coerceAtLeast(0)
        val frames = bufferedSamples / 2
        return (frames * 1000L) / outputSampleRate.coerceAtLeast(1)
    }

    private fun rememberSeen(seen: LinkedHashSet<String>, key: String) {
        seen.add(key)
        val limit = 4_096
        while (seen.size > limit) {
            val it = seen.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    private data class AdaptiveVariant(
        val playlistUri: String,
        val bitrateKbps: Int?,
    )

    private data class HlsSegmentEntry(
        val sequence: Long,
        val url: String,
        val durationUs: Long,
    )
}
