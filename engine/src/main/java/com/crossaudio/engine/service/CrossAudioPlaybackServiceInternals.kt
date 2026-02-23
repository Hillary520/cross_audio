package com.crossaudio.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val ARTWORK_FETCH_TIMEOUT_MS = 6_000
private const val ARTWORK_DECODE_TARGET_PX = 768

internal fun CrossAudioPlaybackService.updateSessionThrottledImpl(st: PlayerState) {
    val now = android.os.SystemClock.uptimeMillis()
    val kind = st::class.java.simpleName
    val item = activeItemForState(st)
    val itemKey = item?.uri?.toString()
    val force = kind != lastNotifKind || itemKey != lastNotifItemKey
    if (!force && (now - lastSessionUpdateMs) < 1000L) return
    lastSessionUpdateMs = now

    val (state, pos, speed) = when (st) {
        is PlayerState.Playing -> {
            sessionPositionMs = st.positionMs
            Triple(PlaybackStateCompat.STATE_PLAYING, st.positionMs, 1.0f)
        }
        is PlayerState.Paused -> {
            sessionPositionMs = st.positionMs
            Triple(PlaybackStateCompat.STATE_PAUSED, st.positionMs, 0.0f)
        }
        is PlayerState.Buffering -> Triple(PlaybackStateCompat.STATE_BUFFERING, sessionPositionMs, 0.0f)
        is PlayerState.Ended -> Triple(PlaybackStateCompat.STATE_STOPPED, 0L, 0.0f)
        is PlayerState.Error -> Triple(PlaybackStateCompat.STATE_ERROR, 0L, 0.0f)
        PlayerState.Idle -> Triple(PlaybackStateCompat.STATE_NONE, 0L, 0.0f)
    }

    val actions =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO

    val pb = PlaybackStateCompat.Builder()
        .setActions(actions)
        .setState(state, pos, speed)
        .build()
    session.setPlaybackState(pb)
    session.setMetadata(buildSessionMetadata(item))
    requestArtworkIfNeeded(item)
}

internal fun CrossAudioPlaybackService.updateNotificationThrottledImpl(st: PlayerState) {
    val shouldShow = st is PlayerState.Playing || st is PlayerState.Paused || st is PlayerState.Buffering
    if (!shouldShow) {
        if (isForeground) {
            stopForeground(true)
            isForeground = false
        }
        stopSelf()
        return
    }

    val now = android.os.SystemClock.uptimeMillis()
    val kind = st::class.java.simpleName
    val itemKey = activeItemForState(st)?.uri?.toString()
    val isPlaying = st is PlayerState.Playing
    val force = kind != lastNotifKind || itemKey != lastNotifItemKey
    if (!force) {
        val minInterval = if (isPlaying) 1000L else 2_000L
        if ((now - lastNotifUpdateMs) < minInterval) return
    }

    val n = buildNotificationImpl(st)
    if (st is PlayerState.Playing && !isForeground) {
        startForegroundCompatImpl(CrossAudioPlaybackService.NOTIF_ID, n)
        isForeground = true
    } else {
        notificationManager.notify(CrossAudioPlaybackService.NOTIF_ID, n)
        if (st is PlayerState.Paused && isForeground) {
            stopForeground(false)
            isForeground = false
        }
    }

    lastNotifKind = kind
    lastNotifItemKey = itemKey
    lastNotifUpdateMs = now

    if (st is PlayerState.Playing) {
        val u = core.rendererUnderrunCount()
        if (u > lastUnderruns) {
            val logNow = android.os.SystemClock.uptimeMillis()
            if ((logNow - lastUnderrunLogMs) > 5_000L) {
                android.util.Log.w("CrossAudioService", "AudioTrack underruns increased: $lastUnderruns -> $u")
                lastUnderrunLogMs = logNow
            }
            lastUnderruns = u
        }
    }
}

