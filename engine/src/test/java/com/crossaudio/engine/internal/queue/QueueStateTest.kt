package com.crossaudio.engine.internal.queue

import android.net.Uri
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

@Ignore("Requires android.net.Uri runtime; migrate to androidTest")
class QueueStateTest {
    @Test
    fun removeCurrentPicksNearest() {
        val q = QueueState()
        q.setQueue(items(4), startIndex = 2)

        val removed = q.removeFromQueue(intArrayOf(2))
        val s = q.snapshot()

        assertTrue(removed.removedAny)
        assertTrue(removed.removedCurrent)
        assertEquals(3, s.items.size)
        assertEquals(2, s.currentIndex)
    }

    @Test
    fun shuffleKeepsCurrentAnchor() {
        val q = QueueState()
        q.setQueue(items(6), startIndex = 3)
        q.setShuffleEnabled(true)

        val s = q.snapshot()
        assertEquals(3, s.currentIndex)
        assertEquals(3, s.playOrder.first())
    }

    @Test
    fun nextSkipFollowsRepeatAll() {
        val q = QueueState()
        q.setQueue(items(3), startIndex = 2)
        q.setRepeatMode(RepeatMode.ALL)

        val next = q.nextIndexForSkip()
        val s = q.snapshot()

        assertEquals(0, next)
        assertEquals(0, s.currentIndex)
    }

    private fun items(n: Int): List<MediaItem> {
        return (0 until n).map { _ -> MediaItem(Uri.EMPTY) }
    }
}
