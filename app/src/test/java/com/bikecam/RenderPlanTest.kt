package com.bikecam

import com.bikecam.render.ChapterClip
import com.bikecam.render.OutputCodec
import com.bikecam.render.OutputResolution
import com.bikecam.render.RenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlanTest {
    @Test
    fun clipsAcrossChapters() {
        val d = listOf(1000L, 500L, 2000L)
        assertEquals(listOf(ChapterClip(0, 0, 1000), ChapterClip(1, 0, 500), ChapterClip(2, 0, 2000)), RenderPlan.clips(d, 0, 3500))
        assertEquals(listOf(ChapterClip(0, 800, 1000), ChapterClip(1, 0, 500), ChapterClip(2, 0, 100)), RenderPlan.clips(d, 800, 1600))
        assertEquals(listOf(ChapterClip(1, 100, 400)), RenderPlan.clips(d, 1100, 1400))
        assertEquals(emptyList<ChapterClip>(), RenderPlan.clips(d, 2000, 2000))
    }

    @Test
    fun outputSizeNeverUpscalesAndIsEven() {
        assertEquals(1920 to 1080, RenderPlan.outputSize(5312, 2988, OutputResolution.P1080))
        assertEquals(3840 to 2160, RenderPlan.outputSize(5312, 2988, OutputResolution.P2160))
        assertEquals(5312 to 2988, RenderPlan.outputSize(5312, 2988, OutputResolution.ORIGINAL))
        assertEquals(1080 to 1920, RenderPlan.outputSize(2160, 3840, OutputResolution.P1080)) // retrato
        assertEquals(1280 to 720, RenderPlan.outputSize(1280, 720, OutputResolution.P1080)) // não amplia
        val (w, h) = RenderPlan.outputSize(1000, 750, OutputResolution.ORIGINAL)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun bitrateAndSize() {
        val h264 = RenderPlan.videoBitrate(1920, 1080, 29.97f, OutputCodec.H264)
        assertEquals(15_536_448.0, h264.toDouble(), 1.0)
        val hevc = RenderPlan.videoBitrate(1920, 1080, 29.97f, OutputCodec.HEVC)
        assertEquals(h264 * 0.6, hevc.toDouble(), 2.0)
        assertEquals(100_000_000, RenderPlan.videoBitrate(7680, 4320, 60f, OutputCodec.H264))
        // 2 min a 15,5 Mbps (+25 % de excesso do codificador) + 192 kbps ≈ 294 MB
        val bytes = RenderPlan.estimateBytes(120_000, h264, audio = true)
        assertEquals((15_536_448 * 1.25 + 192_000) * 15, bytes.toDouble(), 10.0)
    }
}
