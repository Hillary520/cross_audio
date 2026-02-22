package com.crossaudio.engine.internal.drm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal class WidevineLicenseClient {
    fun executeKeyRequest(
        licenseUrl: String,
        body: ByteArray,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): ByteArray {
        val normalizedUrl = validateLicenseUrl(licenseUrl)
        val conn = (normalizedUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/octet-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        return conn.use { c ->
            c.outputStream.use { os -> os.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val message = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw IOException("License server HTTP $code ${message.take(120)}")
            }
            c.inputStream.use { it.readBytes() }
        }
    }

    internal fun validateLicenseUrl(licenseUrl: String): URL {
        val parsed = runCatching { URL(licenseUrl) }
            .getOrElse { throw IOException("Invalid DRM license URL", it) }
        if (parsed.protocol.lowercase(Locale.US) != "https") {
            throw IOException("Only HTTPS DRM license URLs are allowed")
        }
        return parsed
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
