package com.crossaudio.engine.service

import android.content.Intent
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.crossaudio.engine.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight MediaBrowserService surface for Android Auto and other browse clients.
 * It exposes the last known playback queue and transport actions.
 */
class CrossAudioMediaBrowserService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "CrossAudioBrowserSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    CrossAudioPlaybackService.start(
                        context = this@CrossAudioMediaBrowserService,
                        action = CrossAudioPlaybackService.ACTION_PLAY,
                    )
                }

                override fun onPause() {
                    CrossAudioPlaybackService.start(
                        context = this@CrossAudioMediaBrowserService,
                        action = CrossAudioPlaybackService.ACTION_PAUSE,
                    )
                }

                override fun onSkipToNext() {
                    CrossAudioPlaybackService.start(
                        context = this@CrossAudioMediaBrowserService,
                        action = CrossAudioPlaybackService.ACTION_NEXT,
                    )
                }

                override fun onSkipToPrevious() {
                    CrossAudioPlaybackService.start(
                        context = this@CrossAudioMediaBrowserService,
                        action = CrossAudioPlaybackService.ACTION_PREV,
                    )
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    handlePlayFromMediaId(mediaId)
                }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                )
                .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f)
                .build()
            setPlaybackState(state)
            isActive = true
        }
        sessionToken = session.sessionToken
    }

    override fun onDestroy() {
        runCatching { session.release() }
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, Bundle())
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        val items = when (parentId) {
            ROOT_ID -> mutableListOf(
                browseItem(
                    mediaId = ACTION_RESUME_ID,
                    title = "Resume playback",
                ),
                browseItem(
                    mediaId = QUEUE_NODE_ID,
                    title = "Last queue",
                    browsable = true,
                ),
            )
            QUEUE_NODE_ID -> {
                val queue = CrossAudioBrowseCatalogStore.loadQueue(this)
                queue.mapIndexed { index, item ->
                    playableItem(
                        mediaId = "queue:$index",
                        title = item.title ?: item.uri.toString(),
                        subtitle = item.artist,
                    )
                }.toMutableList()
            }
            else -> mutableListOf()
        }
        result.sendResult(items)
    }

    private fun handlePlayFromMediaId(mediaId: String?) {
        when {
            mediaId == ACTION_RESUME_ID -> {
                CrossAudioPlaybackService.start(
                    context = this,
                    action = CrossAudioPlaybackService.ACTION_PLAY,
                )
            }
            mediaId?.startsWith("queue:") == true -> {
                val startIndex = mediaId.substringAfter(':').toIntOrNull() ?: return
                val queue = CrossAudioBrowseCatalogStore.loadQueue(this)
                if (queue.isEmpty() || startIndex !in queue.indices) {
                    CrossAudioPlaybackService.start(
                        context = this,
                        action = CrossAudioPlaybackService.ACTION_PLAY,
                    )
                    return
                }
                val rotated = queue.drop(startIndex) + queue.take(startIndex)
                CrossAudioPlaybackService.start(
                    context = this,
                    action = CrossAudioPlaybackService.ACTION_PLAY,
                    itemsJson = itemsJson(rotated),
                    replaceQueue = true,
                )
            }
        }
    }

    private fun itemsJson(items: List<MediaItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("uri", item.uri.toString())
                    put("title", item.title)
                    put("artist", item.artist)
                    put("artworkUri", item.artworkUri)
                    put("durationMs", item.durationMs ?: JSONObject.NULL)
                    put("cacheKey", item.cacheKey)
                    put("cacheGroupKey", item.cacheGroupKey)
                    put("sourceType", item.sourceType.name)
                    put("headers", JSONObject(item.headers))
                },
            )
        }
        return arr.toString()
    }

    private fun browseItem(
        mediaId: String,
        title: String,
        browsable: Boolean = false,
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .build()
        return MediaBrowserCompat.MediaItem(
            desc,
            if (browsable) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
        )
    }

    private fun playableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    companion object {
        private const val ROOT_ID = "crossaudio_root"
        private const val QUEUE_NODE_ID = "crossaudio_last_queue"
        private const val ACTION_RESUME_ID = "crossaudio_action_resume"
    }
}
