package com.crossaudio.engine.internal.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.crossaudio.engine.CacheState

internal fun CacheIndexDb.upsertImpl(entry: CacheEntry) {
    val values = ContentValues().apply {
        put("cache_key", entry.cacheKey)
        put("uri", entry.uri)
        put("path", entry.path)
        put("state", entry.state.name)
        put("size_bytes", entry.sizeBytes)
        put("last_access_ms", entry.lastAccessMs)
        put("pinned", if (entry.pinned) 1 else 0)
    }
    writableDatabase.insertWithOnConflict("cache_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
}

internal fun CacheIndexDb.getImpl(cacheKey: String): CacheEntry? {
    val c = readableDatabase.query(
        "cache_entries",
        null,
        "cache_key=?",
        arrayOf(cacheKey),
        null,
        null,
        null,
        "1",
    )
    c.use {
        if (!it.moveToFirst()) return null
        return it.toEntry()
    }
}

internal fun CacheIndexDb.markPinnedImpl(cacheKey: String, pinned: Boolean) {
    writableDatabase.execSQL(
        "UPDATE cache_entries SET pinned=? WHERE cache_key=?",
        arrayOf<Any?>(if (pinned) 1 else 0, cacheKey),
    )
}

internal fun CacheIndexDb.markStateImpl(cacheKey: String, state: CacheState) {
    writableDatabase.execSQL(
        "UPDATE cache_entries SET state=? WHERE cache_key=?",
        arrayOf<Any?>(state.name, cacheKey),
    )
}

internal fun CacheIndexDb.updateAccessImpl(cacheKey: String, atMs: Long) {
    writableDatabase.execSQL(
        "UPDATE cache_entries SET last_access_ms=? WHERE cache_key=?",
        arrayOf<Any?>(atMs, cacheKey),
    )
}

internal fun CacheIndexDb.updateReadyImpl(cacheKey: String, path: String, sizeBytes: Long, atMs: Long) {
    writableDatabase.execSQL(
        "UPDATE cache_entries SET path=?, size_bytes=?, state=?, last_access_ms=? WHERE cache_key=?",
        arrayOf<Any?>(path, sizeBytes, CacheState.READY.name, atMs, cacheKey),
    )
}

internal fun CacheIndexDb.deleteImpl(cacheKey: String) {
    writableDatabase.delete("cache_entries", "cache_key=?", arrayOf(cacheKey))
}

internal fun CacheIndexDb.totalBytesImpl(): Long {
    val c = readableDatabase.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM cache_entries", null)
    c.use {
        if (!it.moveToFirst()) return 0L
        return it.getLong(0)
    }
}

internal fun CacheIndexDb.listEvictionCandidatesImpl(): List<CacheEntry> {
    val out = mutableListOf<CacheEntry>()
    val c = readableDatabase.query(
        "cache_entries",
        null,
        "pinned=0",
        null,
        null,
        null,
        "last_access_ms ASC",
    )
    c.use {
        while (it.moveToNext()) {
            out.add(it.toEntry())
        }
    }
    return out
}

internal fun CacheIndexDb.listUnpinnedImpl(): List<CacheEntry> {
    val out = mutableListOf<CacheEntry>()
    val c = readableDatabase.query(
        "cache_entries",
        null,
        "pinned=0",
        null,
        null,
        null,
        null,
    )
    c.use {
        while (it.moveToNext()) {
            out.add(it.toEntry())
        }
    }
    return out
}

internal fun CacheIndexDb.upsertGroupImpl(groupKey: String, pinned: Boolean, atMs: Long = System.currentTimeMillis()) {
    val current = getGroup(groupKey)
    val values = ContentValues().apply {
        put("group_key", groupKey)
        put("pinned", if (pinned || current?.pinned == true) 1 else 0)
        put("last_access_ms", maxOf(current?.lastAccessMs ?: 0L, atMs))
    }
    writableDatabase.insertWithOnConflict("cache_groups", null, values, SQLiteDatabase.CONFLICT_REPLACE)
}

internal fun CacheIndexDb.getGroupImpl(groupKey: String): CacheGroupEntry? {
    val c = readableDatabase.query(
        "cache_groups",
        null,
        "group_key=?",
        arrayOf(groupKey),
        null,
        null,
        null,
        "1",
    )
    c.use {
        if (!it.moveToFirst()) return null
        return it.toGroupEntry()
    }
}

internal fun CacheIndexDb.markGroupPinnedImpl(groupKey: String, pinned: Boolean) {
    writableDatabase.execSQL(
        "UPDATE cache_groups SET pinned=? WHERE group_key=?",
        arrayOf<Any?>(if (pinned) 1 else 0, groupKey),
    )
}

internal fun CacheIndexDb.touchGroupImpl(groupKey: String, atMs: Long = System.currentTimeMillis()) {
    writableDatabase.execSQL(
        "UPDATE cache_groups SET last_access_ms=? WHERE group_key=?",
        arrayOf<Any?>(atMs, groupKey),
    )
}

internal fun CacheIndexDb.listGroupEvictionCandidatesImpl(): List<CacheGroupEntry> {
    val out = mutableListOf<CacheGroupEntry>()
    val c = readableDatabase.query(
        "cache_groups",
        null,
        "pinned=0",
        null,
        null,
        null,
        "last_access_ms ASC",
    )
    c.use {
        while (it.moveToNext()) {
            out += it.toGroupEntry()
        }
    }
    return out
}

internal fun CacheIndexDb.deleteGroupImpl(groupKey: String) {
    writableDatabase.delete("cache_groups", "group_key=?", arrayOf(groupKey))
}
