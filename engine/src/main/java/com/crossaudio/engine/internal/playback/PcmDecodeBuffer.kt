package com.crossaudio.engine.internal.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PcmDecodeBuffer {
    fun readBlockToStereoScratch(
        outBuf: ByteBuffer,
        srcChannelCount: Int,
        stereoScratch: ShortArray,
        monoScratch: ShortArray,
    ): Int {
        outBuf.order(ByteOrder.LITTLE_ENDIAN)
        val shortsToRead = outBuf.remaining() / 2
        if (srcChannelCount == 2) {
            val n = minOf(shortsToRead, stereoScratch.size)
            outBuf.asShortBuffer().get(stereoScratch, 0, n)
            outBuf.position(outBuf.position() + n * 2)
            return n
        }

        val nMono = minOf(shortsToRead, monoScratch.size)
        outBuf.asShortBuffer().get(monoScratch, 0, nMono)
        outBuf.position(outBuf.position() + nMono * 2)
        var si = 0
        var di = 0
        while (si < nMono && (di + 1) < stereoScratch.size) {
            val v = monoScratch[si++]
            stereoScratch[di++] = v
            stereoScratch[di++] = v
        }
        return nMono * 2
    }
}
