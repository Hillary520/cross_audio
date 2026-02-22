package com.crossaudio.engine.internal.streaming.dash

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal data class DashRepresentation(
    val id: String,
    val bandwidthKbps: Int,
    val baseUrl: String?,
    val initializationUrl: String? = null,
    val segmentUrls: List<String> = emptyList(),
)

internal data class DashManifest(
    val representations: List<DashRepresentation>,
    val initDataBase64: String? = null,
)

internal object MpdParser {
    fun parse(xml: String): DashManifest {
        val reps = mutableListOf<DashRepresentation>()
        var initDataBase64: String? = null
        runCatching {
            val doc = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

            if (initDataBase64 == null) {
                val allNodes = doc.getElementsByTagName("*")
                for (idx in 0 until allNodes.length) {
                    val node = allNodes.item(idx)
                    val name = node.nodeName ?: continue
                    if (name.endsWith("pssh")) {
                        val value = node.textContent?.trim()
                        if (!value.isNullOrEmpty()) {
                            initDataBase64 = value
                            break
                        }
                    }
                }
            }

            val repNodes = doc.getElementsByTagName("Representation")
            for (i in 0 until repNodes.length) {
                val rep = repNodes.item(i) as? Element ?: continue
                val id = rep.getAttribute("id").ifBlank { "rep_$i" }
                val bw = rep.getAttribute("bandwidth").toIntOrNull()?.div(1000)?.coerceAtLeast(1) ?: 0
                val baseUrl = rep.getElementsByTagName("BaseURL")
                    .item(0)
                    ?.textContent
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val initializationUrl = (rep.getElementsByTagName("Initialization").item(0) as? Element)
                    ?.getAttribute("sourceURL")
                    ?.takeIf { it.isNotBlank() }
                val segmentUrls = buildList {
                    val segmentNodes = rep.getElementsByTagName("SegmentURL")
                    for (s in 0 until segmentNodes.length) {
                        val seg = segmentNodes.item(s) as? Element ?: continue
                        val media = seg.getAttribute("media").takeIf { it.isNotBlank() } ?: continue
                        add(media)
                    }
                }
                reps += DashRepresentation(
                    id = id,
                    bandwidthKbps = bw,
                    baseUrl = baseUrl,
                    initializationUrl = initializationUrl,
                    segmentUrls = segmentUrls,
                )
            }
        }
        return DashManifest(representations = reps, initDataBase64 = initDataBase64)
    }
}
