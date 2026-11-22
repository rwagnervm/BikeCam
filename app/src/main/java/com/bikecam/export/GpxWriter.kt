package com.bikecam.export

import java.io.Writer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** GPX 1.1 com a extensão Garmin TrackPointExtension (FC e cadência), aceito pelo Strava. */
object GpxWriter {
    fun write(out: Writer, name: String, points: List<TrackPoint>) {
        val iso = DateTimeFormatter.ISO_INSTANT
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write(
            "<gpx version=\"1.1\" creator=\"BikeCam\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
        val start = points.firstOrNull()?.epochMs
        if (start != null) out.write("  <metadata><time>${iso.format(Instant.ofEpochMilli(start))}</time></metadata>\n")
        out.write("  <trk>\n    <name>${escape(name)}</name>\n    <type>cycling</type>\n    <trkseg>\n")
        for (p in points) {
            val lat = p.latitude ?: continue
            val lon = p.longitude ?: continue
            out.write(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", lat, lon))
            p.altitudeM?.let { out.write(String.format(Locale.US, "<ele>%.1f</ele>", it)) }
            out.write("<time>${iso.format(Instant.ofEpochMilli(p.epochMs))}</time>")
            if (p.heartRateBpm != null || p.cadenceRpm != null) {
                out.write("<extensions><gpxtpx:TrackPointExtension>")
                p.heartRateBpm?.let { out.write("<gpxtpx:hr>$it</gpxtpx:hr>") }
                p.cadenceRpm?.let { out.write("<gpxtpx:cad>$it</gpxtpx:cad>") }
                out.write("</gpxtpx:TrackPointExtension></extensions>")
            }
            out.write("</trkpt>\n")
        }
        out.write("    </trkseg>\n  </trk>\n</gpx>\n")
        out.flush()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
