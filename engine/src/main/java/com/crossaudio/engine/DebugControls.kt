package com.crossaudio.engine

/**
 * Optional debug-only controls. Apps can `as? DebugControls` to enable extra diagnostics.
 */
interface DebugControls {
    /**
     * When enabled, during crossfade track A is hard-panned left and track B hard-panned right.
     * This makes overlap unmistakable for manual testing.
     */
    fun setCrossfadeDebugPanning(enabled: Boolean)

    /**
     * Crossfade output headroom multiplier (applied only during crossfade mixing).
     * Lower values reduce clipping risk and tend to sound "smoother" at the cost of loudness.
     */
    fun setCrossfadeHeadroom(headroom: Float)
}
