package com.crossaudio.engine.internal.streaming.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMasterParserTest {
    @Test
    fun `parses variants from master playlist`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\"
            high.m3u8
        """.trimIndent()

        val master = HlsMasterParser.parse(text)

        assertEquals(2, master.variants.size)
        assertEquals(128, master.variants[0].bandwidthKbps)
        assertEquals("low.m3u8", master.variants[0].uri)
        assertEquals(256, master.variants[1].bandwidthKbps)
    }

    @Test
    fun `returns empty for non-master playlist`() {
        val media = """
            #EXTM3U
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()

        val parsed = HlsMasterParser.parse(media)

        assertTrue(parsed.variants.isEmpty())
    }

    @Test
    fun `parses codecs containing comma inside quoted attribute`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=192000,CODECS="avc1.640029,mp4a.40.2"
            video.m3u8
        """.trimIndent()

        val master = HlsMasterParser.parse(text)

        assertEquals(1, master.variants.size)
        assertEquals("avc1.640029,mp4a.40.2", master.variants[0].codecs)
    }
}
