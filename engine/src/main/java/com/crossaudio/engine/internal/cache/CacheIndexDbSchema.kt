package com.crossaudio.engine.internal.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.crossaudio.engine.CacheState

internal fun createLegacyTables(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE cache_entries (
          cache_key TEXT PRIMARY KEY,
          uri TEXT NOT NULL,
          path TEXT NOT NULL,
          state TEXT NOT NULL,
          size_bytes INTEGER NOT NULL,
          last_access_ms INTEGER NOT NULL,
          pinned INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX idx_cache_last_access ON cache_entries(last_access_ms)")
    db.execSQL("CREATE INDEX idx_cache_pinned ON cache_entries(pinned)")
}

internal fun createGroupedTables(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS cache_groups (
          group_key TEXT PRIMARY KEY,
          pinned INTEGER NOT NULL,
          last_access_ms INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_access ON cache_groups(last_access_ms)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_pinned ON cache_groups(pinned)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS cache_resources (
          resource_key TEXT PRIMARY KEY,
          group_key TEXT NOT NULL,
          uri TEXT NOT NULL,
          path TEXT,
          type TEXT NOT NULL,
          state TEXT NOT NULL,
          size_bytes INTEGER NOT NULL,
          pinned INTEGER NOT NULL,
          last_access_ms INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_resources_group ON cache_resources(group_key)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_resources_access ON cache_resources(last_access_ms)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_resources_pinned ON cache_resources(pinned)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS offline_licenses (
          license_id TEXT PRIMARY KEY,
          media_key TEXT NOT NULL,
          group_key TEXT NOT NULL,
          scheme TEXT NOT NULL,
          key_set_id_b64 TEXT NOT NULL,
          created_at_ms INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_licenses_media ON offline_licenses(media_key, scheme)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_licenses_group ON offline_licenses(group_key)")
}

internal fun migrateV1EntriesToGrouped(db: SQLiteDatabase) {
    val c = db.query("cache_entries", null, null, null, null, null, null)
    c.use {
        while (it.moveToNext()) {
            val key = it.getString(it.getColumnIndexOrThrow("cache_key"))
            val uri = it.getString(it.getColumnIndexOrThrow("uri"))
            val path = it.getString(it.getColumnIndexOrThrow("path"))
            val state = it.getString(it.getColumnIndexOrThrow("state"))
            val size = it.getLong(it.getColumnIndexOrThrow("size_bytes"))
            val access = it.getLong(it.getColumnIndexOrThrow("last_access_ms"))
            val pinned = it.getInt(it.getColumnIndexOrThrow("pinned")) == 1

            val groupValues = ContentValues().apply {
                put("group_key", key)
                put("pinned", if (pinned) 1 else 0)
                put("last_access_ms", access)
            }
            db.insertWithOnConflict("cache_groups", null, groupValues, SQLiteDatabase.CONFLICT_REPLACE)

            val resourceValues = ContentValues().apply {
                put("resource_key", key)
                put("group_key", key)
                put("uri", uri)
                put("path", path)
                put("type", CacheResourceType.PROGRESSIVE.name)
                put("state", state)
                put("size_bytes", size)
                put("pinned", if (pinned) 1 else 0)
                put("last_access_ms", access)
            }
            db.insertWithOnConflict("cache_resources", null, resourceValues, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}

internal fun android.database.Cursor.toEntry(): CacheEntry {
    return CacheEntry(
        cacheKey = getString(getColumnIndexOrThrow("cache_key")),
        uri = getString(getColumnIndexOrThrow("uri")),
        path = getString(getColumnIndexOrThrow("path")),
        state = runCatching { CacheState.valueOf(getString(getColumnIndexOrThrow("state"))) }.getOrDefault(CacheState.MISS),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        lastAccessMs = getLong(getColumnIndexOrThrow("last_access_ms")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
    )
}

internal fun android.database.Cursor.toGroupEntry(): CacheGroupEntry {
    return CacheGroupEntry(
        groupKey = getString(getColumnIndexOrThrow("group_key")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
        lastAccessMs = getLong(getColumnIndexOrThrow("last_access_ms")),
    )
}

internal fun android.database.Cursor.toResourceEntry(): CacheResourceEntry {
    return CacheResourceEntry(
        resourceKey = getString(getColumnIndexOrThrow("resource_key")),
        groupKey = getString(getColumnIndexOrThrow("group_key")),
        uri = getString(getColumnIndexOrThrow("uri")),
        path = getString(getColumnIndexOrThrow("path")),
        type = runCatching { CacheResourceType.valueOf(getString(getColumnIndexOrThrow("type"))) }.getOrDefault(CacheResourceType.PROGRESSIVE),
        state = runCatching { CacheState.valueOf(getString(getColumnIndexOrThrow("state"))) }.getOrDefault(CacheState.MISS),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
        lastAccessMs = getLong(getColumnIndexOrThrow("last_access_ms")),
    )
}

internal fun android.database.Cursor.toOfflineLicenseRecord(): OfflineLicenseDbRecord {
    return OfflineLicenseDbRecord(
        licenseId = getString(getColumnIndexOrThrow("license_id")),
        mediaKey = getString(getColumnIndexOrThrow("media_key")),
        groupKey = getString(getColumnIndexOrThrow("group_key")),
        scheme = getString(getColumnIndexOrThrow("scheme")),
        keySetIdB64 = getString(getColumnIndexOrThrow("key_set_id_b64")),
        createdAtMs = getLong(getColumnIndexOrThrow("created_at_ms")),
    )
}
