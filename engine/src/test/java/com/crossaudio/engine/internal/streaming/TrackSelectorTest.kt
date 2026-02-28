package com.crossaudio.engine.internal.streaming

import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.internal.streaming.dash.DashRepresentation
import com.crossaudio.engine.internal.streaming.hls.HlsVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSelectorTest {
    private val selector = TrackSelector()

    @Test
    fun `hls selector respects requested bitrate target`() {
        val selected = selector.selectHlsVariant(
            variants = listOf(
                HlsVariant(uri = "low.m3u8", bandwidthKbps = 96),
                HlsVariant(uri = "mid.m3u8", bandwidthKbps = 160),
                HlsVariant(uri = "high.m3u8", bandwidthKbps = 320),
            ),
            cap = QualityCap.AUTO,
            targetBitrateKbps = 170,
        )

        assertEquals("mid.m3u8", selected.uri)
    }

    @Test
    fun `dash selector applies quality cap to targeted choice`() {
        val selected = selector.selectDashRepresentation(
            representations = listOf(
                DashRepresentation(id = "a", bandwidthKbps = 96, baseUrl = "a.m4s"),
                DashRepresentation(id = "b", bandwidthKbps = 160, baseUrl = "b.m4s"),
                DashRepresentation(id = "c", bandwidthKbps = 320, baseUrl = "c.m4s"),
            ),
            cap = QualityCap.LOW,
            targetBitrateKbps = 300,
        )

        assertEquals("b", selected?.id)
    }
}
