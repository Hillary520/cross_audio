package com.crossaudio.engine.service

import android.content.Intent
import com.crossaudio.engine.DrmGlobalConfig
import com.crossaudio.engine.DrmRequest
import com.crossaudio.engine.DrmScheme
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.QualityHint
import com.crossaudio.engine.SourceType
import com.crossaudio.engine.StreamingConfig
import org.json.JSONArray
import org.json.JSONObject

internal fun CrossAudioPlaybackService.parseItemsImpl(intent: Intent): List<MediaItem> {
    intent.getStringExtra(CrossAudioPlaybackService.EXTRA_ITEMS_JSON)
        ?.takeIf { it.isNotBlank() }
        ?.let { payload ->
            runCatching {
                val arr = JSONArray(payload)
                buildList {
                    for (idx in 0 until arr.length()) {
                        val obj = arr.optJSONObject(idx) ?: continue
                        val uri = obj.optString("uri", "").takeIf { it.isNotBlank() } ?: continue
                        val headers = obj.optJSONObject("headers")?.toStringMap().orEmpty()
                        val drm = obj.optJSONObject("drm")?.toDrmRequest()
                        val sourceType = runCatching {
                            SourceType.valueOf(obj.optString("sourceType", SourceType.AUTO.name))
                        }.getOrDefault(SourceType.AUTO)
                        val qualityHint = runCatching {
                            obj.optString("qualityHint", "").takeIf { it.isNotBlank() }
                                ?.let { QualityHint.valueOf(it) }
                        }.getOrNull()

                        add(
                            MediaItem(
                                uri = android.net.Uri.parse(uri),
                                title = obj.optNullableString("title"),
                                artist = obj.optNullableString("artist"),
                                artworkUri = obj.optNullableString("artworkUri"),
                                durationMs = obj.optNullableLong("durationMs"),
                                headers = headers,
                                cacheKey = obj.optNullableString("cacheKey"),
                                cacheGroupKey = obj.optNullableString("cacheGroupKey"),
                                sourceType = sourceType,
                                drm = drm,
                                qualityHint = qualityHint,
                            ),
                        )
                    }
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

    val uris = intent.getStringArrayExtra(CrossAudioPlaybackService.EXTRA_URIS).orEmpty().toList()
    val headersArray = intent.getStringArrayExtra(CrossAudioPlaybackService.EXTRA_HEADERS_JSON).orEmpty()
    val cacheKeys = intent.getStringArrayExtra(CrossAudioPlaybackService.EXTRA_CACHE_KEYS).orEmpty()
    return uris.mapIndexed { idx, u ->
        val headers = headersArray.getOrNull(idx)?.let { s ->
            runCatching {
                JSONObject(s).toStringMap()
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        MediaItem(
            uri = android.net.Uri.parse(u),
            headers = headers,
            cacheKey = cacheKeys.getOrNull(idx),
        )
    }
}

internal fun CrossAudioPlaybackService.parseStreamingConfigImpl(intent: Intent): StreamingConfig? {
    val raw = intent.getStringExtra(CrossAudioPlaybackService.EXTRA_STREAMING_CONFIG_JSON)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        val obj = JSONObject(raw)
        StreamingConfig(
            startupBitrateKbps = obj.optInt("startupBitrateKbps", 256),
            maxBitrateKbps = obj.optInt("maxBitrateKbps", 3_200),
            minBitrateKbps = obj.optInt("minBitrateKbps", 96),
            minBufferMs = obj.optInt("minBufferMs", 8_000),
            targetBufferMs = obj.optInt("targetBufferMs", 20_000),
            maxBufferMs = obj.optInt("maxBufferMs", 45_000),
            qualityCap = runCatching {
                QualityCap.valueOf(obj.optString("qualityCap", QualityCap.AUTO.name))
            }.getOrDefault(QualityCap.AUTO),
            segmentPipelineEnabled = obj.optBoolean("segmentPipelineEnabled", false),
            segmentPrefetchCount = obj.optInt("segmentPrefetchCount", 3),
            segmentRetryCount = obj.optInt("segmentRetryCount", 2),
        )
    }.getOrNull()
}

internal fun CrossAudioPlaybackService.parseDrmConfigImpl(intent: Intent): DrmGlobalConfig? {
    val raw = intent.getStringExtra(CrossAudioPlaybackService.EXTRA_DRM_CONFIG_JSON)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        val obj = JSONObject(raw)
        DrmGlobalConfig(
            allowOfflineLicenses = obj.optBoolean("allowOfflineLicenses", true),
            requestTimeoutMs = obj.optInt("requestTimeoutMs", 15_000),
            retryCount = obj.optInt("retryCount", 2),
        )
    }.getOrNull()
}

internal fun CrossAudioPlaybackService.parseQualityCapImpl(intent: Intent): QualityCap? {
    val raw = intent.getStringExtra(CrossAudioPlaybackService.EXTRA_QUALITY_CAP)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching { QualityCap.valueOf(raw) }.getOrNull()
}

private fun JSONObject.toStringMap(): Map<String, String> {
    return buildMap {
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            put(k, optString(k, ""))
        }
    }
}

private fun JSONObject.toDrmRequest(): DrmRequest? {
    val licenseUrl = optString("licenseUrl", "").takeIf { it.isNotBlank() } ?: return null
    val headers = optJSONObject("requestHeaders")?.toStringMap().orEmpty()
    return DrmRequest(
        scheme = runCatching { DrmScheme.valueOf(optString("scheme", DrmScheme.WIDEVINE.name)) }
            .getOrDefault(DrmScheme.WIDEVINE),
        licenseUrl = licenseUrl,
        requestHeaders = headers,
        initDataBase64 = optNullableString("initDataBase64"),
        offlineLicenseId = optNullableString("offlineLicenseId"),
        offlineKeySetId = optNullableString("offlineKeySetId"),
        multiSession = optBoolean("multiSession", false),
        forceL3 = optBoolean("forceL3", false),
    )
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name)) return null
    val value = optString(name, "").trim()
    return value.takeIf { it.isNotEmpty() }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name)) return null
    val value = opt(name)
    return when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Double -> value.toLong()
        is Float -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }
}
