package com.crossaudio.engine.internal.drm

import com.crossaudio.engine.DrmRequest

internal object DrmInitDataResolver {
    private const val HEADER_PSSH_B64 = "X-CrossAudio-Pssh-Base64"

    fun resolveOfflineInitData(
        request: DrmRequest,
        manifestInitDataBase64: String?,
    ): String? {
        return request.initDataBase64?.takeIf { it.isNotBlank() }
            ?: manifestInitDataBase64?.takeIf { it.isNotBlank() }
            ?: request.requestHeaders[HEADER_PSSH_B64]?.takeIf { it.isNotBlank() }
    }

    fun resolveStreamingInitData(
        request: DrmRequest,
        manifestInitDataBase64: String?,
    ): String? {
        return request.initDataBase64?.takeIf { it.isNotBlank() }
            ?: manifestInitDataBase64?.takeIf { it.isNotBlank() }
            ?: request.requestHeaders[HEADER_PSSH_B64]?.takeIf { it.isNotBlank() }
    }
}
