package com.crossaudio.engine.internal.cache

import android.content.Context
import com.crossaudio.engine.CacheConfig
import com.crossaudio.engine.CacheInfo
import com.crossaudio.engine.CacheState
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.internal.events.EventEmitter
import java.io.File
import java.security.MessageDigest

internal class CacheManager(
    context: Context,
    private val events: EventEmitter,
) {
    private val appContext = context.applicationContext
    private val db = CacheIndexDb(appContext)
    private val downloader = CacheDownloader()

    @Volatile
    private var config = CacheConfig()

    fun setConfig(value: CacheConfig) {
        config = value
        evictIfNeeded()
    }

    fun cacheInfo(item: MediaItem): CacheInfo {
        val key = cacheKey(item)
        val e = db.get(key)
        return if (e == null) {
            CacheInfo(key, CacheState.MISS, 0L, false)
        } else {
            CacheInfo(key, e.state, e.sizeBytes, e.pinned)
        }
    }

    fun resolveHttp(item: MediaItem): CacheResolveResult {
        val key = cacheKey(item)
        val group = cacheGroupKey(item)
        val existing = db.get(key)
        val local = existing?.takeIf { it.state == CacheState.READY || it.state == CacheState.PINNED }?.let { File(it.path) }
        if (local != null && local.exists()) {
            val now = System.currentTimeMillis()
            db.updateAccess(key, now)
            db.touchGroup(group, now)
            return CacheResolveResult(cacheKey = key, localFile = local, pinned = existing.pinned)
        }

        val finalFile = finalFileFor(key)
        val now = System.currentTimeMillis()
        val pinned = existing?.pinned == true || db.getGroup(group)?.pinned == true
        db.upsert(
            CacheEntry(
                cacheKey = key,
                uri = item.uri.toString(),
                path = finalFile.absolutePath,
                state = if (pinned) CacheState.PARTIAL else CacheState.MISS,
                sizeBytes = existing?.sizeBytes ?: 0L,
                lastAccessMs = now,
                pinned = pinned,
            ),
        )
        db.upsertGroup(group, pinned = pinned, atMs = now)
        db.upsertResource(
            CacheResourceEntry(
                resourceKey = key,
                groupKey = group,
                uri = item.uri.toString(),
                path = finalFile.absolutePath,
                type = CacheResourceType.PROGRESSIVE,
                state = if (pinned) CacheState.PARTIAL else CacheState.MISS,
                sizeBytes = existing?.sizeBytes ?: 0L,
                pinned = pinned,
                lastAccessMs = now,
            ),
        )

        maybeStartReadThrough(item, key, group, pinned)
        return CacheResolveResult(cacheKey = key, localFile = null, pinned = pinned)
    }

    fun pinForOffline(item: MediaItem) {
        val key = cacheKey(item)
        val group = cacheGroupKey(item)
        val existing = db.get(key)
        val finalFile = finalFileFor(key)
        val now = System.currentTimeMillis()
        db.upsert(
            CacheEntry(
                cacheKey = key,
                uri = item.uri.toString(),
                path = existing?.path ?: finalFile.absolutePath,
                state = existing?.state ?: CacheState.PARTIAL,
                sizeBytes = existing?.sizeBytes ?: 0L,
                lastAccessMs = now,
                pinned = true,
            ),
        )
        db.upsertGroup(group, pinned = true, atMs = now)
        db.upsertResource(
            CacheResourceEntry(
                resourceKey = key,
                groupKey = group,
                uri = item.uri.toString(),
                path = existing?.path ?: finalFile.absolutePath,
                type = CacheResourceType.PROGRESSIVE,
                state = existing?.state ?: CacheState.PARTIAL,
                sizeBytes = existing?.sizeBytes ?: 0L,
                pinned = true,
                lastAccessMs = now,
            ),
        )
        events.cacheState(key, CacheState.PINNED)
        maybeStartReadThrough(item, key, group, pinned = true)
    }

    fun unpinOffline(item: MediaItem) {
        val key = cacheKey(item)
        val group = cacheGroupKey(item)
        db.markPinned(key, false)
        db.markGroupPinned(group, false)
        db.markResourcesPinned(group, false)
        evictIfNeeded()
    }

    fun pinGroup(cacheGroupKey: String) {
        if (cacheGroupKey.isBlank()) return
        val now = System.currentTimeMillis()
        db.upsertGroup(cacheGroupKey, pinned = true, atMs = now)
        db.markGroupPinned(cacheGroupKey, true)
        db.markResourcesPinned(cacheGroupKey, true)
    }

    fun unpinGroup(cacheGroupKey: String) {
        if (cacheGroupKey.isBlank()) return
        db.markGroupPinned(cacheGroupKey, false)
        db.markResourcesPinned(cacheGroupKey, false)
        evictIfNeeded()
    }

    fun evictGroup(cacheGroupKey: String) {
        if (cacheGroupKey.isBlank()) return
        evictGroupInternal(cacheGroupKey)
    }

    fun clearUnpinnedCache() {
        if (config.groupedEvictionEnabled) {
            db.listGroupEvictionCandidates().forEach { group ->
                evictGroupInternal(group.groupKey)
            }
            return
        }
        db.listUnpinned().forEach { entry ->
            runCatching { File(entry.path).delete() }
            db.delete(entry.cacheKey)
            events.cacheState(entry.cacheKey, CacheState.MISS)
        }
    }

    fun recordManifestResource(item: MediaItem, manifestUri: String, sizeBytes: Long = 0L) {
        val group = cacheGroupKey(item)
        val now = System.currentTimeMillis()
        val pinned = db.getGroup(group)?.pinned == true
        db.upsertGroup(group, pinned = pinned, atMs = now)
        db.upsertResource(
            CacheResourceEntry(
                resourceKey = "manifest:${hash(group)}:${hash(manifestUri)}",
                groupKey = group,
                uri = manifestUri,
                type = CacheResourceType.MANIFEST,
                state = CacheState.READY,
                sizeBytes = sizeBytes,
                pinned = pinned,
                lastAccessMs = now,
            ),
        )
    }

    fun recordSegmentResource(item: MediaItem, segmentUri: String, sizeBytes: Long) {
        val group = cacheGroupKey(item)
        val now = System.currentTimeMillis()
        val pinned = db.getGroup(group)?.pinned == true
        db.upsertGroup(group, pinned = pinned, atMs = now)
        db.upsertResource(
            CacheResourceEntry(
                resourceKey = "segment:${hash(group)}:${hash(segmentUri)}",
                groupKey = group,
                uri = segmentUri,
                type = CacheResourceType.SEGMENT,
                state = CacheState.READY,
                sizeBytes = sizeBytes.coerceAtLeast(0L),
                pinned = pinned,
                lastAccessMs = now,
            ),
        )
    }

    fun release() {
        downloader.shutdown()
        db.close()
    }

    private fun maybeStartReadThrough(item: MediaItem, key: String, group: String, pinned: Boolean) {
        if (!isHttp(item)) return
        val tmp = tempFileFor(key)
        val dst = finalFileFor(key)
        downloader.enqueue(
            cacheKey = key,
            item = item,
            tmpFile = tmp,
            onProgress = { downloaded, total ->
                events.cacheProgress(key, downloaded, total, pinned)
                db.markState(key, CacheState.PARTIAL)
                db.markResourceState(key, CacheState.PARTIAL)
            },
            onComplete = { ok, size ->
                if (!ok) {
                    db.markState(key, CacheState.FAILED)
                    db.markResourceState(key, CacheState.FAILED)
                    events.cacheState(key, CacheState.FAILED)
                    return@enqueue
                }
                dst.parentFile?.mkdirs()
                runCatching { if (dst.exists()) dst.delete() }
                val moved = tmp.renameTo(dst)
                if (!moved) {
                    db.markState(key, CacheState.FAILED)
                    db.markResourceState(key, CacheState.FAILED)
                    events.cacheState(key, CacheState.FAILED)
                    runCatching { tmp.delete() }
                    return@enqueue
                }
                val now = System.currentTimeMillis()
                db.updateReady(key, dst.absolutePath, size, now)
                db.upsertGroup(group, pinned = pinned, atMs = now)
                db.updateResourceReady(key, dst.absolutePath, size, now)
                events.cacheState(key, if (pinned) CacheState.PINNED else CacheState.READY)
                evictIfNeeded()
            },
        )
    }

    private fun evictIfNeeded() {
        val maxBytes = config.maxBytes.coerceAtLeast(16L * 1024L * 1024L)
        if (config.groupedEvictionEnabled) {
            var total = db.totalGroupedBytes()
            if (total <= maxBytes) return
            db.listGroupEvictionCandidates().forEach { group ->
                if (total <= maxBytes) return
                val groupBytes = db.groupSizeBytes(group.groupKey)
                evictGroupInternal(group.groupKey)
                total -= groupBytes
            }
            return
        }

        var total = db.totalBytes()
        if (total <= maxBytes) return
        db.listEvictionCandidates().forEach { entry ->
            if (total <= maxBytes) return
            runCatching { File(entry.path).delete() }
            db.delete(entry.cacheKey)
            events.cacheState(entry.cacheKey, CacheState.MISS)
            total -= entry.sizeBytes
        }
    }

    private fun evictGroupInternal(groupKey: String) {
        val resources = db.listResourcesByGroup(groupKey)
        resources.forEach { resource ->
            resource.path?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).delete() }
            }
            if (resource.type == CacheResourceType.PROGRESSIVE) {
                db.delete(resource.resourceKey)
                events.cacheState(resource.resourceKey, CacheState.MISS)
            }
            db.deleteResource(resource.resourceKey)
        }
        db.removeOfflineLicensesByGroup(groupKey)
        db.deleteGroup(groupKey)
    }

    private fun finalFileFor(cacheKey: String): File {
        val root = File(appContext.cacheDir, config.cacheDirName)
        return File(root, "${hash(cacheKey)}.bin")
    }

    private fun tempFileFor(cacheKey: String): File {
        val root = File(appContext.cacheDir, config.cacheDirName)
        return File(root, "${hash(cacheKey)}.tmp")
    }

    private fun cacheKey(item: MediaItem): String {
        return item.cacheKey?.takeIf { it.isNotBlank() } ?: item.uri.toString()
    }

    private fun cacheGroupKey(item: MediaItem): String {
        return item.cacheGroupKey?.takeIf { it.isNotBlank() }
            ?: item.cacheKey?.takeIf { it.isNotBlank() }
            ?: item.uri.toString()
    }

    private fun hash(v: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(v.toByteArray()).joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun isHttp(item: MediaItem): Boolean {
        val s = item.uri.scheme?.lowercase() ?: return false
        return s == "http" || s == "https"
    }
}

internal data class CacheResolveResult(
    val cacheKey: String,
    val localFile: File?,
    val pinned: Boolean,
)
