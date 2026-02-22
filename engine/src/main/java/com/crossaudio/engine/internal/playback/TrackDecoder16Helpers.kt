package com.crossaudio.engine.internal.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.crossaudio.engine.internal.drm.ActiveDrmSession
import com.crossaudio.engine.internal.drm.DrmSessionManager

internal fun selectAudioTrackFromExtractor(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) return i to format
    }
    return null
}

internal fun cleanupTrackDecoder(
    codec: MediaCodec?,
    extractor: MediaExtractor?,
    activeDrmSession: ActiveDrmSession?,
    drmSessionManager: DrmSessionManager?,
) {
    try {
        codec?.stop()
    } catch (_: Throwable) {
    }
    try {
        codec?.release()
    } catch (_: Throwable) {
    }
    try {
        extractor?.release()
    } catch (_: Throwable) {
    }
    try {
        activeDrmSession?.let { drmSessionManager?.closePlaybackSession(it) }
    } catch (_: Throwable) {
    }
}
