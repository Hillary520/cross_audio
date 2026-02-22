package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat

internal interface RenderSource {
    val format: PcmFormat

    /**
     * Fill [out] with up to [frames] frames (interleaved).
     * Returns number of frames written. Returning 0 means EOS.
     */
    fun readFramesBlocking(out: ShortArray, frames: Int): Int
}

