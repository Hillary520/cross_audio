package com.crossaudio.engine.service

import android.content.Context
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.QualityHint
import com.crossaudio.engine.RepeatMode
import com.crossaudio.engine.SourceType
import org.json.JSONArray
import org.json.JSONObject

internal object CrossAudioBrowseCatalogStore {
    private const val PREFS = "crossaudio_browse_catalog"
    private const val KEY_LAST_QUEUE = "last_queue_items_json"
    private const val KEY_PLAYBACK_SNAPSHOT = "last_playback_snapshot_json"

    fun saveQueue(context: Context, items: List<MediaItem>) {
        val payload = serializeItems(items).toString()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_QUEUE, payload)
            .apply()
    }

    fun loadQueue(context: Context): List<MediaItem> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_QUEUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching { parseItems(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun savePlaybackSnapshot(
        context: Context,
        items: List<MediaItem>,
        currentIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
        playOrder: IntArray,
        orderCursor: Int,
    ) {
        if (items.isEmpty() || currentIndex !in items.indices) {
            clearPlaybackSnapshot(context)
            return
        }
        val payload = JSONObject().apply {
            put("currentIndex", currentIndex)
            put("positionMs", positionMs.coerceAtLeast(0L))
            put("shuffleEnabled", shuffleEnabled)
            put("repeatMode", repeatMode.name)
            put("playOrder", JSONArray(playOrder.toList()))
            put("orderCursor", orderCursor)
            put("items", serializeItems(items))
            put("savedAtEpochMs", System.currentTimeMillis())
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYBACK_SNAPSHOT, payload.toString())
            .apply()
    }

    fun loadPlaybackSnapshot(context: Context): PlaybackSnapshot? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYBACK_SNAPSHOT, null)?.takeIf { it.isNotBlank() }
        if (raw.isNullOrBlank()) {
            val queueOnly = loadQueue(context)
            if (queueOnly.isEmpty()) return null
            return PlaybackSnapshot(
                items = queueOnly,
                currentIndex = 0,
                positionMs = 0L,
            )
        }
        return runCatching {
            val obj = JSONObject(raw)
            val items = parseItems(obj.optJSONArray("items") ?: JSONArray())
            if (items.isEmpty()) return@runCatching null
            val currentIndex = obj.optInt("currentIndex", 0).coerceIn(0, items.lastIndex)
            val positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L)
            val shuffleEnabled = obj.optBoolean("shuffleEnabled", false)
            val repeatMode = runCatching {
                RepeatMode.valueOf(obj.optString("repeatMode", RepeatMode.OFF.name))
            }.getOrDefault(RepeatMode.OFF)
            val playOrderJson = obj.optJSONArray("playOrder") ?: JSONArray()
            val playOrder = IntArray(playOrderJson.length()) { idx -> playOrderJson.optInt(idx, idx) }
            val orderCursor = obj.optInt("orderCursor", playOrder.indexOf(currentIndex).coerceAtLeast(0))
            PlaybackSnapshot(
                items = items,
                currentIndex = currentIndex,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                playOrder = playOrder,
                orderCursor = orderCursor,
            )
        }.getOrNull()
    }

    fun clearPlaybackSnapshot(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PLAYBACK_SNAPSHOT)
            .apply()
    }

    private fun serializeItems(items: List<MediaItem>): JSONArray {
        return JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("uri", item.uri.toString())
                        put("title", item.title)
                        put("artist", item.artist)
                        put("artworkUri", item.artworkUri)
                        put("durationMs", item.durationMs ?: JSONObject.NULL)
                        put("queueEntryId", item.queueEntryId)
                        put("cacheKey", item.cacheKey)
                        put("cacheGroupKey", item.cacheGroupKey)
                        put("sourceType", item.sourceType.name)
                        put("qualityHint", item.qualityHint?.name)
                        put("headers", JSONObject(item.headers))
                    },
                )
            }
        }
    }

    private fun parseItems(arr: JSONArray): List<MediaItem> {
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val uri = obj.optString("uri").takeIf { it.isNotBlank() } ?: continue
                add(
                    MediaItem(
                        uri = android.net.Uri.parse(uri),
                        title = obj.optString("title").takeIf { it.isNotBlank() },
                        artist = obj.optString("artist").takeIf { it.isNotBlank() },
                        artworkUri = obj.optString("artworkUri").takeIf { it.isNotBlank() },
                        durationMs = obj.optLong("durationMs").takeIf { it > 0L },
                        headers = obj.optJSONObject("headers")?.toStringMap().orEmpty(),
                        queueEntryId = obj.optString("queueEntryId").takeIf { it.isNotBlank() }
                            ?: "restored:$i:${uri.hashCode()}",
                        cacheKey = obj.optString("cacheKey").takeIf { it.isNotBlank() },
                        cacheGroupKey = obj.optString("cacheGroupKey").takeIf { it.isNotBlank() },
                        sourceType = runCatching {
                            SourceType.valueOf(obj.optString("sourceType", SourceType.AUTO.name))
                        }.getOrDefault(SourceType.AUTO),
                        qualityHint = runCatching {
                            obj.optString("qualityHint").takeIf { it.isNotBlank() }?.let(QualityHint::valueOf)
                        }.getOrNull(),
                    ),
                )
            }
        }
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        return buildMap {
            val keys = keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, optString(key, ""))
            }
        }
    }

    data class PlaybackSnapshot(
        val items: List<MediaItem>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val playOrder: IntArray = intArrayOf(),
        val orderCursor: Int = 0,
    )
}
