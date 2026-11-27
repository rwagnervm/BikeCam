package com.bikecam.importer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

/** Hora ISO-8601 (`2026-11-21T19:52:35Z`, com fração ou offset). Sem fuso, assume UTC. */
internal fun parseIsoTime(s: String): Long? {
    val t = s.trim()
    return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/**
 * Base dos leitores SAX: guarda o texto de cada elemento e a pilha de nomes (sem prefixo de
 * namespace, já que cada exportador usa prefixos diferentes para as mesmas extensões).
 */
internal abstract class SampleHandler : DefaultHandler() {
    val samples = ArrayList<ActivitySample>()
    protected val stack = ArrayList<String>()
    private val text = StringBuilder()

    final override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = name(localName, qName)
        stack.add(name)
        text.setLength(0)
        onStart(name, attributes)
    }

    final override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = name(localName, qName)
        onEnd(name, text.toString().trim())
        text.setLength(0)
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    protected fun parent(levelsUp: Int = 1): String? = stack.getOrNull(stack.size - 1 - levelsUp)

    protected abstract fun onStart(name: String, attributes: Attributes)
    protected abstract fun onEnd(name: String, text: String)

    private fun name(localName: String?, qName: String?): String =
        (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')).lowercase()
}

internal fun parseXml(input: InputStream, handler: SampleHandler): List<ActivitySample> {
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    // Arquivos vêm de fora: nada de entidades externas (XXE).
    for (feature in listOf(
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/disallow-doctype-decl",
    )) {
        runCatching { factory.setFeature(feature, feature.endsWith("disallow-doctype-decl")) }
    }
    factory.newSAXParser().parse(InputSource(input), handler)
    return handler.samples
}

/** Campos de uma amostra sendo montada; vira [ActivitySample] quando a hora existe. */
internal class SampleBuilder {
    var time: Long? = null
    var lat: Double? = null
    var lon: Double? = null
    var alt: Double? = null
    var speed: Float? = null
    var dist: Double? = null
    var hr: Int? = null
    var cad: Int? = null
    var power: Int? = null
    var temp: Float? = null

    fun build(): ActivitySample? = time?.let {
        ActivitySample(it, lat, lon, alt, speed, dist, hr, cad, power, temp)
    }

    /** Extensões comuns (Garmin TrackPointExtension, Strava, Wahoo…), pelo nome sem prefixo. */
    fun extension(name: String, text: String) {
        when (name) {
            "hr", "heartrate", "heartratebpm" -> hr = text.toNumber()?.toInt()
            "cad", "cadence" -> cad = text.toNumber()?.toInt()
            "power", "powerinwatts", "watts" -> power = text.toNumber()?.toInt()
            "atemp", "temp", "temperature" -> temp = text.toNumber()?.toFloat()
            "speed" -> speed = text.toNumber()?.toFloat()
        }
    }
}

internal fun String.toNumber(): Double? = trim().toDoubleOrNull()?.takeIf { it.isFinite() }

/** GPX 1.0/1.1: `trkpt` (e `rtept`, caso a trilha venha como rota) com extensões opcionais. */
object GpxReader {
    fun read(input: InputStream): List<ActivitySample> = parseXml(input, Handler())

    private class Handler : SampleHandler() {
        private var current: SampleBuilder? = null

        override fun onStart(name: String, attributes: Attributes) {
            if (name == "trkpt" || name == "rtept") {
                current = SampleBuilder().apply {
                    lat = attributes.getValue("lat")?.toNumber()
                    lon = attributes.getValue("lon")?.toNumber()
                }
            }
        }

        override fun onEnd(name: String, text: String) {
            val b = current ?: return
            when (name) {
                "trkpt", "rtept" -> { b.build()?.let { samples.add(it) }; current = null }
                "ele" -> b.alt = text.toNumber()
                "time" -> b.time = parseIsoTime(text)
                else -> if (stack.contains("extensions")) b.extension(name, text)
            }
        }
    }
}

/** Garmin TCX: `Trackpoint` com posição, altitude, distância, FC, cadência e extensão TPX. */
object TcxReader {
    fun read(input: InputStream): List<ActivitySample> = parseXml(input, Handler())

    private class Handler : SampleHandler() {
        private var current: SampleBuilder? = null

        override fun onStart(name: String, attributes: Attributes) {
            if (name == "trackpoint") current = SampleBuilder()
        }

        override fun onEnd(name: String, text: String) {
            val b = current ?: return
            when (name) {
                "trackpoint" -> { b.build()?.let { samples.add(it) }; current = null }
                "time" -> b.time = parseIsoTime(text)
                "latitudedegrees" -> b.lat = text.toNumber()
                "longitudedegrees" -> b.lon = text.toNumber()
                "altitudemeters" -> b.alt = text.toNumber()
                "distancemeters" -> b.dist = text.toNumber()
                "value" -> if (parent() == "heartratebpm") b.hr = text.toNumber()?.toInt()
                "cadence" -> if (parent() == "trackpoint") b.cad = text.toNumber()?.toInt()
                else -> if (stack.contains("extensions")) b.extension(name, text)
            }
        }
    }
}
