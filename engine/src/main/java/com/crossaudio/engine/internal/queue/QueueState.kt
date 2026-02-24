package com.crossaudio.engine.internal.queue

import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.RepeatMode
import kotlin.random.Random

internal class QueueState {
    private val lock = Any()

    private val items = mutableListOf<MediaItem>()
    private var currentIndex = -1
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private var shuffleEnabled: Boolean = false
    private var playOrder: IntArray = intArrayOf()
    private var orderCursor: Int = 0

    fun snapshot(): QueueSnapshot = synchronized(lock) {
        QueueSnapshot(
            items = items.toList(),
            currentIndex = currentIndex,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            playOrder = playOrder.copyOf(),
            orderCursor = orderCursor,
        )
    }

    fun setQueue(newItems: List<MediaItem>, startIndex: Int) = synchronized(lock) {
        items.clear()
        items.addAll(newItems)
        currentIndex = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
        rebuildOrder()
    }

    fun setRepeatMode(mode: RepeatMode) = synchronized(lock) {
        repeatMode = mode
    }

    fun setShuffleEnabled(enabled: Boolean) = synchronized(lock) {
        if (shuffleEnabled == enabled) return@synchronized
        shuffleEnabled = enabled
        rebuildOrder()
    }

    fun addToQueue(additions: List<MediaItem>, atIndex: Int?) = synchronized(lock) {
        if (additions.isEmpty()) return@synchronized
        val insertAt = (atIndex ?: items.size).coerceIn(0, items.size)
        val previousSize = items.size
        items.addAll(insertAt, additions)
        if (currentIndex < 0) {
            currentIndex = 0
        } else {
            currentIndex = QueueMutator.adjustCurrentForInsert(currentIndex, insertAt, additions.size)
        }
        if (!shuffleEnabled || !isValidPlayOrder(playOrder, previousSize)) {
            rebuildOrder()
            return@synchronized
        }

        // Keep existing shuffled order stable; only append newly inserted indices.
        val shiftedExisting = IntArray(playOrder.size) { orderIdx ->
            val idx = playOrder[orderIdx]
            if (idx >= insertAt) idx + additions.size else idx
        }
        val newIndices = IntArray(additions.size) { insertAt + it }.toMutableList()
        newIndices.shuffle(Random(System.nanoTime()))
        playOrder = shiftedExisting + newIndices.toIntArray()
        orderCursor = playOrder.indexOf(currentIndex).coerceAtLeast(0)
    }

    fun removeFromQueue(indices: IntArray): QueueRemovalResult = synchronized(lock) {
        if (items.isEmpty() || indices.isEmpty()) return@synchronized QueueRemovalResult(false, false)
        val normalized = indices.distinct().filter { it in items.indices }.sorted().toIntArray()
        if (normalized.isEmpty()) return@synchronized QueueRemovalResult(false, false)
        val oldCurrent = currentIndex
        for (i in normalized.indices.reversed()) {
            items.removeAt(normalized[i])
        }

        val adjust = QueueMutator.adjustCurrentForRemoval(oldCurrent, normalized, items.size)
        currentIndex = if (items.isEmpty()) -1 else adjust.newCurrentIndex
        rebuildOrder()
        QueueRemovalResult(true, adjust.removedCurrent)
    }

    fun moveQueueItem(from: Int, to: Int): Boolean = synchronized(lock) {
        if (from !in items.indices || to !in items.indices) return@synchronized false
        if (from == to) return@synchronized true
        val item = items.removeAt(from)
        items.add(to, item)
        currentIndex = QueueMutator.adjustCurrentForMove(currentIndex, from, to)
        rebuildOrder()
        true
    }

    fun clearQueue() = synchronized(lock) {
        items.clear()
        currentIndex = -1
        rebuildOrder()
    }

    fun setCurrentIndex(index: Int): Boolean = synchronized(lock) {
        if (index !in items.indices) return@synchronized false
        currentIndex = index
        if (!isValidPlayOrder(playOrder, items.size)) {
            rebuildOrder()
        } else {
            val cursor = playOrder.indexOf(index)
            if (cursor >= 0) {
                orderCursor = cursor
            } else {
                rebuildOrder()
            }
        }
        true
    }

