package com.crossaudio.engine.internal.queue

internal object QueueMutator {
    fun adjustCurrentForInsert(currentIndex: Int, insertAt: Int, addedCount: Int): Int {
        return if (insertAt <= currentIndex) currentIndex + addedCount else currentIndex
    }

    fun adjustCurrentForRemoval(currentIndex: Int, removed: IntArray, newSize: Int): RemovalAdjust {
        if (removed.isEmpty()) return RemovalAdjust(currentIndex, false)
        val removedSet = removed.toSet()
        val removedCurrent = removedSet.contains(currentIndex)
        if (newSize <= 0) return RemovalAdjust(-1, removedCurrent)

        val removedBefore = removed.count { it < currentIndex }
        val shifted = currentIndex - removedBefore
        val next = if (removedCurrent) shifted.coerceIn(0, newSize - 1) else shifted
        return RemovalAdjust(next.coerceIn(0, newSize - 1), removedCurrent)
    }

    fun adjustCurrentForMove(currentIndex: Int, from: Int, to: Int): Int {
        if (from == to) return currentIndex
        if (currentIndex == from) return to
        return when {
            from < to && currentIndex in (from + 1)..to -> currentIndex - 1
            from > to && currentIndex in to until from -> currentIndex + 1
            else -> currentIndex
        }
    }

    fun remapOrderForMove(order: IntArray, from: Int, to: Int): IntArray {
        return IntArray(order.size) { idx -> adjustCurrentForMove(order[idx], from, to) }
    }

    fun moveOrderEntry(order: IntArray, fromPosition: Int, toPosition: Int): IntArray {
        if (fromPosition !in order.indices || toPosition !in order.indices) return order
        if (fromPosition == toPosition) return order
        val mutable = order.toMutableList()
        val moved = mutable.removeAt(fromPosition)
        mutable.add(toPosition, moved)
        return mutable.toIntArray()
    }
}

internal data class RemovalAdjust(
    val newCurrentIndex: Int,
    val removedCurrent: Boolean,
)
