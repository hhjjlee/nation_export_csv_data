package com.kosub.player

import com.kosub.player.core.Asr
import com.kosub.player.core.AsrResult
import com.kosub.player.core.AudioSource
import com.kosub.player.core.EngineListener
import com.kosub.player.core.EngineOptions
import com.kosub.player.core.JobState
import com.kosub.player.core.Segment
import com.kosub.player.core.StateStore
import com.kosub.player.core.SubUnit
import com.kosub.player.core.SubtitleSink
import com.kosub.player.core.Subtitles
import com.kosub.player.core.Translator
import com.kosub.player.core.TranslatorException
import com.kosub.player.core.Word
import com.kosub.player.engine.SubtitleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 소리 2.5초 / 무음 0.5초가 반복되는 가짜 오디오. */
class ToneAudio(private val seconds: Double) : AudioSource {
    override val durationSec: Double = seconds
    override fun read(start: Double, end: Double): FloatArray {
        val e = minOf(end, seconds)
        if (e <= start) return FloatArray(0)
        val n = ((e - start) * 16000).toInt()
        return FloatArray(n) { i ->
            val t = start + i / 16000.0
            if (t % 3.0 < 2.5) (0.3 * sin(2 * PI * 440 * t)).toFloat() else 0f
        }
    }
}

/** 소리가 있는 곳마다 'Fake sentence N.'을 만드는 가짜 인식기. */
class FakeAsr(private val delayMs: Long = 0) : Asr {
    override val modelName = "fake"
    @Volatile var aborted = false
    var calls = 0
    override fun transcribe(pcm: FloatArray, language: String?): AsrResult {
        calls++
        aborted = false
        val dur = pcm.size / 16000.0
        val segs = ArrayList<Segment>()
        var t = 0.0
        var n = 0
        while (t + 1.0 <= dur) {
            if (delayMs > 0) Thread.sleep(delayMs)
            if (aborted) return AsrResult(emptyList(), language, 0.0, aborted = true)
            val i0 = (t * 16000).toInt()
            val i1 = minOf(pcm.size, ((t + 1.0) * 16000).toInt())
            var energy = 0.0
            for (i in i0 until i1) energy += pcm[i] * pcm[i]
            if (energy / (i1 - i0) > 1e-4) {
                n++
                val words = listOf(" Fake", " sentence", " number", " $n.").mapIndexed { k, w -> Word(t + k * 0.2, t + k * 0.2 + 0.18, w) }
                segs.add(Segment(t, t + 0.8, words.joinToString("") { it.text }, words, -0.2))
                t += 3.0
            } else t += 0.5
        }
        return AsrResult(segs, language ?: "en", 0.97)
    }
    override fun abort() { aborted = true }
}

class FakeTranslator(private val fail: TranslatorException? = null) : Translator {
    override val name = "fake"
    override val label = "가짜"
    override val batchSize = 10
    var calls = 0
    override fun translate(texts: List<String>, srcLang: String, context: List<Pair<String, String>>): List<String?> {
        calls++
        fail?.let { throw it }
        return texts.map { "번역: $it" }
    }
}

class MemorySink : SubtitleSink {
    val files = HashMap<String, String>()
    override fun write(fileName: String, content: String): String { files[fileName] = content; return "mem/$fileName" }
}

class MemoryStore : StateStore {
    var saved: String? = null
    override fun load(): JobState? = saved?.let { JobState.fromJson(it) }
    override fun save(state: JobState) { saved = state.toJson() }
}

class Recorder : EngineListener {
    val done = ArrayList<Pair<Boolean, String>>()
    val errors = ArrayList<Pair<String, Boolean>>()
    val actives = ArrayList<Pair<Double, Double>>()
    val units = ArrayList<SubUnit>()
    override fun onDone(complete: Boolean, message: String) { synchronized(this) { done.add(complete to message) } }
    override fun onError(text: String, fatal: Boolean) { synchronized(this) { errors.add(text to fatal) } }
    override fun onUnits(units: List<SubUnit>) { synchronized(this) { this.units.addAll(units) } }
    override fun onCoverage(intervals: List<Pair<Double, Double>>, active: Pair<Double, Double>?, duration: Double?, speed: Double?) {
        if (active != null) synchronized(this) { actives.add(active) }
    }
}

class EngineTest {
    private fun engine(
        seconds: Double, rec: Recorder, sink: MemorySink = MemorySink(), store: MemoryStore = MemoryStore(),
        asr: Asr = FakeAsr(), translator: Translator? = FakeTranslator(), opts: EngineOptions = EngineOptions(),
        state: JobState = store.load() ?: JobState("movie.mp4", "fp"), retranslate: Boolean = false,
    ) = SubtitleEngine(state, ToneAudio(seconds), { asr }, { translator }, sink, store, opts, rec, retranslate)

