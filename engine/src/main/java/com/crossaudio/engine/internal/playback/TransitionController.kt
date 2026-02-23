package com.crossaudio.engine.internal.playback

internal object TransitionController {
    private const val CROSSFADE_PRELOAD_LEAD_MS = 5_000L

    fun preloadWindowMs(crossfadeMs: Long): Long {
        if (crossfadeMs <= 0L) return 15_000L
        // Start preloading a few seconds before the crossfade window so B is ready when fade starts.
        return (crossfadeMs + CROSSFADE_PRELOAD_LEAD_MS).coerceIn(4_000L, 20_000L)
    }

    fun shouldPreload(currentPosMs: Long, trackDurationUs: Long, crossfadeMs: Long): Boolean {
        if (trackDurationUs <= 0L) return false
        val durationMs = trackDurationUs / 1000L
        val preloadMs = preloadWindowMs(crossfadeMs)
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
