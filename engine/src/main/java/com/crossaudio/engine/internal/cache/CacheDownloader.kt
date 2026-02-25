package com.crossaudio.engine.internal.cache

import com.crossaudio.engine.MediaItem
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal class CacheDownloader {
    private val executor = Executors.newFixedThreadPool(2)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(
        cacheKey: String,
        item: MediaItem,
        tmpFile: File,
        onProgress: (Long, Long) -> Unit,
        onComplete: (Boolean, Long) -> Unit,
    ) {
        if (!inFlight.add(cacheKey)) return
        executor.execute {
            var ok = false
            var written = 0L
            try {
                tmpFile.parentFile?.mkdirs()
                if (tmpFile.exists()) tmpFile.delete()

                val conn = (URL(item.uri.toString()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    item.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.connect()
                val total = conn.contentLengthLong.coerceAtLeast(0L)
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }

                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
                ok = true
            } catch (_: Throwable) {
                ok = false
            } finally {
                if (!ok) {
                    runCatching { tmpFile.delete() }
                }
                onComplete(ok, written)
                inFlight.remove(cacheKey)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        inFlight.clear()
    }
}
