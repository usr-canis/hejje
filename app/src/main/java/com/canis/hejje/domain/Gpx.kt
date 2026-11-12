package com.canis.hejje.domain

import java.time.Instant
import java.time.format.DateTimeFormatter

/** Minimal GPX 1.1 reader and writer (tracks only), without any Android dependency. */
object Gpx {
    class Point(val lat: Double, val lon: Double, val ele: Float, val time: Long)
    class Track(val name: String?, val segments: List<List<RawPoint>>)

    fun write(name: String, creator: String, segments: List<List<Point>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(escape(creator))
        append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        for (seg in segments) {
            if (seg.isEmpty()) continue
            append("    <trkseg>\n")
            for (p in seg) {
                append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
                if (!p.ele.isNaN()) append("<ele>").append(Math.round(p.ele * 10) / 10f).append("</ele>")
                append("<time>").append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(p.time))).append("</time>")
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private val TRK = Regex("<trk[\\s>].*?</trk>", RegexOption.DOT_MATCHES_ALL)
    private val SEG = Regex("<trkseg[\\s>].*?</trkseg>", RegexOption.DOT_MATCHES_ALL)
    private val PT = Regex("<trkpt\\b([^>]*?)(/>|>(.*?)</trkpt>)", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
    private val LAT = Regex("\\blat\\s*=\\s*[\"']([^\"']+)[\"']")
    private val LON = Regex("\\blon\\s*=\\s*[\"']([^\"']+)[\"']")
    private val ELE = Regex("<ele>\\s*([^<]+?)\\s*</ele>")
    private val TIME = Regex("<time>\\s*([^<]+?)\\s*</time>")

    /** Reads all tracks. Points without a timestamp are skipped because a drive needs times. */
    fun parse(text: String): List<Track> = TRK.findAll(text).map { trk ->
        val body = trk.value
        val name = NAME.find(body.substringBefore("<trkseg"))?.groupValues?.get(1)?.let(::unescape)?.trim()
        val segs = SEG.findAll(body).map { seg ->
            PT.findAll(seg.value).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val inner = m.groupValues[3]
                val lat = LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
                val time = TIME.find(inner)?.groupValues?.get(1)?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                        ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                }
                if (lat == null || lon == null || time == null) null
                else RawPoint(time, lat, lon, alt = ELE.find(inner)?.groupValues?.get(1)?.toFloatOrNull() ?: Float.NaN)
            }.toList()
        }.filter { it.isNotEmpty() }.toList()
        Track(name, segs)
    }.filter { it.segments.isNotEmpty() }.toList()

    /** Converts stored segments to GPX points, hiding anything inside [zones]. */
    fun pointsOf(segments: List<Segment>, zones: List<Zone>): List<List<Point>> = segments.sortedBy { it.start }.flatMap { s ->
        val p = s.data.profile
        PrivacyZones.trim(s.data.route, zones).map { r ->
            List(r.size) { i ->
                val w = (r.t[i] * 1000L / p.stepMs).toInt().coerceIn(0, (p.size - 1).coerceAtLeast(0))
                Point(r.lat[i], r.lon[i], if (p.size > 0) p.alt[w] else Float.NaN, s.start + r.t[i] * 1000L)
            }
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
