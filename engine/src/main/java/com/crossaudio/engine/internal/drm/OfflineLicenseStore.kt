package com.crossaudio.engine.internal.drm

import android.content.Context
import android.util.Base64
import com.crossaudio.engine.internal.cache.CacheIndexDb
import com.crossaudio.engine.internal.cache.OfflineLicenseDbRecord

internal data class OfflineLicenseRecord(
    val licenseId: String,
    val mediaKey: String,
    val groupKey: String,
    val scheme: String,
    val keySetIdB64: String,
    val createdAtMs: Long,
)

internal class OfflineLicenseStore(context: Context) {
    private val legacyPrefs = context.applicationContext.getSharedPreferences("crossaudio_offline_licenses", Context.MODE_PRIVATE)
    private val db = CacheIndexDb(context.applicationContext)

    init {
        migrateLegacyPrefsToDb()
    }

    fun put(record: OfflineLicenseRecord) {
        db.upsertOfflineLicense(
            OfflineLicenseDbRecord(
                licenseId = record.licenseId,
                mediaKey = record.mediaKey,
                groupKey = record.groupKey,
                scheme = record.scheme,
                keySetIdB64 = record.keySetIdB64,
                createdAtMs = record.createdAtMs,
            ),
        )
    }

    fun get(licenseId: String): OfflineLicenseRecord? {
        return db.getOfflineLicense(licenseId)?.toStoreRecord()
    }

    fun findByMediaKey(mediaKey: String, scheme: String): OfflineLicenseRecord? {
        return db.findOfflineLicenseByMedia(mediaKey, scheme)?.toStoreRecord()
    }

    fun remove(licenseId: String) {
        db.removeOfflineLicense(licenseId)
        legacyPrefs.edit().remove(licenseId).apply()
    }

    fun close() {
        db.close()
    }

    private fun encode(record: OfflineLicenseRecord): String {
        val mediaB64 = Base64.encodeToString(record.mediaKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val groupB64 = Base64.encodeToString(record.groupKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return listOf(mediaB64, groupB64, record.scheme, record.createdAtMs.toString(), record.keySetIdB64).joinToString("|")
    }

    private fun decode(licenseId: String, payload: String): OfflineLicenseRecord? {
        val parts = payload.split('|')
        if (parts.size !in 4..5) return null
        val media = runCatching { String(Base64.decode(parts[0], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return null
        val group = if (parts.size >= 5) {
            runCatching { String(Base64.decode(parts[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: media
        } else {
            media
        }
        val scheme = if (parts.size >= 5) parts[2] else parts[1]
        val created = (if (parts.size >= 5) parts[3] else parts[2]).toLongOrNull() ?: 0L
        val keySet = if (parts.size >= 5) parts[4] else parts[3]
        return OfflineLicenseRecord(
            licenseId = licenseId,
            mediaKey = media,
            groupKey = group,
            scheme = scheme,
            keySetIdB64 = keySet,
            createdAtMs = created,
        )
    }

    private fun OfflineLicenseDbRecord.toStoreRecord(): OfflineLicenseRecord {
        return OfflineLicenseRecord(
            licenseId = licenseId,
            mediaKey = mediaKey,
            groupKey = groupKey,
            scheme = scheme,
            keySetIdB64 = keySetIdB64,
            createdAtMs = createdAtMs,
        )
    }

    private fun migrateLegacyPrefsToDb() {
        val legacyValues = legacyPrefs.all
        if (legacyValues.isEmpty()) return

        legacyValues.forEach { (licenseId, value) ->
            val payload = value as? String ?: return@forEach
            val decoded = decode(licenseId, payload) ?: return@forEach
            db.upsertOfflineLicense(
                OfflineLicenseDbRecord(
                    licenseId = decoded.licenseId,
                    mediaKey = decoded.mediaKey,
                    groupKey = decoded.groupKey,
                    scheme = decoded.scheme,
                    keySetIdB64 = decoded.keySetIdB64,
                    createdAtMs = decoded.createdAtMs,
                ),
            )
        }
        legacyPrefs.edit().clear().apply()
    }
}