    @Test
    fun fullJobWritesKoreanAndSourceSrt() {
        val rec = Recorder(); val sink = MemorySink(); val store = MemoryStore()
        engine(60.0, rec, sink, store).run(0.0)
        assertEquals(true, rec.done.last().first)
        val ko = Subtitles.parseSrt(sink.files["movie.ko.srt"]!!)
        assertTrue(ko.size >= 15)
        assertTrue(ko.all { it.text.startsWith("번역:") })
        assertTrue(ko.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(sink.files.containsKey("movie.en.srt"))
        val st = JobState.fromJson(store.saved!!)
        assertTrue(st.complete && st.transcribed)
        assertEquals("en", st.language)
    }

    @Test
    fun startsNearPlaybackPositionThenFillsRest() {
        val rec = Recorder(); val sink = MemorySink()
        engine(300.0, rec, sink, translator = null, opts = EngineOptions(translatorKind = "none")).run(150.0)
        val first = rec.actives.first()
        assertTrue("first region $first", first.first in 145.0..151.0)
        assertTrue(rec.done.last().first)
        val starts = rec.units.map { it.start }.sorted()
        assertTrue(starts.first() < 5 && starts.last() > 290)
        assertFalse(sink.files.containsKey("movie.ko.srt"))
        assertTrue(sink.files.containsKey("movie.en.srt"))
    }

    @Test
    fun resumeDoesNotRedoCoveredParts() {
        val store = MemoryStore()
        val rec1 = Recorder()
        val asr = FakeAsr(delayMs = 5)
        val e1 = engine(240.0, rec1, store = store, asr = asr)
        val t = Thread { e1.run(0.0) }
        t.start()
        while (rec1.units.size < 10) Thread.sleep(10)
        e1.cancel(); t.join()
        val partial = JobState.fromJson(store.saved!!)
        assertTrue(partial.covered.total() in 1.0..239.0)
        val rec2 = Recorder()
        engine(240.0, rec2, store = store).run(0.0)
        val final = JobState.fromJson(store.saved!!)
        assertTrue(final.complete)
        val starts = final.units.values.map { it.start }.sorted()
        assertTrue("duplicates", starts.zipWithNext().all { (a, b) -> b - a > 0.5 })
    }

    @Test
    fun translationFailureIsRetriedThenSavedAsSource() {
        SubtitleEngine.retryBaseMs = 10
        val rec = Recorder(); val sink = MemorySink()
        val tr = FakeTranslator(TranslatorException("네트워크 오류"))
        engine(20.0, rec, sink, translator = tr).run(0.0)
        assertFalse(rec.done.last().first)
        assertTrue(rec.done.last().second.contains("번역하지 못한"))
        assertTrue(tr.calls >= 2)
        assertTrue(Subtitles.parseSrt(sink.files["movie.ko.srt"]!!).all { it.text.startsWith("Fake sentence") })
    }

    @Test
    fun badApiKeyStopsTranslationOnly() {
        val rec = Recorder(); val store = MemoryStore()
        val tr = FakeTranslator(TranslatorException("Claude API 키가 올바르지 않습니다.", fatal = true))
        engine(20.0, rec, store = store, translator = tr).run(0.0)
        assertEquals(1, tr.calls)
        assertTrue(rec.errors.any { !it.second && it.first.contains("API 키") })
        assertTrue(JobState.fromJson(store.saved!!).transcribed)
    }

    @Test
    fun koreanSourceNeedsNoTranslation() {
        val rec = Recorder(); val sink = MemorySink()
        engine(15.0, rec, sink, opts = EngineOptions(language = "ko")).run(0.0)
        assertTrue(rec.done.last().first)
        assertTrue(Subtitles.parseSrt(sink.files["movie.ko.srt"]!!).all { it.text.startsWith("Fake sentence") })
        assertFalse(sink.files.keys.any { it.endsWith(".en.srt") })
    }

    @Test
    fun editAndRetranslateKeepUserText() {
        val rec = Recorder(); val sink = MemorySink(); val store = MemoryStore()
        val e = engine(15.0, rec, sink, store)
        e.run(0.0)
        val id = rec.units.minByOrNull { it.start }!!.id
        e.edit(id, "제가 고친 번역")
        assertTrue(sink.files["movie.ko.srt"]!!.contains("제가 고친 번역"))
        val rec2 = Recorder()
        engine(15.0, rec2, sink, store, retranslate = true).run(0.0)
        val st = JobState.fromJson(store.saved!!)
        assertEquals("제가 고친 번역", st.units[id]!!.ko)
        assertTrue(st.complete)
    }
}
