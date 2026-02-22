package com.crossaudio.engine.internal.queue

import kotlin.random.Random

internal object ShuffleOrder {
    fun build(size: Int, currentIndex: Int): IntArray {
        if (size <= 0) return intArrayOf()
        if (size == 1) return intArrayOf(0)
        val validCurrent = currentIndex.coerceIn(0, size - 1)
        val rest = (0 until size).filter { it != validCurrent }.toMutableList()
        rest.shuffle(Random(System.nanoTime()))
        return intArrayOf(validCurrent, *rest.toIntArray())
    }

    fun natural(size: Int): IntArray = IntArray(size) { it }
}
