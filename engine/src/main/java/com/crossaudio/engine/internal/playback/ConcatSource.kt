package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16

/**
 * Gapless chaining of two PCM pipes. Reads from [a] until EOS+empty, then reads from [b].
 */
internal class ConcatSource(
    override val format: PcmFormat,
    private val a: PcmPipe16,
    private val b: PcmPipe16,
    private val onSwitchedToSecond: () -> Unit,
) : RenderSource {
    private var usingA = true
    private var switchNotified = false

    override fun readFramesBlocking(out: ShortArray, frames: Int): Int {
        val ch = format.channelCount
        val samplesWanted = frames * ch

        while (true) {
            if (usingA) {
                val nSamples = a.readBlocking(out, 0, samplesWanted)
                val nFrames = nSamples / ch
                if (nFrames > 0) return nFrames

                // A is EOS+empty; switch to B.
                usingA = false
                if (!switchNotified) {
                    switchNotified = true
                    onSwitchedToSecond()
                }
                continue
            } else {
                val nSamples = b.readBlocking(out, 0, samplesWanted)
                return nSamples / ch
            }
        }
    }
}

