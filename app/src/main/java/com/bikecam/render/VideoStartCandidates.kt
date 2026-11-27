package com.bikecam.render

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong

enum class StartSource {
    /** `creation_time` como início da gravação, em UTC (GoPro com fuso configurado pelo Quik). */
    CREATION_UTC,

    /** `creation_time` com a hora local gravada como se fosse UTC (câmera sem fuso). */
    CREATION_LOCAL,

    /** `creation_time` como fim da gravação (alguns celulares): menos a duração. */
    CREATION_END,

    /** Timecode da trilha `tmcd` (hora local do dia) com a data do `creation_time`. */
    TIMECODE,

    /** Data e hora no nome do arquivo. */
    FILE_NAME,

    /** Sem metadado útil: começa do início da trilha e o usuário ajusta. */
    TRACK_START,
}

data class StartCandidate(val source: StartSource, val epochMs: Long, val description: String)

/**
 * Palpites para a hora (de parede) do primeiro quadro do vídeo, do mais preciso ao mais vago. Cada
 * câmera grava de um jeito (UTC, hora local, fim em vez de início), então a tela de sincronização
 * mostra todos e o usuário escolhe e ajusta. Candidatos a menos de 1 s de um anterior são omitidos
 * (na GoPro, o timecode tem fração de segundo e o `creation_time`, não).
 */
object VideoStartCandidates {
    private val NAME_TIME = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})(\d{3})?(?!\d)""")
    private const val SAME_MS = 1_000L

    fun from(info: Mp4Info?, fileName: String?, zone: ZoneId): List<StartCandidate> {
        val out = ArrayList<StartCandidate>()
        val created = info?.creationEpochMs
        if (created != null) {
            info.timecode?.let { tc ->
                out += StartCandidate(StartSource.TIMECODE, timecodeEpoch(tc, created, zone), "Timecode ${tc.label} (hora local)")
            }
            out += StartCandidate(StartSource.CREATION_UTC, created, "Metadado de criação (UTC)")
            val wall = LocalDateTime.ofInstant(Instant.ofEpochMilli(created), ZoneOffset.UTC)
            val asLocal = wall.atZone(zone).toInstant().toEpochMilli()
            if (asLocal != created) {
                out += StartCandidate(StartSource.CREATION_LOCAL, asLocal, "Metadado de criação (hora local)")
            }
            if (info.durationMs > 0) {
                out += StartCandidate(StartSource.CREATION_END, created - info.durationMs, "Metadado de criação como fim do vídeo")
            }
        }
        fileName?.let { fromName(it, zone) }?.let { out += it }
        val distinct = ArrayList<StartCandidate>()
        for (c in out) if (distinct.none { abs(it.epochMs - c.epochMs) < SAME_MS }) distinct += c
        return distinct
    }

    /** Timecode (segundos desde a meia-noite local) no dia mais próximo do `creation_time`. */
    private fun timecodeEpoch(tc: Timecode, createdMs: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(createdMs).atZone(zone).toLocalDate()
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val base = midnight + (tc.secondsOfDay * 1000).roundToLong()
        return listOf(base - DAY_MS, base, base + DAY_MS).minBy { abs(it - createdMs) }
    }

    fun fromName(fileName: String, zone: ZoneId): StartCandidate? {
        val m = NAME_TIME.find(fileName) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val ms = m.groupValues[7].ifEmpty { "0" }.toInt()
        val wall = runCatching {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), ms * 1_000_000)
        }.getOrNull() ?: return null
        // Os celulares Pixel usam UTC no nome (PXL_…); os demais, hora local.
        val utc = fileName.startsWith("PXL_", ignoreCase = true)
        val epoch = (if (utc) wall.atOffset(ZoneOffset.UTC).toInstant() else wall.atZone(zone).toInstant()).toEpochMilli()
        return StartCandidate(StartSource.FILE_NAME, epoch, if (utc) "Nome do arquivo (UTC)" else "Nome do arquivo (hora local)")
    }

    private const val DAY_MS = 86_400_000L
}
