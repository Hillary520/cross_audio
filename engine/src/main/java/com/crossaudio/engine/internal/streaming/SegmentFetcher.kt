package com.crossaudio.engine.internal.streaming

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class SegmentFetcher {
    fun fetchText(url: String, headers: Map<String, String>): String {
        return String(fetchBytes(url, headers), Charsets.UTF_8)
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
