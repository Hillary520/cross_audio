package com.crossaudio.engine.internal.streaming.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpdParserTest {
    @Test
    fun `parses dash representations`() {
        val xml = """
            <MPD>
              <Period>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a1" bandwidth="128000">
                    <BaseURL>audio_128.m4s</BaseURL>
                  </Representation>
                  <Representation id="a2" bandwidth="256000">
                    <BaseURL>audio_256.m4s</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = MpdParser.parse(xml)

        assertEquals(2, parsed.representations.size)
        assertEquals("a1", parsed.representations[0].id)
        assertEquals(128, parsed.representations[0].bandwidthKbps)
        assertEquals("audio_128.m4s", parsed.representations[0].baseUrl)
    }

    @Test
    fun `returns empty for invalid xml`() {
        val parsed = MpdParser.parse("not-xml")
        assertTrue(parsed.representations.isEmpty())
    }

    @Test
    fun `parses pssh and segment metadata`() {
        val xml = """
            <MPD xmlns:cenc="urn:mpeg:cenc:2013">
              <Period>
                <AdaptationSet mimeType="audio/mp4">
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed">
                    <cenc:pssh>AAAAPSSH</cenc:pssh>
                  </ContentProtection>
                  <Representation id="a1" bandwidth="128000">
                    <BaseURL>audio/</BaseURL>
                    <SegmentList>
                      <Initialization sourceURL="init.mp4" />
                      <SegmentURL media="seg1.m4s" />
                      <SegmentURL media="seg2.m4s" />
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = MpdParser.parse(xml)

        assertEquals("AAAAPSSH", parsed.initDataBase64)
        assertEquals("init.mp4", parsed.representations[0].initializationUrl)
        assertEquals(listOf("seg1.m4s", "seg2.m4s"), parsed.representations[0].segmentUrls)
    }
}
