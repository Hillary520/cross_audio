package com.crossaudio.engine.internal.streaming.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMediaParserTest {
    @Test
    fun `parses media segments and session key pssh`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.widevine",URI="data:text/plain;base64,AAAABBBB"
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()

        val parsed = HlsMediaParser.parse(text)

        assertEquals(6, parsed.targetDurationSec)
        assertEquals(2, parsed.segments.size)
        assertEquals("seg1.ts", parsed.segments[0].uri)
        assertEquals("init.mp4", parsed.initializationSegmentUri)
        assertEquals("AAAABBBB", parsed.sessionKeyPsshBase64)
    }

    @Test
    fun `returns null pssh when session key absent`() {
        val text = """
            #EXTM3U
            #EXTINF:4.0,
            seg.ts
        """.trimIndent()
        val parsed = HlsMediaParser.parse(text)
        assertNull(parsed.sessionKeyPsshBase64)
    }

    @Test
    fun `parses media sequence and endlist`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:153
            #EXTINF:4.0,
            seg153.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsMediaParser.parse(text)

        assertEquals(153L, parsed.mediaSequence)
        assertTrue(parsed.isEndList)
    }
}
