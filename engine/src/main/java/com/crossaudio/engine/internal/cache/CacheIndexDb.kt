package com.crossaudio.engine.internal.cache

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import com.crossaudio.engine.CacheState

internal class CacheIndexDb(context: Context) : SQLiteOpenHelper(context, "cross_audio_cache.db", null, 2) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        createLegacyTables(db)
        createGroupedTables(db)
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createGroupedTables(db)
            migrateV1EntriesToGrouped(db)
        }
    }

    fun upsert(entry: CacheEntry) = upsertImpl(entry)
    fun get(cacheKey: String): CacheEntry? = getImpl(cacheKey)
    fun markPinned(cacheKey: String, pinned: Boolean) = markPinnedImpl(cacheKey, pinned)
    fun markState(cacheKey: String, state: CacheState) = markStateImpl(cacheKey, state)
    fun updateAccess(cacheKey: String, atMs: Long) = updateAccessImpl(cacheKey, atMs)
    fun updateReady(cacheKey: String, path: String, sizeBytes: Long, atMs: Long) = updateReadyImpl(cacheKey, path, sizeBytes, atMs)
    fun delete(cacheKey: String) = deleteImpl(cacheKey)
    fun totalBytes(): Long = totalBytesImpl()
    fun listEvictionCandidates(): List<CacheEntry> = listEvictionCandidatesImpl()
    fun listUnpinned(): List<CacheEntry> = listUnpinnedImpl()
    fun upsertGroup(groupKey: String, pinned: Boolean, atMs: Long = System.currentTimeMillis()) = upsertGroupImpl(groupKey, pinned, atMs)
    fun getGroup(groupKey: String): CacheGroupEntry? = getGroupImpl(groupKey)
    fun markGroupPinned(groupKey: String, pinned: Boolean) = markGroupPinnedImpl(groupKey, pinned)
    fun touchGroup(groupKey: String, atMs: Long = System.currentTimeMillis()) = touchGroupImpl(groupKey, atMs)
    fun listGroupEvictionCandidates(): List<CacheGroupEntry> = listGroupEvictionCandidatesImpl()
    fun deleteGroup(groupKey: String) = deleteGroupImpl(groupKey)
    fun upsertResource(entry: CacheResourceEntry) = upsertResourceImpl(entry)
    fun getResource(resourceKey: String): CacheResourceEntry? = getResourceImpl(resourceKey)
    fun markResourceState(resourceKey: String, state: CacheState) = markResourceStateImpl(resourceKey, state)
    fun updateResourceReady(resourceKey: String, path: String, sizeBytes: Long, atMs: Long) = updateResourceReadyImpl(resourceKey, path, sizeBytes, atMs)
    fun markResourcesPinned(groupKey: String, pinned: Boolean) = markResourcesPinnedImpl(groupKey, pinned)
    fun listResourcesByGroup(groupKey: String): List<CacheResourceEntry> = listResourcesByGroupImpl(groupKey)
    fun deleteResource(resourceKey: String) = deleteResourceImpl(resourceKey)
    fun deleteResourcesByGroup(groupKey: String) = deleteResourcesByGroupImpl(groupKey)
    fun totalGroupedBytes(): Long = totalGroupedBytesImpl()
    fun groupSizeBytes(groupKey: String): Long = groupSizeBytesImpl(groupKey)

    fun upsertOfflineLicense(record: OfflineLicenseDbRecord) = upsertOfflineLicenseImpl(record)
    fun getOfflineLicense(licenseId: String): OfflineLicenseDbRecord? = getOfflineLicenseImpl(licenseId)
    fun findOfflineLicenseByMedia(mediaKey: String, scheme: String): OfflineLicenseDbRecord? = findOfflineLicenseByMediaImpl(mediaKey, scheme)
    fun removeOfflineLicense(licenseId: String) = removeOfflineLicenseImpl(licenseId)
    fun listOfflineLicensesByGroup(groupKey: String): List<OfflineLicenseDbRecord> = listOfflineLicensesByGroupImpl(groupKey)
    fun removeOfflineLicensesByGroup(groupKey: String) = removeOfflineLicensesByGroupImpl(groupKey)
}
