package com.crossaudio.engine.internal.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.crossaudio.engine.CacheState

internal class CacheIndexDb(context: Context) : SQLiteOpenHelper(context, "cross_audio_cache.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        createLegacyTables(db)
        createGroupedTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createGroupedTables(db)
            migrateV1EntriesToGrouped(db)
        }
    }

    fun upsert(entry: CacheEntry) {
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

    fun get(cacheKey: String): CacheEntry? {
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

    fun markPinned(cacheKey: String, pinned: Boolean) {
        writableDatabase.execSQL(
            "UPDATE cache_entries SET pinned=? WHERE cache_key=?",
            arrayOf<Any?>(if (pinned) 1 else 0, cacheKey),
        )
    }

    fun markState(cacheKey: String, state: CacheState) {
        writableDatabase.execSQL(
            "UPDATE cache_entries SET state=? WHERE cache_key=?",
            arrayOf<Any?>(state.name, cacheKey),
        )
    }

    fun updateAccess(cacheKey: String, atMs: Long) {
        writableDatabase.execSQL(
            "UPDATE cache_entries SET last_access_ms=? WHERE cache_key=?",
            arrayOf<Any?>(atMs, cacheKey),
        )
    }

    fun updateReady(cacheKey: String, path: String, sizeBytes: Long, atMs: Long) {
        writableDatabase.execSQL(
            "UPDATE cache_entries SET path=?, size_bytes=?, state=?, last_access_ms=? WHERE cache_key=?",
            arrayOf<Any?>(path, sizeBytes, CacheState.READY.name, atMs, cacheKey),
        )
    }

    fun delete(cacheKey: String) {
        writableDatabase.delete("cache_entries", "cache_key=?", arrayOf(cacheKey))
    }

    fun totalBytes(): Long {
        val c = readableDatabase.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM cache_entries", null)
        c.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }

    fun listEvictionCandidates(): List<CacheEntry> {
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

    fun listUnpinned(): List<CacheEntry> {
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

    fun upsertGroup(groupKey: String, pinned: Boolean, atMs: Long = System.currentTimeMillis()) {
        val current = getGroup(groupKey)
        val values = ContentValues().apply {
            put("group_key", groupKey)
            put("pinned", if (pinned || current?.pinned == true) 1 else 0)
            put("last_access_ms", maxOf(current?.lastAccessMs ?: 0L, atMs))
        }
        writableDatabase.insertWithOnConflict("cache_groups", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getGroup(groupKey: String): CacheGroupEntry? {
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

    fun markGroupPinned(groupKey: String, pinned: Boolean) {
        writableDatabase.execSQL(
            "UPDATE cache_groups SET pinned=? WHERE group_key=?",
            arrayOf<Any?>(if (pinned) 1 else 0, groupKey),
        )
    }

    fun touchGroup(groupKey: String, atMs: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "UPDATE cache_groups SET last_access_ms=? WHERE group_key=?",
            arrayOf<Any?>(atMs, groupKey),
        )
    }

    fun listGroupEvictionCandidates(): List<CacheGroupEntry> {
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

    fun deleteGroup(groupKey: String) {
        writableDatabase.delete("cache_groups", "group_key=?", arrayOf(groupKey))
    }

    fun upsertResource(entry: CacheResourceEntry) {
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

    fun getResource(resourceKey: String): CacheResourceEntry? {
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

    fun markResourceState(resourceKey: String, state: CacheState) {
        writableDatabase.execSQL(
            "UPDATE cache_resources SET state=? WHERE resource_key=?",
            arrayOf<Any?>(state.name, resourceKey),
        )
    }

    fun updateResourceReady(resourceKey: String, path: String, sizeBytes: Long, atMs: Long) {
        writableDatabase.execSQL(
            "UPDATE cache_resources SET path=?, size_bytes=?, state=?, last_access_ms=? WHERE resource_key=?",
            arrayOf<Any?>(path, sizeBytes, CacheState.READY.name, atMs, resourceKey),
        )
    }

    fun markResourcesPinned(groupKey: String, pinned: Boolean) {
        writableDatabase.execSQL(
            "UPDATE cache_resources SET pinned=? WHERE group_key=?",
            arrayOf<Any?>(if (pinned) 1 else 0, groupKey),
        )
    }

    fun listResourcesByGroup(groupKey: String): List<CacheResourceEntry> {
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

    fun deleteResource(resourceKey: String) {
        writableDatabase.delete("cache_resources", "resource_key=?", arrayOf(resourceKey))
    }

    fun deleteResourcesByGroup(groupKey: String) {
        writableDatabase.delete("cache_resources", "group_key=?", arrayOf(groupKey))
    }

    fun totalGroupedBytes(): Long {
        val c = readableDatabase.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM cache_resources", null)
        c.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }

    fun groupSizeBytes(groupKey: String): Long {
        val c = readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(size_bytes),0) FROM cache_resources WHERE group_key=?",
            arrayOf(groupKey),
        )
        c.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }

    fun upsertOfflineLicense(record: OfflineLicenseDbRecord) {
        val values = ContentValues().apply {
            put("license_id", record.licenseId)
            put("media_key", record.mediaKey)
            put("group_key", record.groupKey)
            put("scheme", record.scheme)
            put("key_set_id_b64", record.keySetIdB64)
            put("created_at_ms", record.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("offline_licenses", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getOfflineLicense(licenseId: String): OfflineLicenseDbRecord? {
        val c = readableDatabase.query(
            "offline_licenses",
            null,
            "license_id=?",
            arrayOf(licenseId),
            null,
            null,
            null,
            "1",
        )
        c.use {
            if (!it.moveToFirst()) return null
            return it.toOfflineLicenseRecord()
        }
    }

    fun findOfflineLicenseByMedia(mediaKey: String, scheme: String): OfflineLicenseDbRecord? {
        val c = readableDatabase.query(
            "offline_licenses",
            null,
            "media_key=? AND scheme=?",
            arrayOf(mediaKey, scheme),
            null,
            null,
            "created_at_ms DESC",
            "1",
        )
        c.use {
            if (!it.moveToFirst()) return null
            return it.toOfflineLicenseRecord()
        }
    }

    fun removeOfflineLicense(licenseId: String) {
        writableDatabase.delete("offline_licenses", "license_id=?", arrayOf(licenseId))
    }

    fun listOfflineLicensesByGroup(groupKey: String): List<OfflineLicenseDbRecord> {
        val out = mutableListOf<OfflineLicenseDbRecord>()
        val c = readableDatabase.query(
            "offline_licenses",
            null,
            "group_key=?",
            arrayOf(groupKey),
            null,
            null,
            "created_at_ms ASC",
        )
        c.use {
            while (it.moveToNext()) {
                out += it.toOfflineLicenseRecord()
            }
        }
        return out
    }

    fun removeOfflineLicensesByGroup(groupKey: String) {
        writableDatabase.delete("offline_licenses", "group_key=?", arrayOf(groupKey))
    }

    private fun createLegacyTables(db: SQLiteDatabase) {
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

    private fun createGroupedTables(db: SQLiteDatabase) {
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

    private fun migrateV1EntriesToGrouped(db: SQLiteDatabase) {
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

    private fun android.database.Cursor.toEntry(): CacheEntry {
        return CacheEntry(
            cacheKey = getString(getColumnIndexOrThrow("cache_key")),
            uri = getString(getColumnIndexOrThrow("uri")),
            path = getString(getColumnIndexOrThrow("path")),
            state = runCatching {
                CacheState.valueOf(getString(getColumnIndexOrThrow("state")))
            }.getOrDefault(CacheState.MISS),
            sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
            lastAccessMs = getLong(getColumnIndexOrThrow("last_access_ms")),
            pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
        )
    }

    private fun android.database.Cursor.toGroupEntry(): CacheGroupEntry {
        return CacheGroupEntry(
            groupKey = getString(getColumnIndexOrThrow("group_key")),
            pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
            lastAccessMs = getLong(getColumnIndexOrThrow("last_access_ms")),
        )
    }

    private fun android.database.Cursor.toResourceEntry(): CacheResourceEntry {
        return CacheResourceEntry(
            resourceKey = getString(getColumnIndexOrThrow("resource_key")),
            groupKey = getString(getColumnIndexOrThrow("group_key")),
            uri = getString(getColumnIndexOrThrow("uri")),
            path = getString(getColumnIndexOrThrow("path")),
            type = runCatching { CacheResourceType.valueOf(getString(getColumnIndexOrThrow("type"))) }
                .getOrDefault(CacheResourceType.PROGRESSIVE),
            state = runCatching { CacheState.valueOf(getString(getColumnIndexOrThrow("state"))) }
                .getOrDefault(CacheState.MISS),
            sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
            pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
            lastAccessMs = getLong(getColumnIndexOrThrow("last_access_ms")),
        )
    }

    private fun android.database.Cursor.toOfflineLicenseRecord(): OfflineLicenseDbRecord {
        return OfflineLicenseDbRecord(
            licenseId = getString(getColumnIndexOrThrow("license_id")),
            mediaKey = getString(getColumnIndexOrThrow("media_key")),
            groupKey = getString(getColumnIndexOrThrow("group_key")),
            scheme = getString(getColumnIndexOrThrow("scheme")),
            keySetIdB64 = getString(getColumnIndexOrThrow("key_set_id_b64")),
            createdAtMs = getLong(getColumnIndexOrThrow("created_at_ms")),
        )
    }
}
