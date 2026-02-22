package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16

internal class PipeSource(
    override val format: PcmFormat,
    private val pipe: PcmPipe16,
) : RenderSource {
    override fun readFramesBlocking(out: ShortArray, frames: Int): Int {
        val samplesWanted = frames * format.channelCount
        val nSamples = pipe.readBlocking(out, 0, samplesWanted)
        return nSamples / format.channelCount
    }
}

