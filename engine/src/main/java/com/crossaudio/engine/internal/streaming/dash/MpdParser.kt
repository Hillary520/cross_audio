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

            val allElements = doc.getElementsByTagName("*")
            if (initDataBase64 == null) {
                for (idx in 0 until allElements.length) {
                    val node = allElements.item(idx) as? Element ?: continue
                    if (!node.hasLocalName("pssh")) continue
                    val value = node.textContent?.trim()
                    if (!value.isNullOrEmpty()) {
                        initDataBase64 = value
                        break
                    }
                }
            }

            val repElements = mutableListOf<Element>()
            for (i in 0 until allElements.length) {
                val el = allElements.item(i) as? Element ?: continue
                if (el.hasLocalName("Representation")) repElements += el
            }
            for ((i, rep) in repElements.withIndex()) {
                val id = rep.getAttribute("id").ifBlank { "rep_$i" }
                val bw = rep.getAttribute("bandwidth").toIntOrNull()?.div(1000)?.coerceAtLeast(1) ?: 0
                val baseUrl = rep.firstChildTextByLocalName("BaseURL")
                    ?: (rep.parentNode as? Element)?.firstChildTextByLocalName("BaseURL")
                    ?: (rep.parentNode?.parentNode as? Element)?.firstChildTextByLocalName("BaseURL")
                val initializationUrl = rep.firstChildElementByLocalName("Initialization")
                    ?.getAttribute("sourceURL")
                    ?.takeIf { it.isNotBlank() }
                val segmentUrls = buildList {
                    val segmentNodes = rep.getElementsByTagName("*")
                    for (s in 0 until segmentNodes.length) {
                        val seg = segmentNodes.item(s) as? Element ?: continue
                        if (!seg.hasLocalName("SegmentURL")) continue
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

    private fun Element.hasLocalName(local: String): Boolean {
        val ln = this.localName ?: this.nodeName?.substringAfterLast(':') ?: return false
        return ln.equals(local, ignoreCase = true)
    }

    private fun Element.firstChildElementByLocalName(local: String): Element? {
        val nodes = getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.hasLocalName(local)) return el
        }
        return null
    }

    private fun Element.firstChildTextByLocalName(local: String): String? {
        return firstChildElementByLocalName(local)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
