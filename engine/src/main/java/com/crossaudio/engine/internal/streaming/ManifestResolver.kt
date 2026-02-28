package com.crossaudio.engine.internal.streaming

import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.SourceType
import com.crossaudio.engine.internal.abr.AbrController
import com.crossaudio.engine.internal.streaming.dash.MpdParser
import com.crossaudio.engine.internal.streaming.hls.HlsMasterParser
import com.crossaudio.engine.internal.streaming.hls.HlsMediaParser

internal data class ResolvedManifest(
    val sourceType: SourceType,
    val manifestUri: String,
    val selectedStreamUri: String? = null,
    val selectedInitializationUri: String? = null,
    val selectedSegmentUris: List<String> = emptyList(),
    val variantCount: Int = 0,
    val availableBitratesKbps: List<Int> = emptyList(),
    val selectedBitrateKbps: Int? = null,
    val abrReason: String? = null,
    val initDataBase64: String? = null,
)

internal class ManifestResolver(
    private val fetcher: SegmentFetcher = SegmentFetcher(),
    private val selector: TrackSelector = TrackSelector(),
    private val abrController: AbrController = AbrController(),
) {
    fun resolve(
        item: MediaItem,
        qualityCap: QualityCap = QualityCap.AUTO,
        estimatedBandwidthKbps: Int? = null,
        bufferedMs: Long = 0L,
        startupBitrateKbps: Int? = null,
    ): ResolvedManifest {
        val sourceType = detectSourceType(item)
        val uri = item.uri.toString()
        if (sourceType == SourceType.PROGRESSIVE) {
            return ResolvedManifest(sourceType = SourceType.PROGRESSIVE, manifestUri = uri)
        }

        val manifestText = fetcher.fetchText(uri, item.headers)
        return when (sourceType) {
            SourceType.HLS -> resolveHls(
                uri = uri,
                manifest = manifestText,
                qualityCap = qualityCap,
                headers = item.headers,
                estimatedBandwidthKbps = estimatedBandwidthKbps,
                bufferedMs = bufferedMs,
                startupBitrateKbps = startupBitrateKbps,
            )
            SourceType.DASH -> resolveDash(
                uri = uri,
                manifest = manifestText,
                qualityCap = qualityCap,
                estimatedBandwidthKbps = estimatedBandwidthKbps,
                bufferedMs = bufferedMs,
                startupBitrateKbps = startupBitrateKbps,
            )
            else -> ResolvedManifest(sourceType = SourceType.PROGRESSIVE, manifestUri = uri)
        }
    }

    fun detectSourceType(item: MediaItem): SourceType {
        if (item.sourceType != SourceType.AUTO) return item.sourceType
        val path = item.uri.encodedPath?.lowercase().orEmpty()
        return when {
            path.endsWith(".m3u8") -> SourceType.HLS
            path.endsWith(".mpd") -> SourceType.DASH
            else -> SourceType.PROGRESSIVE
        }
    }

    private fun resolveHls(
        uri: String,
        manifest: String,
        qualityCap: QualityCap,
        headers: Map<String, String>,
        estimatedBandwidthKbps: Int?,
        bufferedMs: Long,
        startupBitrateKbps: Int?,
    ): ResolvedManifest {
        val master = HlsMasterParser.parse(manifest)
        if (master.variants.isEmpty()) {
            val media = HlsMediaParser.parse(manifest)
            return ResolvedManifest(
                sourceType = SourceType.HLS,
                manifestUri = uri,
                selectedStreamUri = uri,
                selectedInitializationUri = media.initializationSegmentUri,
                selectedSegmentUris = media.segments.map { it.uri },
                variantCount = if (media.segments.isEmpty()) 0 else 1,
                initDataBase64 = media.sessionKeyPsshBase64,
            )
        }
        val availableBitrates = master.variants.map { it.bandwidthKbps }.filter { it > 0 }.distinct().sorted()
        val abrDecision = if (availableBitrates.isNotEmpty() && estimatedBandwidthKbps != null) {
            abrController.chooseBitrate(
                availableBitratesKbps = availableBitrates,
                estimatedBandwidthKbps = estimatedBandwidthKbps,
                bufferedMs = bufferedMs,
                cap = qualityCap,
            )
        } else {
            null
        }
        val selected = selector.selectHlsVariant(
            variants = master.variants,
            cap = qualityCap,
            targetBitrateKbps = abrDecision?.selectedBitrateKbps ?: startupBitrateKbps,
        )
        val absoluteSelected = SegmentTimeline.resolveUri(uri, selected.uri)
        val selectedMedia = runCatching {
            val selectedMediaText = fetcher.fetchText(absoluteSelected, headers)
            HlsMediaParser.parse(selectedMediaText)
        }.getOrNull()
        return ResolvedManifest(
            sourceType = SourceType.HLS,
            manifestUri = uri,
            selectedStreamUri = absoluteSelected,
            selectedInitializationUri = selectedMedia?.initializationSegmentUri,
            selectedSegmentUris = selectedMedia?.segments?.map { it.uri }.orEmpty(),
            variantCount = master.variants.size,
            availableBitratesKbps = availableBitrates,
            selectedBitrateKbps = selected.bandwidthKbps.takeIf { it > 0 },
            abrReason = abrDecision?.reason ?: if (startupBitrateKbps != null) "startup_hint" else null,
            initDataBase64 = selectedMedia?.sessionKeyPsshBase64,
        )
    }

    private fun resolveDash(
        uri: String,
        manifest: String,
        qualityCap: QualityCap,
        estimatedBandwidthKbps: Int?,
        bufferedMs: Long,
        startupBitrateKbps: Int?,
    ): ResolvedManifest {
        val mpd = MpdParser.parse(manifest)
        val availableBitrates = mpd.representations.map { it.bandwidthKbps }.filter { it > 0 }.distinct().sorted()
        val abrDecision = if (availableBitrates.isNotEmpty() && estimatedBandwidthKbps != null) {
            abrController.chooseBitrate(
                availableBitratesKbps = availableBitrates,
                estimatedBandwidthKbps = estimatedBandwidthKbps,
                bufferedMs = bufferedMs,
                cap = qualityCap,
            )
        } else {
            null
        }
        val selected = selector.selectDashRepresentation(
            representations = mpd.representations,
            cap = qualityCap,
            targetBitrateKbps = abrDecision?.selectedBitrateKbps ?: startupBitrateKbps,
        )
        val selectedUri = when {
            selected == null -> null
            !selected.baseUrl.isNullOrBlank() -> SegmentTimeline.resolveUri(uri, selected.baseUrl)
            else -> uri
        }
        return ResolvedManifest(
            sourceType = SourceType.DASH,
            manifestUri = uri,
            selectedStreamUri = selectedUri,
            selectedInitializationUri = selected?.initializationUrl,
            selectedSegmentUris = selected?.segmentUrls.orEmpty(),
            variantCount = mpd.representations.size,
            availableBitratesKbps = availableBitrates,
            selectedBitrateKbps = selected?.bandwidthKbps?.takeIf { it > 0 },
            abrReason = abrDecision?.reason ?: if (startupBitrateKbps != null) "startup_hint" else null,
            initDataBase64 = mpd.initDataBase64,
        )
    }
}
