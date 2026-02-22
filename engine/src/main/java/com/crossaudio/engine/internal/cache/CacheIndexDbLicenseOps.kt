package com.crossaudio.engine.internal.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

internal fun CacheIndexDb.upsertOfflineLicenseImpl(record: OfflineLicenseDbRecord) {
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

internal fun CacheIndexDb.getOfflineLicenseImpl(licenseId: String): OfflineLicenseDbRecord? {
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

internal fun CacheIndexDb.findOfflineLicenseByMediaImpl(mediaKey: String, scheme: String): OfflineLicenseDbRecord? {
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

internal fun CacheIndexDb.removeOfflineLicenseImpl(licenseId: String) {
    writableDatabase.delete("offline_licenses", "license_id=?", arrayOf(licenseId))
}

internal fun CacheIndexDb.listOfflineLicensesByGroupImpl(groupKey: String): List<OfflineLicenseDbRecord> {
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

internal fun CacheIndexDb.removeOfflineLicensesByGroupImpl(groupKey: String) {
    writableDatabase.delete("offline_licenses", "group_key=?", arrayOf(groupKey))
}