internal fun CrossAudioPlaybackService.ensureForegroundStartingImpl() {
    if (isForeground) return
    val n = NotificationCompat.Builder(this, CrossAudioPlaybackService.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("cross_audio")
        .setContentText("Starting playback…")
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    startForegroundCompatImpl(CrossAudioPlaybackService.NOTIF_ID, n)
    isForeground = true
}

internal fun CrossAudioPlaybackService.buildNotificationImpl(st: PlayerState): Notification {
    val isPlaying = st is PlayerState.Playing

    val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    val prev = NotificationCompat.Action(
        android.R.drawable.ic_media_previous,
        "Prev",
        servicePIImpl(CrossAudioPlaybackService.ACTION_PREV),
    )
    val playPause = NotificationCompat.Action(
        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        if (isPlaying) "Pause" else "Play",
        servicePIImpl(if (isPlaying) CrossAudioPlaybackService.ACTION_PAUSE else CrossAudioPlaybackService.ACTION_PLAY),
    )
    val next = NotificationCompat.Action(
        android.R.drawable.ic_media_next,
        "Next",
        servicePIImpl(CrossAudioPlaybackService.ACTION_NEXT),
    )
    val stop = NotificationCompat.Action(
        android.R.drawable.ic_menu_close_clear_cancel,
        "Stop",
        servicePIImpl(CrossAudioPlaybackService.ACTION_STOP),
    )

    val item = activeItemForState(st)

    val style = MediaStyle()
        .setMediaSession(session.sessionToken)
        .setShowActionsInCompactView(0, 1, 2)

    return NotificationCompat.Builder(this, CrossAudioPlaybackService.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(item?.title ?: "cross_audio")
        .setContentText(item?.artist ?: "Playback")
        .setContentIntent(contentIntent)
        .setDeleteIntent(servicePIImpl(CrossAudioPlaybackService.ACTION_STOP))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setLargeIcon(currentArtworkBitmap)
        .setOnlyAlertOnce(true)
        .setOngoing(isPlaying)
        .setStyle(style)
        .addAction(prev)
        .addAction(playPause)
        .addAction(next)
        .addAction(stop)
        .build()
}

internal fun CrossAudioPlaybackService.servicePIImpl(action: String): PendingIntent {
    val i = Intent(this, CrossAudioPlaybackService::class.java).setAction(action)
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    val needsFg =
        action == CrossAudioPlaybackService.ACTION_PLAY ||
            action == CrossAudioPlaybackService.ACTION_TOGGLE ||
            action == CrossAudioPlaybackService.ACTION_NEXT ||
            action == CrossAudioPlaybackService.ACTION_PREV
    return if (Build.VERSION.SDK_INT >= 26 && needsFg) {
        PendingIntent.getForegroundService(this, action.hashCode(), i, flags)
    } else {
        PendingIntent.getService(this, action.hashCode(), i, flags)
    }
}

internal fun CrossAudioPlaybackService.createNotificationChannelImpl() {
    if (Build.VERSION.SDK_INT < 26) return
    val ch = NotificationChannel(
        CrossAudioPlaybackService.CHANNEL_ID,
        "Playback",
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = "Media playback controls"
    }
    notificationManager.createNotificationChannel(ch)
}

internal fun CrossAudioPlaybackService.startForegroundCompatImpl(id: Int, n: Notification) {
    if (Build.VERSION.SDK_INT >= 29) {
        ServiceCompat.startForeground(
            this,
            id,
            n,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    } else {
        startForeground(id, n)
    }
}

private fun CrossAudioPlaybackService.activeItemForState(st: PlayerState): MediaItem? {
    return when (st) {
        is PlayerState.Playing -> st.item.also { sessionItem = it }
        is PlayerState.Paused -> st.item.also { sessionItem = it }
        is PlayerState.Ended -> st.item.also { sessionItem = it }
        is PlayerState.Buffering -> sessionItem
        is PlayerState.Error -> {
            sessionItem = null
            null
        }
        PlayerState.Idle -> {
            sessionItem = null
            null
        }
    }
}

private fun CrossAudioPlaybackService.buildSessionMetadata(item: MediaItem?): MediaMetadataCompat {
    val builder = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item?.title ?: "cross_audio")
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, item?.title ?: "cross_audio")
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item?.artist ?: "")
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, item?.artist ?: "")

    val duration = item?.durationMs?.takeIf { it > 0L }
    if (duration != null) {
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
    }

    val artworkUrl = item?.artworkUri?.takeIf { it.isNotBlank() }
    if (artworkUrl != null) {
        builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
        builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUrl)
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUrl)
    }

    val artwork = currentArtworkBitmap
    if (artwork != null && artworkUrl == currentArtworkUrl) {
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
    }

    return builder.build()
}

private fun CrossAudioPlaybackService.requestArtworkIfNeeded(item: MediaItem?) {
    val artworkUrl = item?.artworkUri?.trim().orEmpty()
    if (artworkUrl.isBlank()) {
        artworkLoadJob?.cancel()
        artworkLoadJob = null
        artworkRequestUrl = null
        currentArtworkUrl = null
        currentArtworkBitmap = null
        return
    }

    val cached = artworkCache.get(artworkUrl)
    if (cached != null) {
        currentArtworkUrl = artworkUrl
        currentArtworkBitmap = cached
        session.setMetadata(buildSessionMetadata(item))
        return
    }

    if (artworkRequestUrl == artworkUrl) return
    artworkLoadJob?.cancel()
    artworkRequestUrl = artworkUrl
    currentArtworkUrl = artworkUrl
    currentArtworkBitmap = null
    artworkLoadJob = scope.launch(Dispatchers.IO) {
        val bitmap = fetchArtworkBitmap(artworkUrl)
        withContext(Dispatchers.Main.immediate) {
            if (artworkRequestUrl != artworkUrl) return@withContext
            artworkRequestUrl = null
            if (bitmap == null) return@withContext

            artworkCache.put(artworkUrl, bitmap)
            currentArtworkUrl = artworkUrl
            currentArtworkBitmap = bitmap

            val currentItem = activeItemForState(lastPlayerState)
            if (currentItem?.artworkUri == artworkUrl) {
                session.setMetadata(buildSessionMetadata(currentItem))
                val shouldShow =
                    lastPlayerState is PlayerState.Playing ||
                        lastPlayerState is PlayerState.Paused ||
                        lastPlayerState is PlayerState.Buffering
                if (shouldShow) {
                    val refreshed = buildNotificationImpl(lastPlayerState)
                    if (lastPlayerState is PlayerState.Playing && !isForeground) {
                        startForegroundCompatImpl(CrossAudioPlaybackService.NOTIF_ID, refreshed)
                        isForeground = true
                    } else {
                        notificationManager.notify(CrossAudioPlaybackService.NOTIF_ID, refreshed)
                    }
                }
            }
        }
    }
}

private fun CrossAudioPlaybackService.fetchArtworkBitmap(url: String): Bitmap? {
    var connection: HttpURLConnection? = null
    return try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = ARTWORK_FETCH_TIMEOUT_MS
        connection.readTimeout = ARTWORK_FETCH_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.doInput = true
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) return null

        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampled = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, ARTWORK_DECODE_TARGET_PX, ARTWORK_DECODE_TARGET_PX)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled)
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { connection?.disconnect() }
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height <= 0 || width <= 0) return inSampleSize

    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
