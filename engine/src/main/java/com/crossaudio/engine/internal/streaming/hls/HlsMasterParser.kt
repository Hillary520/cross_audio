package com.crossaudio.engine.internal.streaming.hls

internal data class HlsVariant(
    val uri: String,
    val bandwidthKbps: Int,
    val codecs: String? = null,
)

internal data class HlsMaster(
    val variants: List<HlsVariant>,
) {
    val isMaster: Boolean get() = variants.isNotEmpty()
}

internal object HlsMasterParser {
    fun parse(text: String): HlsMaster {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it.startsWith("#EXTM3U") }) return HlsMaster(emptyList())

        val variants = mutableListOf<HlsVariant>()
        var pendingBandwidthKbps: Int? = null
        var pendingCodecs: String? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttrs(line.substringAfter(':'))
                val bw = attrs["BANDWIDTH"]?.toIntOrNull()?.div(1000)
                pendingBandwidthKbps = bw
                pendingCodecs = attrs["CODECS"]
                continue
            }
            if (line.startsWith("#")) continue
            val bw = pendingBandwidthKbps
            if (bw != null) {
                variants += HlsVariant(
                    uri = line,
                    bandwidthKbps = bw.coerceAtLeast(1),
                    codecs = pendingCodecs,
                )
            }
            pendingBandwidthKbps = null
            pendingCodecs = null
        }
        return HlsMaster(variants)
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        splitAttributeTokens(raw).forEach { token ->
            val idx = token.indexOf('=')
            if (idx <= 0) return@forEach
            val key = token.substring(0, idx).trim()
            val value = token.substring(idx + 1).trim().trim('"')
            out[key] = value
        }
        return out
    }

    private fun splitAttributeTokens(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in raw) {
            when (ch) {
                '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ',' -> {
                    if (inQuotes) current.append(ch) else {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
