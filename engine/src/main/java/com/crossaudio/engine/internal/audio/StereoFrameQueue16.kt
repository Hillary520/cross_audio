package com.crossaudio.engine.internal.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-producer/single-consumer ring buffer for stereo PCM16 frames (L,R).
 * Stores interleaved samples in a ShortArray.
 */
internal class StereoFrameQueue16(
    capacityFrames: Int,
    private val stop: AtomicBoolean,
) {
    private val buf = ShortArray(capacityFrames * 2)
    private val capFrames = capacityFrames

    private val lock = Object()
    private var r = 0
    private var w = 0
    private var size = 0

    fun availableFrames(): Int = synchronized(lock) { size }

    fun writeBlocking(src: ShortArray, offsetSamples: Int, samples: Int) {
        // samples must be even (stereo).
        var off = offsetSamples
        var remaining = samples
        while (remaining > 0 && !stop.get()) {
            synchronized(lock) {
                while (size == capFrames && !stop.get()) lock.wait(50)
                if (stop.get()) return

                val framesAvail = capFrames - size
                val framesToWrite = minOf(framesAvail, remaining / 2)
                if (framesToWrite <= 0) return

                val samplesToWrite = framesToWrite * 2

                val firstFrames = minOf(framesToWrite, capFrames - w)
                val firstSamples = firstFrames * 2
                src.copyInto(buf, destinationOffset = w * 2, startIndex = off, endIndex = off + firstSamples)
                w = (w + firstFrames) % capFrames
                size += firstFrames
                off += firstSamples
                remaining -= firstSamples

                val secondFrames = framesToWrite - firstFrames
                if (secondFrames > 0) {
                    val secondSamples = secondFrames * 2
                    src.copyInto(buf, destinationOffset = w * 2, startIndex = off, endIndex = off + secondSamples)
                    w = (w + secondFrames) % capFrames
                    size += secondFrames
                    off += secondSamples
                    remaining -= secondSamples
                }

                lock.notifyAll()
            }
        }
    }

    /**
     * Pops a single stereo frame into [dst] at [dstOffSamples]. Returns false if none available.
     */
    fun popFrameBlocking(dst: ShortArray, dstOffSamples: Int): Boolean {
        synchronized(lock) {
            while (size == 0 && !stop.get()) lock.wait(50)
            if (stop.get()) return false
            if (size == 0) return false

            val base = r * 2
            dst[dstOffSamples] = buf[base]
            dst[dstOffSamples + 1] = buf[base + 1]
            r = (r + 1) % capFrames
            size -= 1
            lock.notifyAll()
            return true
        }
    }

    /**
     * Pops a single stereo frame into [dst] at [dstOffSamples] without blocking.
     */
    fun popFrameAvailable(dst: ShortArray, dstOffSamples: Int): Boolean {
        synchronized(lock) {
            if (stop.get()) return false
            if (size == 0) return false

            val base = r * 2
            dst[dstOffSamples] = buf[base]
            dst[dstOffSamples + 1] = buf[base + 1]
            r = (r + 1) % capFrames
            size -= 1
            lock.notifyAll()
            return true
        }
    }
}
