package com.crossaudio.engine.internal.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking single-producer/single-consumer pipe for interleaved PCM16 samples.
 *
 * Units are "samples" (not frames). For stereo, one frame == 2 samples.
 */
internal class PcmPipe16(
    capacitySamples: Int,
    private val stop: AtomicBoolean,
) {
    private val buf = ShortArray(capacitySamples)
    private val capacity = capacitySamples

    private val lock = Object()
    private var r = 0
    private var w = 0
    private var size = 0
    private var eos = false

    fun availableSamples(): Int = synchronized(lock) { size }

    fun isEos(): Boolean = synchronized(lock) { eos }

    fun markEos() {
        synchronized(lock) {
            eos = true
            lock.notifyAll()
        }
    }

    fun reset() {
        synchronized(lock) {
            r = 0
            w = 0
            size = 0
            eos = false
            lock.notifyAll()
        }
    }

    fun isEosAndEmpty(): Boolean = synchronized(lock) { eos && size == 0 }

    /**
     * Writes [count] samples from [src], blocking until all are written or [stop] is set.
     */
    fun writeBlocking(src: ShortArray, offset: Int, count: Int) {
        var off = offset
        var remaining = count
        while (remaining > 0 && !stop.get()) {
            val wrote = synchronized(lock) {
                while (size == capacity && !stop.get()) {
                    lock.wait(50)
                }
                if (stop.get()) return

                val space = capacity - size
                val n = minOf(space, remaining)
                val first = minOf(n, capacity - w)
                src.copyInto(buf, destinationOffset = w, startIndex = off, endIndex = off + first)
                w = (w + first) % capacity
                size += first
                off += first
                remaining -= first
                val second = n - first
                if (second > 0) {
                    src.copyInto(buf, destinationOffset = w, startIndex = off, endIndex = off + second)
                    w = (w + second) % capacity
                    size += second
                    off += second
                    remaining -= second
                }
                lock.notifyAll()
                n
            }

            if (wrote == 0) Thread.yield()
        }
    }

    /**
     * Reads up to [count] samples into [dst], blocking until at least 1 sample is read,
     * or EOS/stop is reached. Returns number of samples read.
     */
    fun readBlocking(dst: ShortArray, offset: Int, count: Int): Int {
        synchronized(lock) {
            while (size == 0 && !eos && !stop.get()) {
                lock.wait(50)
            }
            if (stop.get()) return 0
            if (size == 0 && eos) return 0

            val n = minOf(size, count)
            val first = minOf(n, capacity - r)
            buf.copyInto(dst, destinationOffset = offset, startIndex = r, endIndex = r + first)
            r = (r + first) % capacity
            size -= first

            val second = n - first
            if (second > 0) {
                buf.copyInto(dst, destinationOffset = offset + first, startIndex = r, endIndex = r + second)
                r = (r + second) % capacity
                size -= second
            }

            lock.notifyAll()
            return n
        }
    }

    /**
     * Reads up to [count] samples into [dst] without blocking. Returns 0 if none are available.
     * Returns 0 when EOS+empty as well; use [isEosAndEmpty] if you need to distinguish.
     */
    fun readAvailable(dst: ShortArray, offset: Int, count: Int): Int {
        synchronized(lock) {
            if (stop.get()) return 0
            if (size == 0) return 0

            val n = minOf(size, count)
            val first = minOf(n, capacity - r)
            buf.copyInto(dst, destinationOffset = offset, startIndex = r, endIndex = r + first)
            r = (r + first) % capacity
            size -= first

            val second = n - first
            if (second > 0) {
                buf.copyInto(dst, destinationOffset = offset + first, startIndex = r, endIndex = r + second)
                r = (r + second) % capacity
                size -= second
            }

            lock.notifyAll()
            return n
        }
    }
}
