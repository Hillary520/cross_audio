package com.crossaudio.engine.internal.streaming.dash

import org.w3c.dom.Element
import java.io.StringReader
import kotlin.math.ceil
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
    val isDynamic: Boolean = false,
    val minimumUpdatePeriodMs: Long? = null,
    val initDataBase64: String? = null,
)

internal object MpdParser {
    fun parse(xml: String): DashManifest {
        val reps = mutableListOf<DashRepresentation>()
        var initDataBase64: String? = null
        var isDynamic = false
        var minimumUpdatePeriodMs: Long? = null
        var mediaPresentationDurationMs: Long? = null
        runCatching {
            val doc = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            val root = doc.documentElement
            val type = root?.getAttribute("type")?.trim()?.lowercase()
            isDynamic = type == "dynamic"
            minimumUpdatePeriodMs = root
                ?.getAttribute("minimumUpdatePeriod")
                ?.takeIf { it.isNotBlank() }
                ?.let(::parseIsoDurationMs)
            mediaPresentationDurationMs = root
                ?.getAttribute("mediaPresentationDuration")
                ?.takeIf { it.isNotBlank() }
                ?.let(::parseIsoDurationMs)

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
                val adaptation = rep.parentNode as? Element
                val period = adaptation?.parentNode as? Element
                val baseUrl = rep.firstChildTextByLocalName("BaseURL")
                    ?: adaptation?.firstChildTextByLocalName("BaseURL")
                    ?: period?.firstChildTextByLocalName("BaseURL")
                val initializationUrl = rep.firstChildElementByLocalName("Initialization")
                    ?.getAttribute("sourceURL")
                    ?.takeIf { it.isNotBlank() }
                val segmentListUrls = buildList {
                    val segmentNodes = rep.getElementsByTagName("*")
                    for (s in 0 until segmentNodes.length) {
                        val seg = segmentNodes.item(s) as? Element ?: continue
                        if (!seg.hasLocalName("SegmentURL")) continue
                        val media = seg.getAttribute("media").takeIf { it.isNotBlank() } ?: continue
                        add(media)
                    }
                }
                val template = rep.firstDirectChildByLocalName("SegmentTemplate")
                    ?: adaptation?.firstDirectChildByLocalName("SegmentTemplate")
                    ?: period?.firstDirectChildByLocalName("SegmentTemplate")

                val periodDurationMs = period
                    ?.getAttribute("duration")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::parseIsoDurationMs)

                val templateInit = template
                    ?.getAttribute("initialization")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { tpl ->
                        applyTemplate(
                            template = tpl,
                            representationId = id,
                            bandwidthKbps = bw,
                            number = template.getAttribute("startNumber").toLongOrNull() ?: 1L,
                            time = 0L,
                        )
                    }
                val initialization = initializationUrl ?: templateInit

                val templateSegments = expandTemplateSegments(
                    template = template,
                    representationId = id,
                    bandwidthKbps = bw,
                    isDynamic = isDynamic,
                    minimumUpdatePeriodMs = minimumUpdatePeriodMs,
                    mediaPresentationDurationMs = mediaPresentationDurationMs,
                    periodDurationMs = periodDurationMs,
                )
                val segmentUrls = if (segmentListUrls.isNotEmpty()) segmentListUrls else templateSegments

                reps += DashRepresentation(
                    id = id,
                    bandwidthKbps = bw,
                    baseUrl = baseUrl,
                    initializationUrl = initialization,
                    segmentUrls = segmentUrls,
                )
            }
        }
        return DashManifest(
            representations = reps,
            isDynamic = isDynamic,
            minimumUpdatePeriodMs = minimumUpdatePeriodMs,
            initDataBase64 = initDataBase64,
        )
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

    private fun Element.firstDirectChildByLocalName(local: String): Element? {
        val children = childNodes ?: return null
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.hasLocalName(local)) return child
        }
        return null
    }

    private fun expandTemplateSegments(
        template: Element?,
        representationId: String,
        bandwidthKbps: Int,
        isDynamic: Boolean,
        minimumUpdatePeriodMs: Long?,
        mediaPresentationDurationMs: Long?,
        periodDurationMs: Long?,
    ): List<String> {
        val t = template ?: return emptyList()
        val mediaTemplate = t.getAttribute("media").takeIf { it.isNotBlank() } ?: return emptyList()
        val startNumber = t.getAttribute("startNumber").toLongOrNull() ?: 1L
        val timescale = t.getAttribute("timescale").toLongOrNull()?.takeIf { it > 0L } ?: 1L
        val durationUnits = t.getAttribute("duration").toLongOrNull()?.takeIf { it > 0L }

        val timeline = t.firstDirectChildByLocalName("SegmentTimeline")
        if (timeline != null) {
            val periodUnits = periodDurationMs?.let { ms -> (ms * timescale) / 1000L }
            val points = expandTimelinePoints(
                timeline = timeline,
                startNumber = startNumber,
                periodDurationUnits = periodUnits,
                isDynamic = isDynamic,
                minimumUpdatePeriodMs = minimumUpdatePeriodMs,
                timescale = timescale,
            )
            return points.map { point ->
                applyTemplate(
                    template = mediaTemplate,
                    representationId = representationId,
                    bandwidthKbps = bandwidthKbps,
                    number = point.number,
                    time = point.time,
                )
            }
        }

        val dur = durationUnits ?: return emptyList()
        val segmentDurationMs = (dur * 1000.0) / timescale.toDouble()
        if (segmentDurationMs <= 0.0) return emptyList()
        val count = if (isDynamic) {
            liveWindowSegmentCount(minimumUpdatePeriodMs, segmentDurationMs)
        } else {
            val totalMs = periodDurationMs ?: mediaPresentationDurationMs
            val resolved = totalMs?.let { ceil(it / segmentDurationMs).toInt() } ?: 1
            resolved.coerceAtLeast(1)
        }
        return (0 until count).map { idx ->
            val number = startNumber + idx.toLong()
            applyTemplate(
                template = mediaTemplate,
                representationId = representationId,
                bandwidthKbps = bandwidthKbps,
                number = number,
                time = (number - startNumber) * dur,
            )
        }
    }

    private fun expandTimelinePoints(
        timeline: Element,
        startNumber: Long,
        periodDurationUnits: Long?,
        isDynamic: Boolean,
        minimumUpdatePeriodMs: Long?,
        timescale: Long,
    ): List<DashTimelinePoint> {
        val sNodes = timeline.childNodes
        if (sNodes == null || sNodes.length <= 0) return emptyList()
        val sElements = buildList {
            for (i in 0 until sNodes.length) {
                val el = sNodes.item(i) as? Element ?: continue
                if (el.hasLocalName("S")) add(el)
            }
        }
        if (sElements.isEmpty()) return emptyList()

        val out = mutableListOf<DashTimelinePoint>()
        var number = startNumber
        var currentTime: Long? = null

        for ((idx, s) in sElements.withIndex()) {
            val d = s.getAttribute("d").toLongOrNull()?.takeIf { it > 0L } ?: continue
            val explicitT = s.getAttribute("t").toLongOrNull()
            if (explicitT != null) {
                currentTime = explicitT
            } else if (currentTime == null) {
                currentTime = 0L
            }
            var repeat = s.getAttribute("r").toIntOrNull() ?: 0
            if (repeat < 0) {
                val nextT = sElements.getOrNull(idx + 1)?.getAttribute("t")?.toLongOrNull()
                val baseTime = currentTime ?: 0L
                repeat = when {
                    nextT != null -> {
                        val segments = ((nextT - baseTime) / d).toInt()
                        (segments - 1).coerceAtLeast(0)
                    }
                    periodDurationUnits != null -> {
                        val remaining = (periodDurationUnits - baseTime).coerceAtLeast(0L)
                        val segments = (remaining / d).toInt()
                        (segments - 1).coerceAtLeast(0)
                    }
                    else -> {
                        if (isDynamic) {
                            liveWindowSegmentCount(
                                minimumUpdatePeriodMs = minimumUpdatePeriodMs,
                                segmentDurationMs = (d * 1000.0) / timescale.toDouble(),
                            ) - 1
                        } else {
                            0
                        }
                    }
                }
            }
            val count = (repeat + 1).coerceAtLeast(1)
            repeat(count) {
                val t = currentTime ?: 0L
                out += DashTimelinePoint(number = number, time = t)
                number += 1L
                currentTime = t + d
            }
        }
        return out
    }

    private fun applyTemplate(
        template: String,
        representationId: String,
        bandwidthKbps: Int,
        number: Long,
        time: Long,
    ): String {
        var result = template
            .replace("\$RepresentationID\$", representationId)
            .replace("\$Bandwidth\$", (bandwidthKbps * 1000).toString())
            .replace("\$Time\$", time.toString())

        val numberRegex = Regex("\\$" + "Number(%0(\\d+)d)?\\$")
        result = numberRegex.replace(result) { m ->
            val width = m.groupValues.getOrNull(2)?.toIntOrNull()
            if (width != null && width > 0) {
                number.toString().padStart(width, '0')
            } else {
                number.toString()
            }
        }
        return result
    }

    private fun liveWindowSegmentCount(
        minimumUpdatePeriodMs: Long?,
        segmentDurationMs: Double,
    ): Int {
        if (segmentDurationMs <= 0.0) return 6
        val updateMs = (minimumUpdatePeriodMs ?: 2_000L).coerceAtLeast(500L).toDouble()
        val approx = ceil((updateMs * 3.0) / segmentDurationMs).toInt()
        return approx.coerceIn(3, 30)
    }

    private fun parseIsoDurationMs(raw: String): Long? {
        val text = raw.trim().uppercase()
        if (!text.startsWith("PT")) return null
        var idx = 2
        var number = ""
        var totalMs = 0.0
        while (idx < text.length) {
            val ch = text[idx]
            if (ch.isDigit() || ch == '.') {
                number += ch
                idx++
                continue
            }
            val value = number.toDoubleOrNull() ?: return null
            when (ch) {
                'H' -> totalMs += value * 3_600_000.0
                'M' -> totalMs += value * 60_000.0
                'S' -> totalMs += value * 1_000.0
                else -> return null
            }
            number = ""
            idx++
        }
        return totalMs.toLong().takeIf { it > 0L }
    }

    private data class DashTimelinePoint(
        val number: Long,
        val time: Long,
    )
}
