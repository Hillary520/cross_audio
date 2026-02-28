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

    @Test
    fun `parses namespaced representation tags`() {
        val xml = """
            <mpd:MPD xmlns:mpd="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013">
              <mpd:Period>
                <mpd:AdaptationSet mimeType="audio/mp4">
                  <mpd:Representation id="a1" bandwidth="128000">
                    <mpd:BaseURL>audio/</mpd:BaseURL>
                    <mpd:SegmentList>
                      <mpd:Initialization sourceURL="init.mp4" />
                      <mpd:SegmentURL media="seg1.m4s" />
                    </mpd:SegmentList>
                  </mpd:Representation>
                </mpd:AdaptationSet>
              </mpd:Period>
            </mpd:MPD>
        """.trimIndent()

        val parsed = MpdParser.parse(xml)

        assertEquals(1, parsed.representations.size)
        assertEquals("a1", parsed.representations[0].id)
        assertEquals("audio/", parsed.representations[0].baseUrl)
        assertEquals("init.mp4", parsed.representations[0].initializationUrl)
        assertEquals(listOf("seg1.m4s"), parsed.representations[0].segmentUrls)
    }

    @Test
    fun `parses dynamic manifest update hints`() {
        val xml = """
            <MPD type="dynamic" minimumUpdatePeriod="PT2.5S">
              <Period>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a1" bandwidth="128000">
                    <BaseURL>audio/</BaseURL>
                    <SegmentList>
                      <SegmentURL media="seg1.m4s" />
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = MpdParser.parse(xml)

        assertTrue(parsed.isDynamic)
        assertEquals(2_500L, parsed.minimumUpdatePeriodMs)
    }

    @Test
    fun `expands segment template with Number placeholder`() {
        val xml = """
            <MPD mediaPresentationDuration="PT12S">
              <Period>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a1" bandwidth="128000">
                    <BaseURL>audio/</BaseURL>
                    <SegmentTemplate initialization="init-${'$'}RepresentationID${'$'}.mp4" media="chunk-${'$'}Number%03d${'$'}.m4s" timescale="1" duration="4" startNumber="10"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = MpdParser.parse(xml)
        val rep = parsed.representations.first()

        assertEquals("init-a1.mp4", rep.initializationUrl)
        assertEquals(listOf("chunk-010.m4s", "chunk-011.m4s", "chunk-012.m4s"), rep.segmentUrls)
    }

    @Test
    fun `expands segment template timeline with Time placeholder`() {
        val xml = """
            <MPD>
              <Period>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a1" bandwidth="128000">
                    <SegmentTemplate media="seg-${'$'}Time${'$'}.m4s" initialization="init.mp4" timescale="1" startNumber="1">
                      <SegmentTimeline>
                        <S t="0" d="2" r="2"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = MpdParser.parse(xml)
        val rep = parsed.representations.first()

        assertEquals(listOf("seg-0.m4s", "seg-2.m4s", "seg-4.m4s"), rep.segmentUrls)
    }
}
