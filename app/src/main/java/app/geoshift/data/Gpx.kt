package app.geoshift.data

import app.geoshift.core.LatLon
import java.util.Locale

/**
 * Pure-Kotlin GPX 1.0/1.1 route parser and exporter. Supports track points (<trkpt>),
 * route points (<rtept>) and fallback waypoints (<wpt>).
 */
object Gpx {
    data class ParsedRoute(val name: String, val points: List<LatLon>)

    private val NAME_TAG = Regex("""<(?:[A-Za-z0-9_]+:)?name\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?name>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TRKPT = Regex("""<(?:[A-Za-z0-9_]+:)?trkpt\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val RTEPT = Regex("""<(?:[A-Za-z0-9_]+:)?rtept\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val WPT = Regex("""<(?:[A-Za-z0-9_]+:)?wpt\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("""\blat\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LON_ATTR = Regex("""\blon\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun parse(xml: String): ParsedRoute {
        if (!xml.contains("<gpx", ignoreCase = true) && !xml.contains("trkpt", ignoreCase = true) &&
            !xml.contains("rtept", ignoreCase = true) && !xml.contains("wpt", ignoreCase = true)
        ) {
            fail("文件不是有效的 GPX 轨迹文件。")
        }
        val points = extractPoints(xml, TRKPT)
            .ifEmpty { extractPoints(xml, RTEPT) }
            .ifEmpty { extractPoints(xml, WPT) }
        if (points.size < 2) fail("GPX 文件中未找到至少 2 个有效坐标点。")
        val rawName = NAME_TAG.find(xml)?.groupValues?.getOrNull(1)?.let(::unescapeXml)?.trim().orEmpty()
        return ParsedRoute(rawName.take(120), points)
    }

    fun export(name: String, points: List<LatLon>): String = buildString(points.size * 64 + 256) {
        val safeName = escapeXml(name.ifBlank { "Geoshift Route" })
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="Geoshift" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <trk>")
        appendLine("    <name>$safeName</name>")
        appendLine("    <trkseg>")
        for (p in points) {
            append("      <trkpt lat=\"")
            append(String.format(Locale.ROOT, "%.7f", p.lat))
            append("\" lon=\"")
            append(String.format(Locale.ROOT, "%.7f", p.lon))
            appendLine("\"/>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }

    private fun extractPoints(xml: String, tag: Regex): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (m in tag.findAll(xml)) {
            val attrs = m.groupValues[1]
            val lat = LAT_ATTR.find(attrs)?.groupValues?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: continue
            val lon = LON_ATTR.find(attrs)?.groupValues?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: continue
            val p = LatLon(lat, lon)
            if (p.valid) out.add(p)
        }
        return out
    }

    private fun escapeXml(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    private fun unescapeXml(s: String): String = s
        .removePrefix("<![CDATA[").removeSuffix("]]>")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
