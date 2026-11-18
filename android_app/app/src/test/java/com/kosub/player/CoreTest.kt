package com.kosub.player

import com.kosub.player.asr.WhisperAsr
import com.kosub.player.core.ClaudeTranslator
import com.kosub.player.core.GoogleTranslator
import com.kosub.player.core.IntervalSet
import com.kosub.player.core.JobState
import com.kosub.player.core.Segment
import com.kosub.player.core.Segmentation
import com.kosub.player.core.SubUnit
import com.kosub.player.core.Subtitles
import com.kosub.player.core.Word
import com.kosub.player.media.Resampler
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class CoreTest {
    private fun seg(vararg words: Triple<Double, Double, String>): Segment {
        val ws = words.map { Word(it.first, it.second, " " + it.third) }
        return Segment(ws.first().start, ws.last().end, ws.joinToString("") { it.text }, ws)
    }

    @Test
    fun intervalsMergeAndGaps() {
        val s = IntervalSet()
        s.add(10.0, 20.0); s.add(0.0, 5.0); s.add(19.0, 30.0); s.add(5.1, 6.0)
        assertEquals(listOf(0.0 to 6.0, 10.0 to 30.0), s.toList())
        assertEquals(listOf(6.0 to 10.0, 30.0 to 40.0), s.gaps(0.0, 40.0))
        assertTrue(s.contains(12.0)); assertFalse(s.contains(8.0))
        assertEquals(26.0, s.total(), 1e-9)
    }

    @Test
    fun srtFormattingAndRoundTrip() {
        assertEquals("01:01:01,235", Subtitles.srtTime(3661.2346))
        val units = listOf(SubUnit(1, 1.0, 3.0, "Hello there.", "안녕하세요."), SubUnit(2, 4.0, 6.0, "Bye.", "잘 가요."))
        val srt = Subtitles.cuesToSrt(Subtitles.buildCues(units, "ko"), "ko")
        val parsed = Subtitles.parseSrt("﻿" + srt.replace("\n", "\r\n"))
        assertEquals(listOf("안녕하세요.", "잘 가요."), parsed.map { it.text })
        val both = Subtitles.cuesToSrt(Subtitles.buildCues(units, "ko"), "both", "en")
        assertTrue(both.contains("안녕하세요.\nHello there."))
    }

    @Test
    fun wrapAndSplitKorean() {
        val text = "오늘은 날씨가 정말 좋아서 공원에 산책을 가기로 했어요"
        val lines = Subtitles.wrapText(text, 22).split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.length <= 22 })
        val pieces = Subtitles.splitByWeights("어제 우리는 아주 멀리 있는 산에 올라가서 해가 지는 것을 오래 바라보았다", listOf(30, 30))
        assertNotNull(pieces); assertEquals(2, pieces!!.size)
        assertNull(Subtitles.splitByWeights("네", listOf(10, 10)))
    }

    @Test
    fun buildCuesSplitsPartsAndAvoidsOverlap() {
        val units = listOf(
            SubUnit(1, 0.0, 0.4, "Hi.", "안녕."),
            SubUnit(2, 5.0, 14.0, "This is a very long sentence, and it keeps going for a while.",
                "이것은 아주 긴 문장이고 한동안 계속 이어집니다.",
                parts = listOf(SubUnit.Part(5.0, 9.0, "This is a very long sentence,"), SubUnit.Part(9.2, 14.0, "and it keeps going for a while."))),
            SubUnit(3, 14.0, 15.0, "Untranslated", null),
        )
        val cues = Subtitles.buildCues(units)
        assertEquals(listOf(1, 2, 2, 3), cues.map { it.unitId })
        assertTrue(cues[0].end >= 0.8)
        assertTrue(cues[1].end <= cues[2].start)
        assertFalse(cues[3].translated)
    }

    @Test
    fun segmentationSentencesMergingAndParts() {
        val a = seg(Triple(0.0, 0.5, "I"), Triple(0.5, 1.0, "went"), Triple(1.0, 1.4, "to"), Triple(1.4, 2.0, "the"), Triple(2.0, 2.6, "store"))
        val b = seg(Triple(2.7, 3.2, "and"), Triple(3.2, 3.7, "bought"), Triple(3.7, 4.0, "some"), Triple(4.0, 4.6, "milk."))
        val units = Segmentation.buildUnits(listOf(a, b), "en")
        assertEquals(listOf("I went to the store and bought some milk."), units.map { it.text })

        val abbrev = Segmentation.buildUnits(listOf(seg(Triple(0.0, 0.3, "Mr."), Triple(0.3, 0.8, "Smith"), Triple(0.8, 1.2, "is"), Triple(1.2, 2.0, "here."))), "en")
        assertEquals(listOf("Mr. Smith is here."), abbrev.map { it.text })

        val tokens = "So yesterday I walked all the way across the old town, and then I finally found the little bookshop".split(" ")
        var t = 0.0
        val words = tokens.map { tok -> Triple(t, t + 0.45, tok).also { t += if (tok.endsWith(",")) 1.1 else 0.5 } }
        val long = Segmentation.buildUnits(listOf(seg(*words.toTypedArray())), "en").single()
        assertNotNull(long.parts)
        assertTrue(long.parts!![0].text.endsWith("town,"))
        assertTrue(long.parts!!.all { it.end - it.start <= 7.0 + 1e-6 })
    }

    @Test
    fun segmentationCjkWithoutWordTimes() {
        val units = Segmentation.buildUnits(listOf(Segment(0.0, 6.0, "今日は晴れです。散歩に行きましょう。")), "ja")
        assertEquals(listOf("今日は晴れです。", "散歩に行きましょう。"), units.map { it.text })
    }

    @Test
    fun hallucinationNeedsLowConfidence() {
        assertTrue(Segmentation.isHallucination(Segment(0.0, 2.0, " Thank you for watching!", avgLogprob = -0.9)))
        assertFalse(Segmentation.isHallucination(Segment(0.0, 2.0, " Thank you for watching!", avgLogprob = -0.1)))
    }

    @Test
    fun jobStateJsonRoundTrip() {
        val st = JobState("movie.mp4", "abc")
        st.duration = 120.0; st.language = "en"; st.languageProb = 0.97
        st.units[1] = SubUnit(1, 1.0, 2.0, "Hi", "안녕", parts = listOf(SubUnit.Part(1.0, 1.5, "H"), SubUnit.Part(1.5, 2.0, "i")), edited = true)
        st.covered.add(0.0, 30.0)
        val back = JobState.fromJson(st.toJson())
        assertEquals("en", back.language)
        assertEquals(30.0, back.covered.total(), 1e-9)
        assertEquals("안녕", back.units[1]!!.ko)
        assertTrue(back.units[1]!!.edited)
        assertEquals(2, back.units[1]!!.parts!!.size)
        assertEquals(2, back.nextId)
    }

    @Test
    fun googleTranslatorBatchesAndFallsBack() {
        val calls = ArrayList<String>()
        val tr = GoogleTranslator { url ->
            val q = URLDecoder.decode(url.substringAfter("&q="), "UTF-8")
            calls.add(q)
            val translated = q.split("\n").joinToString("\n") { "번역<$it>" }
            JSONArray().put(JSONArray().put(JSONArray().put(translated).put(q))).toString()
        }
        assertEquals(listOf("번역<Hello.>", "♪ ♪", "번역<How are you?>"), tr.translate(listOf("Hello.", "♪ ♪", "How are you?"), "en", emptyList()))
        assertEquals("Hello.\nHow are you?", calls[0])

        val merging = GoogleTranslator { url ->
            val q = URLDecoder.decode(url.substringAfter("&q="), "UTF-8")
            JSONArray().put(JSONArray().put(JSONArray().put("번역 " + q.replace("\n", " ")).put(q))).toString()
        }
        assertEquals(listOf("번역 One.", "번역 Two."), merging.translate(listOf("One.", "Two."), "en", emptyList()))
    }

    @Test
    fun claudeResponseParsing() {
        val out = ClaudeTranslator.parseTranslations("""{"translations":[{"id":1,"ko":" 둘째 "},{"id":0,"ko":"첫째"},{"id":9,"ko":"x"}]}""", 3)
        assertEquals(listOf("첫째", "둘째", null), out)
    }

    @Test
    fun whisperJsonParsing() {
        val json = """{"lang":"en","lang_prob":0.96,"segments":[{"t0":10,"t1":250,"text":" Hello world.","logprob":-0.2,"words":[[10,60," Hello"],[70,250," world."]]}]}"""
        val r = WhisperAsr.parse(json, null)
        assertEquals("en", r.language)
        assertEquals(0.96, r.languageProb, 1e-9)
        val s = r.segments.single()
        assertEquals(0.1, s.start, 1e-9); assertEquals(2.5, s.end, 1e-9)
        assertEquals(" world.", s.words!![1].text)
        assertTrue(WhisperAsr.parse("""{"aborted":true}""", "en").aborted)
    }

    @Test
    fun resamplerKeepsToneAndLength() {
        val src = 48000
        val x = FloatArray(src) { (0.5 * sin(2 * PI * 440.0 * it / src)).toFloat() }
        val y = Resampler.resample(x, src, 16000)
        assertEquals(16000, y.size)
        val rms = sqrt(y.map { it * it.toDouble() }.average())
        assertEquals(0.5 / sqrt(2.0), rms, 0.02)
        // 7 kHz 이상 성분은 거의 사라져야 한다 (접힘 방지)
        val hi = FloatArray(src) { (0.5 * sin(2 * PI * 12000.0 * it / src)).toFloat() }
        val yh = Resampler.resample(hi, src, 16000)
        val rmsHi = sqrt(yh.drop(100).dropLast(100).map { it * it.toDouble() }.average())
        assertTrue("aliasing rms=$rmsHi", rmsHi < 0.05)
        assertEquals(listOf(0.5f, 1f), Resampler.downmix(floatArrayOf(0f, 1f, 1f, 1f), 2).toList())
        assertTrue(abs(y[100]) <= 0.6f)
    }
}
