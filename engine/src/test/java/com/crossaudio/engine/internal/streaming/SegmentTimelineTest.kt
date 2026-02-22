package com.crossaudio.engine.internal.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentTimelineTest {
    @Test
    fun `resolves relative uri against base`() {
        val resolved = SegmentTimeline.resolveUri(
            "https://cdn.example.com/audio/master.m3u8",
            "seg1.ts",
        )
        assertEquals("https://cdn.example.com/audio/seg1.ts", resolved)
    }
}
