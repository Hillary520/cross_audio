package com.crossaudio.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.crossaudio.engine.CacheEngine
import com.crossaudio.engine.CacheInfo
import com.crossaudio.engine.CacheState
import com.crossaudio.engine.CrossAudioEngine
import com.crossaudio.engine.AdaptiveStreamingEngine
import com.crossaudio.engine.DrmEngine
import com.crossaudio.engine.DrmGlobalConfig
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.OfflineLicenseResult
import com.crossaudio.engine.PlayerState
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.QueueMutableEngine
import com.crossaudio.engine.ShuffleEngine
import com.crossaudio.engine.StreamInfo
import com.crossaudio.engine.StreamingConfig
import com.crossaudio.engine.platform.PlatformPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the player and exposes lock screen / BT controls via MediaSession,
 * with an ongoing media-style notification.
 */
class CrossAudioPlaybackService : Service() {
    class Binder internal constructor(val service: CrossAudioPlaybackService) : android.os.Binder()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal lateinit var session: MediaSessionCompat
    internal lateinit var notificationManager: NotificationManager
    internal val core by lazy { CrossAudioEngine(this) }
    internal val engine by lazy { PlatformPlayerEngine(this, core) }
    internal var isForeground = false
    internal var lastNotifKind: String? = null
    internal var lastNotifItemKey: String? = null
    internal var lastNotifUpdateMs: Long = 0L
    internal var lastSessionUpdateMs: Long = 0L
    internal var lastUnderruns: Long = 0L
    internal var lastUnderrunLogMs: Long = 0L
    internal var effectSessionOpened: Boolean = false
    internal var effectSessionId: Int = 0
    @Volatile internal var lastPlayerState: PlayerState = PlayerState.Idle
    @Volatile internal var sessionItem: MediaItem? = null
    @Volatile internal var sessionPositionMs: Long = 0L
    @Volatile internal var currentArtworkUrl: String? = null
    @Volatile internal var currentArtworkBitmap: Bitmap? = null
    @Volatile internal var artworkRequestUrl: String? = null
    internal var artworkLoadJob: Job? = null
    internal val artworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        session = MediaSessionCompat(this, "CrossAudioSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    engine.play()
                }

                override fun onPause() {
                    engine.pause()
                }

                override fun onStop() {
                    engine.stop()
                    stopSelf()
                }

                override fun onSkipToNext() {
                    ensureForegroundStartingImpl()
                    engine.skipNext()
                }

