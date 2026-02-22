package com.crossaudio.engine.internal.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.crossaudio.engine.CacheState

internal fun CacheIndexDb.upsertResourceImpl(entry: CacheResourceEntry) {
    val values = ContentValues().apply {
        put("resource_key", entry.resourceKey)
        put("group_key", entry.groupKey)
        put("uri", entry.uri)
        put("path", entry.path)
        put("type", entry.type.name)
        put("state", entry.state.name)
        put("size_bytes", entry.sizeBytes)
        put("pinned", if (entry.pinned) 1 else 0)
        put("last_access_ms", entry.lastAccessMs)
    }
    writableDatabase.insertWithOnConflict("cache_resources", null, values, SQLiteDatabase.CONFLICT_REPLACE)
}

internal fun CacheIndexDb.getResourceImpl(resourceKey: String): CacheResourceEntry? {
    val c = readableDatabase.query(
        "cache_resources",
        null,
        "resource_key=?",
        arrayOf(resourceKey),
        null,
        null,
        null,
        "1",
    )
    c.use {
        if (!it.moveToFirst()) return null
        return it.toResourceEntry()
    }
}

internal fun CacheIndexDb.markResourceStateImpl(resourceKey: String, state: CacheState) {
    writableDatabase.execSQL(
        "UPDATE cache_resources SET state=? WHERE resource_key=?",
        arrayOf<Any?>(state.name, resourceKey),
    )
}

internal fun CacheIndexDb.updateResourceReadyImpl(resourceKey: String, path: String, sizeBytes: Long, atMs: Long) {
    writableDatabase.execSQL(
        "UPDATE cache_resources SET path=?, size_bytes=?, state=?, last_access_ms=? WHERE resource_key=?",
        arrayOf<Any?>(path, sizeBytes, CacheState.READY.name, atMs, resourceKey),
    )
}

internal fun CacheIndexDb.markResourcesPinnedImpl(groupKey: String, pinned: Boolean) {
    writableDatabase.execSQL(
        "UPDATE cache_resources SET pinned=? WHERE group_key=?",
        arrayOf<Any?>(if (pinned) 1 else 0, groupKey),
    )
}

internal fun CacheIndexDb.listResourcesByGroupImpl(groupKey: String): List<CacheResourceEntry> {
    val out = mutableListOf<CacheResourceEntry>()
    val c = readableDatabase.query(
        "cache_resources",
        null,
        "group_key=?",
        arrayOf(groupKey),
        null,
        null,
        "last_access_ms ASC",
    )
    c.use {
        while (it.moveToNext()) {
            out += it.toResourceEntry()
        }
    }
    return out
}

internal fun CacheIndexDb.deleteResourceImpl(resourceKey: String) {
    writableDatabase.delete("cache_resources", "resource_key=?", arrayOf(resourceKey))
}

internal fun CacheIndexDb.deleteResourcesByGroupImpl(groupKey: String) {
    writableDatabase.delete("cache_resources", "group_key=?", arrayOf(groupKey))
}

internal fun CacheIndexDb.totalGroupedBytesImpl(): Long {
    val c = readableDatabase.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM cache_resources", null)
    c.use {
        if (!it.moveToFirst()) return 0L
        return it.getLong(0)
    }
}

internal fun CacheIndexDb.groupSizeBytesImpl(groupKey: String): Long {
    val c = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(size_bytes),0) FROM cache_resources WHERE group_key=?",
        arrayOf(groupKey),
    )
    c.use {
        if (!it.moveToFirst()) return 0L
        return it.getLong(0)
    }
}
