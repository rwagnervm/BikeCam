package com.bikecam.render

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/** Contas da sincronização vídeo × trilha, sem Android. */
object SyncMath {
    private val CLOCK = Regex("""^\s*(\d{1,2})[:h](\d{2})(?::(\d{2})(?:[.,](\d{1,3}))?)?\s*$""")

    /** `17:16`, `17:16:41`, `17:16:41.5` ou `17:16:41,25` (hora local). */
    fun parseClock(text: String): LocalTime? {
        val m = CLOCK.matchEntire(text) ?: return null
        val (h, mi, s, frac) = m.destructured
        val nanos = if (frac.isEmpty()) 0 else frac.padEnd(9, '0').take(9).toInt()
        return runCatching { LocalTime.of(h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt(), nanos) }.getOrNull()
    }

    /**
     * Hora de início do vídeo sabendo que, na posição [positionMs] do vídeo, um relógio filmado
     * (ou a tela do ciclocomputador) mostrava [clock]. A data é a mais próxima de [referenceEpochMs].
     */
    fun startFromVisibleClock(clock: LocalTime, positionMs: Long, referenceEpochMs: Long, zone: ZoneId): Long {
        val refDate = Instant.ofEpochMilli(referenceEpochMs).atZone(zone).toLocalDate()
        return listOf(refDate.minusDays(1), refDate, refDate.plusDays(1))
            .map { ZonedDateTime.of(it, clock, zone).toInstant().toEpochMilli() - positionMs }
            .minBy { abs(it - referenceEpochMs) }
    }

    enum class Coverage { FULL, PARTIAL, NONE }

    /** Quanto do vídeo [startMs, startMs + durationMs] cai dentro da trilha. */
    fun coverage(startMs: Long, durationMs: Long, trackStartMs: Long, trackEndMs: Long): Coverage {
        val endMs = startMs + durationMs
        return when {
            startMs >= trackStartMs && endMs <= trackEndMs -> Coverage.FULL
            endMs <= trackStartMs || startMs >= trackEndMs -> Coverage.NONE
            else -> Coverage.PARTIAL
        }
    }
}

/** Capítulos de uma gravação tocados em sequência (a GoPro não deixa lacuna entre eles). */
class ChapterTimeline(private val durationsMs: List<Long>) {
    val totalMs: Long = durationsMs.sum()

    /** Capítulo e posição dentro dele para uma posição na gravação inteira. */
    fun locate(positionMs: Long): Pair<Int, Long> {
        var rest = positionMs.coerceIn(0L, (totalMs - 1).coerceAtLeast(0L))
        for ((i, d) in durationsMs.withIndex()) {
            if (rest < d || i == durationsMs.size - 1) return i to rest.coerceAtMost((d - 1).coerceAtLeast(0L))
            rest -= d
        }
        return 0 to 0L
    }
}