                override fun onSkipToPrevious() {
                    ensureForegroundStartingImpl()
                    engine.skipPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    ensureForegroundStartingImpl()
                    engine.seekTo(pos)
                }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }

        scope.launch {
            engine.state.collect { st ->
                lastPlayerState = st
                updateAudioEffectSession(st)
                updateSessionThrottled(st)
                updateNotificationThrottled(st)
            }
        }

        scope.launch {
            // When the AudioTrack is created, the engine discovers the real session id asynchronously.
            // Re-run attachment logic so external EQ apps can bind to the correct session.
            core.audioSessionIdState.collect {
                updateAudioEffectSession(lastPlayerState)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = Binder(this)
    fun setQueue(items: List<MediaItem>, startIndex: Int = 0) = engine.setQueue(items, startIndex)
    fun addToQueue(items: List<MediaItem>, atIndex: Int? = null) = (engine as? QueueMutableEngine)?.addToQueue(items, atIndex)
    fun removeFromQueue(indices: IntArray) = (engine as? QueueMutableEngine)?.removeFromQueue(indices)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = (engine as? QueueMutableEngine)?.moveQueueItem(fromIndex, toIndex)
    fun clearQueue() = (engine as? QueueMutableEngine)?.clearQueue()
    fun queuePlaybackOrder(): IntArray = core.queueState.snapshot().playOrder
    fun currentStreamInfo(): StreamInfo = core.currentStreamInfo()
    fun setShuffleEnabled(enabled: Boolean) = (engine as? ShuffleEngine)?.setShuffleEnabled(enabled)
    fun isShuffleEnabled(): Boolean = (engine as? ShuffleEngine)?.isShuffleEnabled() == true
    fun pinForOffline(item: MediaItem) = (engine as? CacheEngine)?.pinForOffline(item)
    fun unpinOffline(item: MediaItem) = (engine as? CacheEngine)?.unpinOffline(item)
    fun pinCacheGroup(cacheGroupKey: String) = (engine as? CacheEngine)?.pinCacheGroup(cacheGroupKey)
    fun unpinCacheGroup(cacheGroupKey: String) = (engine as? CacheEngine)?.unpinCacheGroup(cacheGroupKey)
    fun evictCacheGroup(cacheGroupKey: String) = (engine as? CacheEngine)?.evictCacheGroup(cacheGroupKey)
    fun clearUnpinnedCache() = (engine as? CacheEngine)?.clearUnpinnedCache()
    fun setStreamingConfig(config: StreamingConfig) = (engine as? AdaptiveStreamingEngine)?.setStreamingConfig(config)
    fun setQualityCap(cap: QualityCap) = (engine as? AdaptiveStreamingEngine)?.setQualityCap(cap)
    fun preloadManifest(item: MediaItem) = (engine as? AdaptiveStreamingEngine)?.preloadManifest(item)
    fun setDrmConfig(config: DrmGlobalConfig) = (engine as? DrmEngine)?.setDrmConfig(config)
    fun acquireOfflineLicense(item: MediaItem): OfflineLicenseResult = (engine as? DrmEngine)?.acquireOfflineLicense(item) ?: OfflineLicenseResult.Failure("DRM engine unavailable")
    fun releaseOfflineLicense(licenseId: String) = (engine as? DrmEngine)?.releaseOfflineLicense(licenseId)
    fun cacheInfo(item: MediaItem): CacheInfo = (engine as? CacheEngine)?.cacheInfo(item) ?: CacheInfo(item.cacheKey ?: item.uri.toString(), CacheState.MISS, 0L, false)

    fun player(): PlatformPlayerEngine = engine

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = onStartCommandImpl(intent)

    override fun onDestroy() {
        runCatching { artworkLoadJob?.cancel() }
        runCatching { artworkCache.evictAll() }
        runCatching { closeAudioEffectSession() }
        runCatching { stopForeground(true) }
        runCatching { session.release() }
        engine.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateAudioEffectSession(st: PlayerState) = updateAudioEffectSessionImpl(st)
    private fun openAudioEffectSession(sessionId: Int) = openAudioEffectSessionImpl(sessionId)
    private fun closeAudioEffectSession() = closeAudioEffectSessionImpl()
    private fun updateSessionThrottled(st: PlayerState) = updateSessionThrottledImpl(st)
    private fun updateNotificationThrottled(st: PlayerState) = updateNotificationThrottledImpl(st)
    private fun ensureForegroundStarting() = ensureForegroundStartingImpl()
    private fun buildNotification(st: PlayerState): Notification = buildNotificationImpl(st)
    private fun servicePI(action: String): PendingIntent = servicePIImpl(action)
    private fun createNotificationChannel() = createNotificationChannelImpl()
    private fun startForegroundCompat(id: Int, n: Notification) = startForegroundCompatImpl(id, n)

    companion object {
        const val CHANNEL_ID = "cross_audio_playback"
        const val NOTIF_ID = 1001

        const val EXTRA_URIS = "com.crossaudio.engine.extra.URIS"
        const val EXTRA_HEADERS_JSON = "com.crossaudio.engine.extra.HEADERS_JSON"
        const val EXTRA_CACHE_KEYS = "com.crossaudio.engine.extra.CACHE_KEYS"
        const val EXTRA_ITEMS_JSON = "com.crossaudio.engine.extra.ITEMS_JSON"
        const val EXTRA_REPLACE_QUEUE = "com.crossaudio.engine.extra.REPLACE_QUEUE"
        const val EXTRA_CROSSFADE_MS = "com.crossaudio.engine.extra.CROSSFADE_MS"
        const val EXTRA_STREAMING_CONFIG_JSON = "com.crossaudio.engine.extra.STREAMING_CONFIG_JSON"
        const val EXTRA_DRM_CONFIG_JSON = "com.crossaudio.engine.extra.DRM_CONFIG_JSON"
        const val EXTRA_QUALITY_CAP = "com.crossaudio.engine.extra.QUALITY_CAP"
        const val EXTRA_LICENSE_ID = "com.crossaudio.engine.extra.LICENSE_ID"
        const val EXTRA_CACHE_GROUP_KEY = "com.crossaudio.engine.extra.CACHE_GROUP_KEY"

        const val ACTION_PLAY = "com.crossaudio.engine.action.PLAY"
        const val ACTION_PAUSE = "com.crossaudio.engine.action.PAUSE"
        const val ACTION_TOGGLE = "com.crossaudio.engine.action.TOGGLE"
        const val ACTION_NEXT = "com.crossaudio.engine.action.NEXT"
        const val ACTION_PREV = "com.crossaudio.engine.action.PREV"
        const val ACTION_STOP = "com.crossaudio.engine.action.STOP"
        const val ACTION_SET_STREAMING_CONFIG = "com.crossaudio.engine.action.SET_STREAMING_CONFIG"
        const val ACTION_SET_DRM_CONFIG = "com.crossaudio.engine.action.SET_DRM_CONFIG"
        const val ACTION_SET_QUALITY_CAP = "com.crossaudio.engine.action.SET_QUALITY_CAP"
        const val ACTION_PRELOAD_MANIFEST = "com.crossaudio.engine.action.PRELOAD_MANIFEST"
        const val ACTION_ACQUIRE_OFFLINE_LICENSE = "com.crossaudio.engine.action.ACQUIRE_OFFLINE_LICENSE"
        const val ACTION_RELEASE_OFFLINE_LICENSE = "com.crossaudio.engine.action.RELEASE_OFFLINE_LICENSE"
        const val ACTION_PIN_GROUP = "com.crossaudio.engine.action.PIN_GROUP"
        const val ACTION_UNPIN_GROUP = "com.crossaudio.engine.action.UNPIN_GROUP"
        const val ACTION_EVICT_GROUP = "com.crossaudio.engine.action.EVICT_GROUP"

        fun start(
            context: Context,
            action: String,
            uris: List<String> = emptyList(),
            headersJson: List<String> = emptyList(),
            cacheKeys: List<String> = emptyList(),
            itemsJson: String? = null,
            replaceQueue: Boolean = false,
            crossfadeMs: Long? = null,
            streamingConfigJson: String? = null,
            drmConfigJson: String? = null,
            qualityCap: String? = null,
            licenseId: String? = null,
            cacheGroupKey: String? = null,
        ) {
            val i = Intent(context, CrossAudioPlaybackService::class.java).setAction(action)
            if (replaceQueue) i.putExtra(EXTRA_REPLACE_QUEUE, true)
            if (uris.isNotEmpty()) i.putExtra(EXTRA_URIS, uris.toTypedArray())
            if (headersJson.isNotEmpty()) i.putExtra(EXTRA_HEADERS_JSON, headersJson.toTypedArray())
            if (cacheKeys.isNotEmpty()) i.putExtra(EXTRA_CACHE_KEYS, cacheKeys.toTypedArray())
            if (crossfadeMs != null) i.putExtra(EXTRA_CROSSFADE_MS, crossfadeMs)
            if (!itemsJson.isNullOrBlank()) i.putExtra(EXTRA_ITEMS_JSON, itemsJson)
            if (!streamingConfigJson.isNullOrBlank()) i.putExtra(EXTRA_STREAMING_CONFIG_JSON, streamingConfigJson)
            if (!drmConfigJson.isNullOrBlank()) i.putExtra(EXTRA_DRM_CONFIG_JSON, drmConfigJson)
            if (!qualityCap.isNullOrBlank()) i.putExtra(EXTRA_QUALITY_CAP, qualityCap)
            if (!licenseId.isNullOrBlank()) i.putExtra(EXTRA_LICENSE_ID, licenseId)
            if (!cacheGroupKey.isNullOrBlank()) i.putExtra(EXTRA_CACHE_GROUP_KEY, cacheGroupKey)
            val needsFg = action == ACTION_PLAY || action == ACTION_TOGGLE || action == ACTION_NEXT || action == ACTION_PREV
            if (Build.VERSION.SDK_INT >= 26 && needsFg) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun bindIntent(context: Context): Intent =
            Intent(context, CrossAudioPlaybackService::class.java)
    }

    private fun parseItems(intent: Intent): List<MediaItem> = parseItemsImpl(intent)
}
