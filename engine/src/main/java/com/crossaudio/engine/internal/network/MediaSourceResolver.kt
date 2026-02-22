package com.crossaudio.engine.internal.network

import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.SourceType
import com.crossaudio.engine.internal.cache.CacheManager
import com.crossaudio.engine.internal.streaming.ResolvedManifest
import com.crossaudio.engine.internal.streaming.ManifestResolver

internal class MediaSourceResolver(
    private val cacheManager: CacheManager,
    private val manifestResolver: ManifestResolver,
    private val qualityCapProvider: () -> QualityCap,
) {
    fun resolve(item: MediaItem): ResolvedMediaSource {
        val scheme = item.uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val sourceType = manifestResolver.detectSourceType(item)
            if (sourceType != SourceType.PROGRESSIVE) {
                val resolvedManifest = runCatching {
                    manifestResolver.resolve(item, qualityCapProvider())
                }.getOrNull()
                resolvedManifest?.manifestUri?.let { cacheManager.recordManifestResource(item, it) }
                val streamUrl = resolvedManifest?.selectedStreamUri ?: item.uri.toString()
                return ResolvedMediaSource.RemoteHttp(
                    url = streamUrl,
                    headers = item.headers,
                    sourceType = resolvedManifest?.sourceType ?: sourceType,
                    manifestInitDataBase64 = resolvedManifest?.initDataBase64,
                    manifest = resolvedManifest,
                )
            }
            val r = cacheManager.resolveHttp(item)
            if (r.localFile != null) {
                return ResolvedMediaSource.LocalFile(r.localFile.absolutePath)
            }
            return ResolvedMediaSource.RemoteHttp(
                url = item.uri.toString(),
                headers = item.headers,
                sourceType = SourceType.PROGRESSIVE,
            )
        }
        return ResolvedMediaSource.LocalUri(item.uri)
    }
}

internal sealed class ResolvedMediaSource {
    data class LocalUri(val uri: android.net.Uri) : ResolvedMediaSource()
    data class LocalFile(val path: String) : ResolvedMediaSource()
    data class RemoteHttp(
        val url: String,
        val headers: Map<String, String>,
        val sourceType: SourceType,
        val manifestInitDataBase64: String? = null,
        val manifest: ResolvedManifest? = null,
    ) : ResolvedMediaSource()
}
