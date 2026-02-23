package com.crossaudio.engine.service

import android.content.Intent
import androidx.media.session.MediaButtonReceiver
import com.crossaudio.engine.PlayerState

internal fun CrossAudioPlaybackService.onStartCommandImpl(intent: Intent?): Int {
    if (intent != null) {
        MediaButtonReceiver.handleIntent(session, intent)

        val replaceQueue = intent.getBooleanExtra(CrossAudioPlaybackService.EXTRA_REPLACE_QUEUE, false)
        val parsedItems = parseItemsImpl(intent)
        if (replaceQueue && parsedItems.isNotEmpty()) {
            setQueue(parsedItems)
        }
        if (intent.hasExtra(CrossAudioPlaybackService.EXTRA_CROSSFADE_MS)) {
            engine.setCrossfadeDurationMs(intent.getLongExtra(CrossAudioPlaybackService.EXTRA_CROSSFADE_MS, 0L))
        }

        when (intent.action) {
            CrossAudioPlaybackService.ACTION_PLAY -> {
                ensureForegroundStartingImpl()
                engine.play()
            }
            CrossAudioPlaybackService.ACTION_PAUSE -> engine.pause()
            CrossAudioPlaybackService.ACTION_TOGGLE -> {
                when (engine.state.value) {
                    is PlayerState.Playing -> engine.pause()
                    else -> {
                        ensureForegroundStartingImpl()
                        engine.play()
                    }
                }
            }
            CrossAudioPlaybackService.ACTION_NEXT -> {
                ensureForegroundStartingImpl()
                engine.skipNext()
            }
            CrossAudioPlaybackService.ACTION_PREV -> {
                ensureForegroundStartingImpl()
                engine.skipPrevious()
            }
            CrossAudioPlaybackService.ACTION_STOP -> {
                engine.stop()
                stopSelf()
            }
            CrossAudioPlaybackService.ACTION_SET_STREAMING_CONFIG -> {
                parseStreamingConfigImpl(intent)?.let { setStreamingConfig(it) }
            }
            CrossAudioPlaybackService.ACTION_SET_DRM_CONFIG -> {
                parseDrmConfigImpl(intent)?.let { setDrmConfig(it) }
            }
            CrossAudioPlaybackService.ACTION_SET_QUALITY_CAP -> {
                parseQualityCapImpl(intent)?.let { setQualityCap(it) }
            }
            CrossAudioPlaybackService.ACTION_PRELOAD_MANIFEST -> {
                parseItemsImpl(intent).firstOrNull()?.let { preloadManifest(it) }
            }
            CrossAudioPlaybackService.ACTION_ACQUIRE_OFFLINE_LICENSE -> {
                parseItemsImpl(intent).firstOrNull()?.let { acquireOfflineLicense(it) }
            }
            CrossAudioPlaybackService.ACTION_RELEASE_OFFLINE_LICENSE -> {
                intent.getStringExtra(CrossAudioPlaybackService.EXTRA_LICENSE_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { releaseOfflineLicense(it) }
            }
            CrossAudioPlaybackService.ACTION_PIN_GROUP -> {
                intent.getStringExtra(CrossAudioPlaybackService.EXTRA_CACHE_GROUP_KEY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { pinCacheGroup(it) }
            }
            CrossAudioPlaybackService.ACTION_UNPIN_GROUP -> {
                intent.getStringExtra(CrossAudioPlaybackService.EXTRA_CACHE_GROUP_KEY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { unpinCacheGroup(it) }
            }
            CrossAudioPlaybackService.ACTION_EVICT_GROUP -> {
                intent.getStringExtra(CrossAudioPlaybackService.EXTRA_CACHE_GROUP_KEY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { evictCacheGroup(it) }
            }
        }
    }
    return android.app.Service.START_STICKY
}
