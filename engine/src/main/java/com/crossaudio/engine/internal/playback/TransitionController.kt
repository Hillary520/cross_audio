package com.crossaudio.engine.internal.playback

internal object TransitionController {
    fun preloadWindowMs(crossfadeMs: Long): Long = minOf(15_000L, maxOf(3_000L, crossfadeMs))

    fun shouldPreload(currentPosMs: Long, trackDurationUs: Long, crossfadeMs: Long): Boolean {
        if (trackDurationUs <= 0L) return false
        val durationMs = trackDurationUs / 1000L
        val preloadMs = if (crossfadeMs <= 0L) 15_000L else preloadWindowMs(crossfadeMs)
        val threshold = (durationMs - preloadMs).coerceAtLeast(0L)
        return currentPosMs >= threshold
    }

    fun crossfadeStartMs(trackDurationUs: Long, crossfadeMs: Long): Long {
        if (trackDurationUs <= 0L) return 0L
        return ((trackDurationUs / 1000L) - crossfadeMs).coerceAtLeast(0L)
    }

    fun shouldStartCrossfade(currentPosMs: Long, trackDurationUs: Long, crossfadeMs: Long): Boolean {
        if (crossfadeMs <= 0L || trackDurationUs <= 0L) return false
        return currentPosMs >= crossfadeStartMs(trackDurationUs, crossfadeMs)
    }
}
