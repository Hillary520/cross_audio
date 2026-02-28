package com.crossaudio.engine.internal.streaming

import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.SourceType
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
    val initDataBase64: String? = null,
)

internal class ManifestResolver(
    private val fetcher: SegmentFetcher = SegmentFetcher(),
    private val selector: TrackSelector = TrackSelector(),
) {
    fun resolve(item: MediaItem, qualityCap: QualityCap = QualityCap.AUTO): ResolvedManifest {
        val sourceType = detectSourceType(item)
        val uri = item.uri.toString()
        if (sourceType == SourceType.PROGRESSIVE) {
            return ResolvedManifest(sourceType = SourceType.PROGRESSIVE, manifestUri = uri)
        }

        val manifestText = fetcher.fetchText(uri, item.headers)
        return when (sourceType) {
            SourceType.HLS -> resolveHls(uri, manifestText, qualityCap, item.headers)
            SourceType.DASH -> resolveDash(uri, manifestText, qualityCap)
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
        val selected = selector.selectHlsVariant(master.variants, qualityCap)
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
            initDataBase64 = selectedMedia?.sessionKeyPsshBase64,
        )
    }

    private fun resolveDash(uri: String, manifest: String, qualityCap: QualityCap): ResolvedManifest {
        val mpd = MpdParser.parse(manifest)
        val selected = selector.selectDashRepresentation(mpd.representations, qualityCap)
        val selectedUri = selected?.baseUrl?.let { SegmentTimeline.resolveUri(uri, it) }
        return ResolvedManifest(
            sourceType = SourceType.DASH,
            manifestUri = uri,
            selectedStreamUri = selectedUri,
            selectedInitializationUri = selected?.initializationUrl,
            selectedSegmentUris = selected?.segmentUrls.orEmpty(),
            variantCount = mpd.representations.size,
            initDataBase64 = mpd.initDataBase64,
        )
    }
}
