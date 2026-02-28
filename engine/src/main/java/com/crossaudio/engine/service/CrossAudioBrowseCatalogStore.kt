package com.crossaudio.engine.service

import android.content.Context
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.QualityHint
import com.crossaudio.engine.SourceType
import org.json.JSONArray
import org.json.JSONObject

internal object CrossAudioBrowseCatalogStore {
    private const val PREFS = "crossaudio_browse_catalog"
    private const val KEY_LAST_QUEUE = "last_queue_items_json"

    fun saveQueue(context: Context, items: List<MediaItem>) {
        val payload = JSONArray()
        items.forEach { item ->
            payload.put(
                JSONObject().apply {
                    put("uri", item.uri.toString())
                    put("title", item.title)
                    put("artist", item.artist)
                    put("artworkUri", item.artworkUri)
                    put("durationMs", item.durationMs ?: JSONObject.NULL)
                    put("cacheKey", item.cacheKey)
                    put("cacheGroupKey", item.cacheGroupKey)
                    put("sourceType", item.sourceType.name)
                    put("qualityHint", item.qualityHint?.name)
                    put("headers", JSONObject(item.headers))
                },
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_QUEUE, payload.toString())
            .apply()
    }

    fun loadQueue(context: Context): List<MediaItem> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_QUEUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
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
        }.getOrDefault(emptyList())
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
}
