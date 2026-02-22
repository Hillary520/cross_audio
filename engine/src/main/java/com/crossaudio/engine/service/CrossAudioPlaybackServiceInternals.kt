package com.crossaudio.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.crossaudio.engine.PlayerState

internal fun CrossAudioPlaybackService.updateSessionThrottledImpl(st: PlayerState) {
    val now = android.os.SystemClock.uptimeMillis()
    val kind = st::class.java.simpleName
    val itemKey = when (st) {
        is PlayerState.Playing -> st.item.uri.toString()
        is PlayerState.Paused -> st.item.uri.toString()
        is PlayerState.Ended -> st.item.uri.toString()
        else -> null
    }
    val force = kind != lastNotifKind || itemKey != lastNotifItemKey
    if (!force && (now - lastSessionUpdateMs) < 1000L) return
    lastSessionUpdateMs = now

    val (state, pos) = when (st) {
        is PlayerState.Playing -> PlaybackStateCompat.STATE_PLAYING to st.positionMs
        is PlayerState.Paused -> PlaybackStateCompat.STATE_PAUSED to st.positionMs
        is PlayerState.Buffering -> PlaybackStateCompat.STATE_BUFFERING to 0L
        is PlayerState.Ended -> PlaybackStateCompat.STATE_STOPPED to 0L
        is PlayerState.Error -> PlaybackStateCompat.STATE_ERROR to 0L
        PlayerState.Idle -> PlaybackStateCompat.STATE_NONE to 0L
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
        .setState(state, pos, 1.0f)
        .build()
    session.setPlaybackState(pb)

    val item = when (st) {
        is PlayerState.Playing -> st.item
        is PlayerState.Paused -> st.item
        is PlayerState.Ended -> st.item
        else -> null
    }

    val md = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item?.title ?: "cross_audio")
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item?.artist ?: "")
        .build()
    session.setMetadata(md)
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
    val itemKey = when (st) {
        is PlayerState.Playing -> st.item.uri.toString()
        is PlayerState.Paused -> st.item.uri.toString()
        else -> null
    }
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

    val item = when (st) {
        is PlayerState.Playing -> st.item
        is PlayerState.Paused -> st.item
        else -> null
    }

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
