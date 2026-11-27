package com.bikecam

import com.bikecam.render.ChapterTimeline
import com.bikecam.render.SyncMath
import com.bikecam.render.SyncMath.Coverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

class SyncMathTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val start = 1_795_292_196_768L // 2026-11-21T20:16:36.768Z = 17:16:36,768 local

    @Test
    fun parsesClockText() {
        assertEquals(LocalTime.of(17, 16, 41), SyncMath.parseClock("17:16:41"))
        assertEquals(LocalTime.of(17, 16, 41, 500_000_000), SyncMath.parseClock(" 17:16:41.5 "))
        assertEquals(LocalTime.of(17, 16, 41, 250_000_000), SyncMath.parseClock("17:16:41,25"))
        assertEquals(LocalTime.of(7, 5), SyncMath.parseClock("7:05"))
        assertEquals(LocalTime.of(7, 5), SyncMath.parseClock("7h05"))
        assertNull(SyncMath.parseClock("25:00"))
        assertNull(SyncMath.parseClock("17:6:41"))
        assertNull(SyncMath.parseClock("abc"))
    }

    @Test
    fun startFromClockSeenInTheVideo() {
        // Aos 4,232 s do vídeo, o ciclocomputador mostrava 17:16:41.
        val got = SyncMath.startFromVisibleClock(LocalTime.of(17, 16, 41), 4_232, start + 90_000, zone)
        assertEquals(start, got)
    }

    @Test
    fun startFromClockPicksTheNearestDate() {
        // Vídeo que começa 23:59:50 local; relógio visto 00:00:05 aos 15 s → mesmo início, dia anterior ao do relógio.
        val ref = 1_795_316_390_000L // 2026-11-22T02:59:50Z = 23:59:50 local (21/11)
        val got = SyncMath.startFromVisibleClock(LocalTime.of(0, 0, 5), 15_000, ref + 3_000, zone)
        assertEquals(ref, got)
    }

    @Test
    fun coverage() {
        assertEquals(Coverage.FULL, SyncMath.coverage(100, 50, 0, 1000))
        assertEquals(Coverage.PARTIAL, SyncMath.coverage(980, 50, 0, 1000))
        assertEquals(Coverage.PARTIAL, SyncMath.coverage(-10, 50, 0, 1000))
        assertEquals(Coverage.NONE, SyncMath.coverage(1000, 50, 0, 1000))
        assertEquals(Coverage.NONE, SyncMath.coverage(-100, 50, 0, 1000))
    }

    @Test
    fun chaptersLocate() {
        val c = ChapterTimeline(listOf(1000, 500, 2000))
        assertEquals(3500L, c.totalMs)
        assertEquals(0 to 0L, c.locate(0))
        assertEquals(0 to 999L, c.locate(999))
        assertEquals(1 to 0L, c.locate(1000))
        assertEquals(2 to 100L, c.locate(1600))
        assertEquals(2 to 1999L, c.locate(99_999))
        assertEquals(0 to 0L, c.locate(-5))
    }
}
