package com.crossaudio.engine.internal.streaming

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class SegmentFetcher {
    data class SourceProbe(
        val contentType: String?,
        val bodyPrefix: String,
    )

    fun fetchText(url: String, headers: Map<String, String>): String {
        return String(fetchBytes(url, headers), Charsets.UTF_8)
    }

    fun probeSource(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int = 2_048,
    ): SourceProbe {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-${maxBytes.coerceAtLeast(256) - 1}")
            setRequestProperty(
                "Accept",
                "application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,text/plain,*/*",
            )
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return conn.use { c ->
            val code = c.responseCode
            if (code !in 200..299 && code != 206) {
                throw IOException("HTTP $code for $url")
            }
            val prefixBytes = BufferedInputStream(c.inputStream).use { input ->
                val limit = maxBytes.coerceIn(256, 8_192)
                val out = ByteArrayOutputStream(limit)
                val buf = ByteArray(512)
                while (out.size() < limit) {
                    val read = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
            val contentType = c.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
            SourceProbe(
                contentType = contentType,
                bodyPrefix = String(prefixBytes, Charsets.UTF_8),
            )
        }
    }

    fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        retries: Int = 2,
        backoffMs: Long = 300L,
    ): ByteArray {
        var last: Throwable? = null
        repeat((retries + 1).coerceAtLeast(1)) { attempt ->
            runCatching {
                return open(url, headers)
            }.onFailure { failure ->
                last = failure
                if (attempt < retries) Thread.sleep(backoffMs * (attempt + 1))
            }
        }
        throw IOException("Failed to fetch $url", last)
    }

    private fun open(url: String, headers: Map<String, String>): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return conn.use { c ->
            val code = c.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for $url")
            }
            BufferedInputStream(c.inputStream).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
