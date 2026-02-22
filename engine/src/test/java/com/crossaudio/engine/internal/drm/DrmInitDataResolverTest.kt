package com.crossaudio.engine.internal.drm

import com.crossaudio.engine.DrmRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class DrmInitDataResolverTest {
    @Test
    fun `offline resolver precedence`() {
        val req = DrmRequest(
            licenseUrl = "https://license.example",
            requestHeaders = mapOf("X-CrossAudio-Pssh-Base64" to "headerPssh"),
            initDataBase64 = "initData",
        )

        val resolved = DrmInitDataResolver.resolveOfflineInitData(req, manifestInitDataBase64 = "manifestPssh")

        assertEquals("initData", resolved)
    }

    @Test
    fun `offline resolver falls back to manifest then header`() {
        val req = DrmRequest(
            licenseUrl = "https://license.example",
            requestHeaders = mapOf("X-CrossAudio-Pssh-Base64" to "headerPssh"),
        )

        assertEquals("manifestPssh", DrmInitDataResolver.resolveOfflineInitData(req, "manifestPssh"))
        assertEquals("headerPssh", DrmInitDataResolver.resolveOfflineInitData(req, null))
    }
}
