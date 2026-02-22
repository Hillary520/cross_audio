package com.crossaudio.engine.internal.drm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WidevineLicenseClientTest {
    private val client = WidevineLicenseClient()

    @Test
    fun `accepts https license url`() {
        val url = client.validateLicenseUrl("https://license.example/wv")
        assertEquals("https", url.protocol)
    }

    @Test
    fun `rejects non-https license url`() {
        assertThrows(IOException::class.java) {
            client.validateLicenseUrl("http://license.example/wv")
        }
    }
}
