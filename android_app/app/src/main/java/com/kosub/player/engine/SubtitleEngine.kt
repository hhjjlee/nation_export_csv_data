package com.kosub.player.engine

import com.kosub.player.core.Asr
import com.kosub.player.core.AudioSource
import com.kosub.player.core.EngineListener
import com.kosub.player.core.EngineOptions
import com.kosub.player.core.JobState
import com.kosub.player.core.Languages
import com.kosub.player.core.Segment
import com.kosub.player.core.Segmentation
import com.kosub.player.core.StateStore
import com.kosub.player.core.SubUnit
import com.kosub.player.core.SubtitleSink
import com.kosub.player.core.Subtitles
import com.kosub.player.core.Translator
import com.kosub.player.core.TranslatorException
import com.kosub.player.core.Word
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 자막 생성 작업 (데스크톱 앱과 같은 방식).
 * - 재생 위치(또는 이동한 위치)부터 약 30초 구간씩 음성 인식한다.
 * - 구간 끝은 말소리가 없는 지점에 맞춰 문장이 잘리지 않게 한다.
 * - 문장 단위로 번역하고, 몇 초마다 작업 상태와 .srt 파일을 저장한다.
 */
class SubtitleEngine(
    private val state: JobState,
    private val audio: AudioSource,
    private val asrProvider: () -> Asr,
    private val translatorProvider: () -> Translator?,
    private val sink: SubtitleSink,
    private val store: StateStore,
    private val options: EngineOptions,
    private val listener: EngineListener,
    private val retranslate: Boolean = false,
    private val clock: () -> Long = { System.nanoTime() },
) {
    companion object {
        const val SR = 16000
        const val REGION_SECONDS = 27.0 // Whisper 한 번에 30초까지 처리
        const val SAVE_INTERVAL_MS = 3000L
        const val MAX_TRANSLATE_ATTEMPTS = 6
        var retryBaseMs = 5000L
        const val SILENCE_RMS = 0.0025
    }

    private val lock = Object()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var pos = 0.0
    @Volatile private var posVersion = 0
    @Volatile private var transcribing = false
    @Volatile private var asr: Asr? = null
    private var translator: Translator? = null
    private var translatorError: String? = null
    private var translateThread: Thread? = null
    private val queue = ArrayList<Int>()
    private val retry = HashMap<Int, Long>() // id → 다시 시도할 시각(ms)
    private val attempts = HashMap<Int, Int>()
    private var detections = 0
    private var dirty = false
    private var lastSaveMs = 0L
    private var lastCoverageMs = 0L
    private var speed: Double? = null
    private var activeRegion: Pair<Double, Double>? = null
    private var currentRegion: Pair<Double, Double>? = null

    fun setPosition(seconds: Double) {
        synchronized(lock) {
            pos = max(0.0, seconds)
            posVersion++
            lock.notifyAll()
        }
        if (shouldAbortCurrent()) asr?.abort()
    }

    fun cancel() {
        cancelled.set(true)
        asr?.abort()
        synchronized(lock) { lock.notifyAll() }
    }

    val isCancelled: Boolean get() = cancelled.get()

    fun edit(unitId: Int, text: String) {
        val unit: SubUnit
        synchronized(lock) {
            unit = state.units[unitId] ?: return
            val clean = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            unit.ko = clean.ifEmpty { null }
            unit.edited = clean.isNotEmpty()
            dirty = true
        }
        listener.onUnits(listOf(unit.copy()))
        save(force = true)
    }

    private fun nowMs(): Long = clock() / 1_000_000

    // ------------------------------------------------------------------
    /** 작업 스레드에서 호출: 끝까지 처리하거나 취소될 때까지 돌아온다. */
    fun run(initialPosition: Double = 0.0) {
        pos = initialPosition
        try {
            runInner()
        } catch (e: Throwable) {
            fail("자막 생성 중 오류가 발생했습니다: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            synchronized(lock) {
                transcribing = false
                lock.notifyAll()
            }
            if (cancelled.get()) translateThread?.join(5000)
            save(force = true)
        }
    }

    private fun fail(text: String) {
        listener.onError(text, fatal = true)
        cancelled.set(true)
        synchronized(lock) { lock.notifyAll() }
    }

    private fun runInner() {
        listener.onStatus("prepare", "자막 작업 준비 중")
        if (options.language != "auto") {
            state.language = options.language
            state.languageProb = 1.0
            state.languageLocked = true
        }
        audio.durationSec?.let { state.duration = it }
        if (retranslate && state.language != "ko") {
            synchronized(lock) {
                state.units.values.filter { !it.edited }.forEach { it.ko = null }
                state.complete = false
                dirty = true
            }
        }
        listener.onState(state)
        if (state.complete) {
            if (state.outputs.isEmpty()) {
                synchronized(lock) { dirty = true }
                save(force = true)
            }
            listener.onDone(true, "이미 만들어 둔 자막을 불러왔습니다.")
            return
        }
        val needAsr = !state.transcribed
        synchronized(lock) { transcribing = needAsr }
        try {
            ensureTranslator()
            if (needAsr) transcribeAll()
        } finally {
            synchronized(lock) {
                transcribing = false
                lock.notifyAll()
            }
        }
        translateThread?.let { t -> while (t.isAlive && !cancelled.get()) t.join(500) }
        finalizeJob()
    }

    // ------------------------------------------------------------------
    // 음성 인식
    private fun transcribeAll() {
        val recognizer = try {
            asrProvider()
        } catch (e: Exception) {
            fail(e.message ?: "음성 인식 모델을 준비하지 못했습니다.")
            return
        }
        asr = recognizer
        state.model = recognizer.modelName
        listener.onStatus("transcribing", "음성 인식 중")
        while (!cancelled.get()) {
            val region = pickRegion() ?: break
            processRegion(recognizer, region)
            save()
        }
    }

    private data class Region(
        val start: Double, val end: Double, val snapStart: Boolean, val snapEnd: Boolean,
        val gapLo: Double, val gapHi: Double, val posVersion: Int,
    )

    private fun gaps(): List<Pair<Double, Double>> = state.covered.gaps(0.0, state.duration ?: 1e9, 0.5)

    private fun pickRegion(): Region? {
        val gaps: List<Pair<Double, Double>>
        val p: Double
        val pv: Int
        synchronized(lock) {
            gaps = gaps()
            if (gaps.isEmpty()) return null
            p = min(pos, state.duration ?: 1e9)
            pv = posVersion
        }
        val (lo, hi) = gaps.firstOrNull { it.second > p + 1.0 } ?: gaps[0]
        var start = lo
        var snapStart = false
        if (lo < p && p < hi && p - lo > 10.0) {
            start = p - 2.0
            snapStart = true
        }
        var end = min(hi, start + REGION_SECONDS)
        var snapEnd = end < hi
        if (snapEnd && hi - end < 5.0) {
            end = hi; snapEnd = false
        }
        return Region(start, end, snapStart, snapEnd, lo, hi, pv)
    }

    private fun processRegion(recognizer: Asr, r: Region) {
        val readLo = if (r.snapStart) max(r.gapLo, r.start - 3.0) else r.start
        val readHi = if (r.snapEnd) min(r.gapHi, r.end + 2.5) else r.end
        synchronized(lock) { currentRegion = r.start to r.end; activeRegion = r.start to r.start }
        emitCoverage(force = true)
        val pcm = audio.read(readLo, readHi)
        val gotEnd = readLo + pcm.size / SR.toDouble()
        var endTime = r.end
        var snapEnd = r.snapEnd
        if (gotEnd < readHi - 0.5) {
            synchronized(lock) {
                val known = state.duration
                if (known == null || known > 1e8 || gotEnd >= known - 5.0) {
                    state.duration = gotEnd
                } else {
                    state.covered.add(max(r.start, gotEnd), r.end) // 읽을 수 없는 부분은 건너뛴다
                }
                dirty = true
            }
            endTime = min(r.end, gotEnd)
            snapEnd = false
        }
        if (endTime - r.start < 0.3) return

        var startTime = r.start
        if (r.snapStart) startTime = readLo + AudioMath.findQuietPoint(pcm, max(0.0, r.start - 3.0 - readLo), r.start + 1.0 - readLo)
        if (snapEnd) endTime = readLo + AudioMath.findQuietPoint(pcm, r.end - 5.0 - readLo, min(gotEnd, r.end + 2.5) - readLo)
        if (endTime - startTime < 1.0) {
            startTime = r.start; endTime = min(r.end, gotEnd)
        }
        val from = ((startTime - readLo) * SR).toInt().coerceIn(0, pcm.size)
        val to = ((endTime - readLo) * SR).toInt().coerceIn(from, pcm.size)
        val chunk = pcm.copyOfRange(from, to)

        if (AudioMath.rms(chunk) < SILENCE_RMS) {
            // 조용한 구간은 인식하지 않고 넘어간다 (속도 향상, 환각 방지)
            synchronized(lock) { state.covered.add(startTime, endTime); dirty = true }
            emitCoverage(force = true)
            return
        }
        val knownLang = synchronized(lock) {
            if (state.languageLocked || (state.language != null && state.languageProb >= 0.8)) state.language else null
        }
        val t0 = nowMs()
        listener.onStatus("transcribing", "음성 인식 중")
        val result = recognizer.transcribe(chunk, knownLang)
        if (result.aborted || cancelled.get()) {
            synchronized(lock) { activeRegion = null; currentRegion = null }
            emitCoverage(force = true)
            return // 이 구간은 나중에 다시 처리
        }
        val regionLang = knownLang ?: result.language
        if (knownLang == null && result.language != null) updateLanguage(result.language, result.languageProb)

        val segments = result.segments.mapNotNull { shift(it, startTime, endTime) }
            .filter { !Segmentation.isHallucination(it) }
        // 문장 끝 / 긴 쉼 기준으로 묶어서 번역 단위로 만든다
        var pending = ArrayList<Segment>()
        for (seg in segments) {
            if (pending.isNotEmpty() && seg.start - pending.last().end > 1.5) {
                flush(pending, regionLang); pending = ArrayList()
            }
            pending.add(seg)
            if (Segmentation.endsSentence(seg.text) || pending.size >= 8) {
                flush(pending, regionLang); pending = ArrayList()
            }
        }
        if (pending.isNotEmpty()) flush(pending, regionLang)
        val elapsed = max(1L, nowMs() - t0)
        synchronized(lock) {
            state.covered.add(startTime, endTime)
            dirty = true
            speed = (endTime - startTime) / (elapsed / 1000.0)
            activeRegion = null
            currentRegion = null
        }
        emitCoverage(force = true)
    }

    /** 처리 중인 구간 밖의, 자막이 없는 곳으로 재생 위치가 옮겨 갔으면 지금 인식을 멈춘다. */
    private fun shouldAbortCurrent(): Boolean {
        synchronized(lock) {
            val (s, e) = currentRegion ?: return false
            val p = pos
            if (p >= s - 1.0 && p <= e) return false
            val need = if (state.covered.contains(p)) {
                state.covered.gaps(p, state.duration ?: 1e9, 0.5).firstOrNull()?.first ?: return false
            } else p
            if (need >= s - 1.0 && need <= e) return false
            return need - p < 25.0
        }
    }

    private fun shift(seg: Segment, offset: Double, limit: Double): Segment? {
        val start = seg.start + offset
        if (start >= limit) return null
        val end = min(seg.end + offset, limit + 0.5)
        val words = seg.words?.map { Word(it.start + offset, min(it.end + offset, limit + 0.5), it.text) }
        return Segment(start, max(end, start), seg.text, words, seg.avgLogprob)
    }

    private fun updateLanguage(code: String, prob: Double) {
        var changed = false
        synchronized(lock) {
            detections++
            if (!state.languageLocked && (state.language == null || prob > state.languageProb)) {
                changed = state.language != code
                state.language = code
                state.languageProb = prob
            }
            if (state.languageProb >= 0.8 || detections >= 3) state.languageLocked = true
            dirty = true
        }
        if (changed) listener.onLanguage(code, prob)
        ensureTranslator()
    }

    private fun flush(segments: List<Segment>, lang: String?) {
        val drafts = Segmentation.buildUnits(segments, lang)
        if (drafts.isEmpty()) return
        val created = ArrayList<SubUnit>()
        synchronized(lock) {
            for (d in drafts) {
                val u = SubUnit(state.nextId++, d.start, d.end, d.text, parts = d.parts)
                if (lang == "ko") u.ko = u.text
                state.units[u.id] = u
                created.add(u.copy())
                if (lang != "ko" && translator != null) queue.add(u.id)
            }
            dirty = true
            lock.notifyAll()
        }
        listener.onUnits(created)
    }

    // ------------------------------------------------------------------
    // 번역
    private fun ensureTranslator() {
        synchronized(lock) {
            if (translator != null || translatorError != null) return
            val lang = state.language ?: return
            if (lang == "ko" || options.translatorKind == "none") return
        }
        val tr = try {
            translatorProvider()
        } catch (e: TranslatorException) {
            synchronized(lock) { translatorError = e.message }
            listener.onError(e.message ?: "번역기를 준비하지 못했습니다.", fatal = false)
            return
        } ?: return
        synchronized(lock) {
            if (translator != null) return
            translator = tr
            state.translator = tr.name
            state.untranslated().sortedBy { it.start }.forEach { if (it.id !in queue) queue.add(it.id) }
            translateThread = Thread({ translationLoop(tr) }, "translate").apply { isDaemon = true; start() }
            lock.notifyAll()
        }
    }

    private fun readyIds(): List<Int> {
        val now = nowMs()
        val due = retry.filterValues { it <= now }.keys
        val ids = (queue + due).filter { it in state.units }.toSet()
        val p = pos
        return ids.sortedWith(compareBy({ if (state.units[it]!!.end >= p - 5.0) 0 else 1 }, { state.units[it]!!.start }))
    }

    private fun translationLoop(tr: Translator) {
        while (!cancelled.get()) {
            val batch: List<SubUnit>
            val lang: String
            val context: List<Pair<String, String>>
            synchronized(lock) {
                var ready = readyIds()
                while (ready.isEmpty()) {
                    if (cancelled.get()) return
                    if (!transcribing && queue.isEmpty() && retry.isEmpty()) return
                    lock.wait(500)
                    ready = readyIds()
                }
                val ids = ready.take(tr.batchSize)
                ids.forEach { queue.remove(it); retry.remove(it) }
                batch = ids.map { state.units[it]!! }
                lang = state.language ?: ""
                context = contextBefore(batch[0])
            }
            listener.onStatus("translating", "번역 중 (${tr.label})")
            val results: List<String?> = try {
                tr.translate(batch.map { it.text }, lang, context)
            } catch (e: TranslatorException) {
                if (e.fatal) {
                    synchronized(lock) {
                        translatorError = e.message
                        queue.clear(); retry.clear()
                    }
                    listener.onError(e.message ?: "번역 설정을 확인하세요.", fatal = false)
                    return
                }
                listener.onStatus("translate_wait", "번역 실패, 잠시 후 다시 시도합니다: ${e.message}")
                synchronized(lock) { batch.forEach { scheduleRetry(it.id, MAX_TRANSLATE_ATTEMPTS) } }
                continue
            } catch (e: Exception) {
                listener.onStatus("translate_wait", "번역 실패, 잠시 후 다시 시도합니다: ${e.message}")
                synchronized(lock) { batch.forEach { scheduleRetry(it.id, MAX_TRANSLATE_ATTEMPTS) } }
                continue
            }
            val updated = ArrayList<SubUnit>()
            synchronized(lock) {
                for ((u, ko) in batch.zip(results)) {
                    if (u.edited) continue
                    if (!ko.isNullOrBlank()) {
                        u.ko = ko
                        updated.add(u.copy())
                    } else scheduleRetry(u.id, 3)
                }
                dirty = true
            }
            if (updated.isNotEmpty()) listener.onUnits(updated)
            save()
        }
    }

    private fun scheduleRetry(id: Int, limit: Int) {
        val n = (attempts[id] ?: 0) + 1
        attempts[id] = n
        if (n >= limit) return
        retry[id] = nowMs() + min(60_000L, retryBaseMs * (1L shl (n - 1)))
    }

    private fun contextBefore(unit: SubUnit): List<Pair<String, String>> =
        state.units.values.filter { it.end <= unit.start + 0.01 && !it.ko.isNullOrBlank() }
            .sortedBy { it.start }.takeLast(4).map { it.text to it.ko!! }

    // ------------------------------------------------------------------
    private fun finalizeJob() {
        if (cancelled.get()) return
        val missing: Int
        val count: Int
        synchronized(lock) {
            state.transcribed = state.duration != null && gaps().isEmpty()
            val needTranslation = state.language != null && state.language != "ko" && options.translatorKind != "none"
            missing = if (needTranslation) state.untranslated().size else 0
            state.complete = state.transcribed && missing == 0
            count = state.units.size
            dirty = true
        }
        save(force = true)
        val message = when {
            count == 0 -> "음성이 감지되지 않아 자막을 만들지 않았습니다."
            state.complete -> "자막 완성 (${count}개) — 저장했습니다."
            missing > 0 -> "번역하지 못한 자막 ${missing}개는 원문으로 저장했습니다 (다시 열면 이어서 번역)."
            else -> "자막을 저장했습니다."
        }
        listener.onDone(state.complete, message)
    }

    /** 몇 초마다 작업 상태와 자막 파일을 저장한다. */
    fun save(force: Boolean = false) {
        val files: List<Pair<String, String>>
        synchronized(lock) {
            if (!dirty) return
            val now = nowMs()
            if (!force && now - lastSaveMs < SAVE_INTERVAL_MS) return
            dirty = false
            lastSaveMs = now
            files = buildOutputs()
        }
        val written = ArrayList<String>()
        for ((name, content) in files) {
            try {
                written.add(sink.write(name, content))
            } catch (e: Exception) {
                listener.onError("자막 파일을 저장하지 못했습니다: ${e.message}", fatal = false)
            }
        }
        synchronized(lock) {
            if (written.isNotEmpty()) state.outputs = written
            try {
                store.save(state)
            } catch (e: Exception) {
                listener.onError("작업 기록을 저장하지 못했습니다: ${e.message}", fatal = false)
            }
        }
        if (written.isNotEmpty()) listener.onSaved(written)
    }

    private fun buildOutputs(): List<Pair<String, String>> {
        val units = state.units.values.toList()
        if (units.isEmpty()) return emptyList()
        val lang = state.language ?: "und"
        val stem = state.videoName.substringBeforeLast('.').ifEmpty { "video" }
        val translate = options.translatorKind != "none"
        val out = ArrayList<Pair<String, String>>()
        if (lang == "ko") {
            out.add("$stem.ko.srt" to Subtitles.cuesToSrt(Subtitles.buildCues(units, "src"), "src", lang))
        } else {
            if (translate) out.add("$stem.ko.srt" to Subtitles.cuesToSrt(Subtitles.buildCues(units, "ko"), "ko", lang))
            if (options.saveSource || !translate) {
                out.add("$stem.$lang.srt" to Subtitles.cuesToSrt(Subtitles.buildCues(units, "src"), "src", lang))
            }
            if (options.saveBilingual && translate) {
                out.add("$stem.ko+$lang.srt" to Subtitles.cuesToSrt(Subtitles.buildCues(units, "ko"), "both", lang))
            }
        }
        return out
    }

    private fun emitCoverage(force: Boolean = false) {
        val now = nowMs()
        if (!force && now - lastCoverageMs < 300) return
        lastCoverageMs = now
        val intervals: List<Pair<Double, Double>>
        val active: Pair<Double, Double>?
        synchronized(lock) {
            intervals = state.covered.toList()
            active = currentRegion
        }
        listener.onCoverage(intervals, active, state.duration, speed)
    }

    fun languageName(): String = Languages.name(state.language)
}

