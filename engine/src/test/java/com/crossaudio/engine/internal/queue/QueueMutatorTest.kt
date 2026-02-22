package com.crossaudio.engine.internal.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMutatorTest {
    @Test
    fun insertBeforeCurrentShiftsRight() {
        assertEquals(5, QueueMutator.adjustCurrentForInsert(currentIndex = 3, insertAt = 1, addedCount = 2))
    }

    @Test
    fun moveAdjustsCurrentWhenCrossed() {
        assertEquals(2, QueueMutator.adjustCurrentForMove(currentIndex = 3, from = 1, to = 4))
        assertEquals(4, QueueMutator.adjustCurrentForMove(currentIndex = 3, from = 4, to = 1))
    }

    @Test
    fun removalDetectsCurrentRemoved() {
        val r = QueueMutator.adjustCurrentForRemoval(currentIndex = 2, removed = intArrayOf(2), newSize = 4)
        assertTrue(r.removedCurrent)
        assertEquals(2, r.newCurrentIndex)
    }
}