    fun currentItem(): MediaItem? = synchronized(lock) {
        items.getOrNull(currentIndex)
    }

    fun nextIndexForAuto(): Int? = synchronized(lock) {
        nextIndex(auto = true)
    }

    fun peekNextIndexForAuto(): Int? = synchronized(lock) {
        peekNextIndex(auto = true)
    }

    fun nextIndexForSkip(): Int? = synchronized(lock) {
        nextIndex(auto = false)
    }

    fun peekNextIndexForSkip(): Int? = synchronized(lock) {
        peekNextIndex(auto = false)
    }

    fun prevIndexForSkip(): Int? = synchronized(lock) {
        previousIndex(move = true)
    }

    fun peekPrevIndexForSkip(): Int? = synchronized(lock) {
        previousIndex(move = false)
    }

    private fun previousIndex(move: Boolean): Int? {
        if (items.isEmpty() || currentIndex < 0) return null
        if (!shuffleEnabled) {
            return if (currentIndex > 0) currentIndex - 1 else if (repeatMode == RepeatMode.ALL) items.lastIndex else null
        }
        if (playOrder.isEmpty()) return null
        val prevCursor = orderCursor - 1
        return if (prevCursor >= 0) {
            if (move) {
                orderCursor = prevCursor
                playOrder[orderCursor].also { currentIndex = it }
            } else {
                playOrder[prevCursor]
            }
        } else if (repeatMode == RepeatMode.ALL) {
            if (move) {
                orderCursor = playOrder.lastIndex
                playOrder[orderCursor].also { currentIndex = it }
            } else {
                playOrder.last()
            }
        } else {
            null
        }
    }

    private fun nextIndex(auto: Boolean): Int? {
        if (items.isEmpty() || currentIndex < 0) return null
        if (repeatMode == RepeatMode.ONE && auto) return currentIndex
        if (!shuffleEnabled) {
            return if (currentIndex < items.lastIndex) {
                (currentIndex + 1).also { currentIndex = it }
            } else if (repeatMode == RepeatMode.ALL) {
                0.also { currentIndex = it }
            } else {
                null
            }
        }
        if (playOrder.isEmpty()) return null
        val nextCursor = orderCursor + 1
        return if (nextCursor <= playOrder.lastIndex) {
            orderCursor = nextCursor
            playOrder[orderCursor].also { currentIndex = it }
        } else if (repeatMode == RepeatMode.ALL) {
            orderCursor = 0
            playOrder[orderCursor].also { currentIndex = it }
        } else {
            null
        }
    }

    private fun peekNextIndex(auto: Boolean): Int? {
        if (items.isEmpty() || currentIndex < 0) return null
        if (repeatMode == RepeatMode.ONE && auto) return currentIndex
        if (!shuffleEnabled) {
            return if (currentIndex < items.lastIndex) {
                currentIndex + 1
            } else if (repeatMode == RepeatMode.ALL) {
                0
            } else {
                null
            }
        }
        if (playOrder.isEmpty()) return null
        val nextCursor = orderCursor + 1
        return if (nextCursor <= playOrder.lastIndex) {
            playOrder[nextCursor]
        } else if (repeatMode == RepeatMode.ALL) {
            playOrder.first()
        } else {
            null
        }
    }

    private fun rebuildOrder() {
        playOrder = if (items.isEmpty()) {
            intArrayOf()
        } else if (shuffleEnabled) {
            ShuffleOrder.build(items.size, currentIndex.coerceAtLeast(0))
        } else {
            ShuffleOrder.natural(items.size)
        }
        orderCursor = playOrder.indexOf(currentIndex).coerceAtLeast(0)
    }

    private fun isValidPlayOrder(order: IntArray, expectedSize: Int): Boolean {
        if (order.size != expectedSize) return false
        val seen = BooleanArray(expectedSize)
        order.forEach { index ->
            if (index !in 0 until expectedSize) return false
            if (seen[index]) return false
            seen[index] = true
        }
        return true
    }
}

internal data class QueueSnapshot(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val playOrder: IntArray,
    val orderCursor: Int,
)

internal data class QueueRemovalResult(
    val removedAny: Boolean,
    val removedCurrent: Boolean,
)