/** 오디오 계산 도우미 (무음 지점 찾기, 음량). */
object AudioMath {
    fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size)
    }

    /** audio 안의 [lo, hi] 초 범위에서 가장 조용한 0.3초의 가운데(초). */
    fun findQuietPoint(audio: FloatArray, lo: Double, hi: Double, sr: Int = 16000): Double {
        val loI = max(0, (lo * sr).toInt())
        val hiI = min(audio.size, (hi * sr).toInt())
        if (hiI - loI < sr / 2) return (lo + hi) / 2
        val frame = 320
        val n = (hiI - loI) / frame
        if (n < 5) return (lo + hi) / 2
        val rms = DoubleArray(n)
        for (f in 0 until n) {
            var s = 0.0
            val base = loI + f * frame
            for (k in 0 until frame) {
                val v = audio[base + k]
                s += v * v
            }
            rms[f] = sqrt(s / frame)
        }
        val k = 15
        var best = 0
        var bestVal = Double.MAX_VALUE
        for (f in 0 until n) {
            val a = max(0, f - k / 2)
            val b = min(n, f + k / 2 + 1)
            var s = 0.0
            for (j in a until b) s += rms[j]
            val avg = s / (b - a)
            if (avg < bestVal) {
                bestVal = avg; best = f
            }
        }
        return (loI + best * frame + frame / 2) / sr.toDouble()
    }
}
