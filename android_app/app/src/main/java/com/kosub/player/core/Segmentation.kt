package com.kosub.player.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Whisper 출력 단어 (영어 등은 앞에 공백 포함). */
data class Word(var start: Double, var end: Double, val text: String)

data class Segment(
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<Word>? = null,
    val avgLogprob: Double = 0.0,
)

data class DraftUnit(val start: Double, val end: Double, val text: String, val parts: List<SubUnit.Part>?)

/**
 * 음성 인식 결과를 문장 단위 자막으로 다시 나눈다 (데스크톱 앱과 같은 규칙).
 * - 문장 끝(. ? ! 。 등)과 긴 쉼에서 끊고, 너무 짧은 문장은 이웃과 합친다.
 * - 7초나 글자 수 제한을 넘는 문장은 쉼/쉼표에서 여러 자막 줄(parts)로 나눈다.
 */
object Segmentation {
    const val MAX_CUE_DURATION = 7.0
    const val MAX_CHARS_SPACED = 84
    const val MAX_CHARS_NOSPACE = 34
    private const val PAUSE_BREAK = 1.2
    private const val MIN_PART_DURATION = 0.8

    private const val SENTENCE_END = ".?!…。？！‼⁇⁈⁉"
    private const val CLOSERS = "\"'”’)]}」』】》〉»"
    private const val OPENERS = "\"'“‘([{¿¡«「『【《〈"
    private const val SOFT_BREAK = ",;:，、；：—–"
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "u.s", "u.k",
        "mt", "lt", "col", "gen", "sgt", "capt", "no", "vol", "fig", "approx", "dept", "est",
        "sra", "srta", "hr", "fr", "nr", "bzw", "usw", "z.b",
    )
    private val CONJUNCTIONS_EN = setOf(
        "and", "but", "so", "because", "which", "that", "or", "if", "when", "while", "although",
        "though", "where", "who", "then", "until", "unless", "since", "after", "before",
    )
    private val HALLUCINATIONS = listOf(
        "thank you for watching", "thanks for watching", "thank you so much for watching",
        "please subscribe", "subscribe to my channel", "like and subscribe",
        "subtitles by the amara org community", "transcription by castingwords",
        "ご視聴ありがとうございました", "チャンネル登録お願いします",
        "字幕由amaraorg社区提供", "请不吝点赞订阅转发打赏支持明镜与点点栏目",
        "mbc 뉴스 이덕영입니다", "시청해주셔서 감사합니다", "시청해 주셔서 감사합니다",
        "구독과 좋아요 부탁드립니다", "구독 좋아요 알림설정 부탁드립니다",
    ).map { normalize(it) }.flatMap { listOf(it, it.replace(" ", "")) }.toSet()

    private fun noSpace(lang: String?) = (lang ?: "") in Languages.NO_SPACE

    fun limits(lang: String?): Pair<Double, Int> =
        MAX_CUE_DURATION to (if (noSpace(lang)) MAX_CHARS_NOSPACE else MAX_CHARS_SPACED)

    private fun normalize(text: String): String {
        val t = text.lowercase().replace(Regex("[\\s.,!?。！？、，…·'\"“”‘’\\-–—:;()\\[\\]{}♪]+"), " ")
        return t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** 무음·음악 구간에서 자주 지어내는 문장 (신뢰도가 낮을 때만 버림). */
    fun isHallucination(seg: Segment): Boolean {
        val norm = normalize(seg.text)
        if (norm.isEmpty()) return true
        if (norm in HALLUCINATIONS || norm.replace(" ", "") in HALLUCINATIONS) return seg.avgLogprob < -0.5
        return false
    }

    fun endsSentence(text: String): Boolean {
        val t = text.trim().trimEnd { it in CLOSERS }
        return t.isNotEmpty() && t.last() in SENTENCE_END
    }

    fun joinWords(words: List<Word>, lang: String?): String {
        val text = words.joinToString("") { it.text }
        return if (noSpace(lang)) text.trim() else text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun duration(words: List<Word>): Double =
        if (words.isEmpty()) 0.0 else words.maxOf { it.end } - words[0].start

    private fun pseudoWords(seg: Segment, lang: String?): List<Word> {
        val text = seg.text.trim()
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>()
        if (noSpace(lang)) {
            for (ch in text) {
                if (tokens.isNotEmpty() && (ch in SENTENCE_END || ch in SOFT_BREAK || ch in CLOSERS || ch.isWhitespace())) {
                    tokens[tokens.size - 1] = tokens.last() + ch
                } else tokens.add(ch.toString())
            }
        } else {
            text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { tokens.add(" $it") }
        }
        val total = tokens.sumOf { max(1, it.trim().length) }.toDouble()
        val span = max(seg.end - seg.start, 0.01)
        var cur = seg.start
        return tokens.map { tok ->
            val d = span * max(1, tok.trim().length) / total
            Word(cur, cur + d, tok).also { cur += d }
        }
    }

    fun segmentsToWords(segments: List<Segment>, lang: String?): List<Word> {
        val words = ArrayList<Word>()
        for (seg in segments) {
            val ws = seg.words?.filter { it.text.isNotBlank() }.orEmpty().ifEmpty { pseudoWords(seg, lang) }
            ws.forEach { words.add(Word(it.start, it.end, it.text)) }
        }
        for (i in 1 until words.size) {
            if (words[i].start < words[i - 1].start) words[i].start = words[i - 1].start
            if (words[i].end < words[i].start) words[i].end = words[i].start
        }
        return words
    }

    private fun isSentenceEnd(word: String, next: String?, lang: String?): Boolean {
        val t = word.trim().trimEnd { it in CLOSERS }
        if (t.isEmpty() || t.last() !in SENTENCE_END) return false
        if (noSpace(lang)) return true
        if (t.endsWith(".") && !t.endsWith("..")) {
            val core = t.trimEnd('.').trimStart { it in OPENERS }.lowercase()
            if (core in ABBREVIATIONS || (core.length == 1 && core[0].isLetter())) return false
        }
        if (next != null) {
            val nt = next.trim().trimStart { it in OPENERS }
            if (nt.isNotEmpty() && nt[0].isLetter() && nt[0].isLowerCase()) return false
        }
        return true
    }

    fun splitSentences(words: List<Word>, lang: String?): List<List<Word>> {
        val out = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for ((i, w) in words.withIndex()) {
            if (cur.isNotEmpty() && w.start - cur.last().end >= PAUSE_BREAK) {
                out.add(cur); cur = ArrayList()
            }
            cur.add(w)
            val next = words.getOrNull(i + 1)?.text
            if (isSentenceEnd(w.text, next, lang)) {
                out.add(cur); cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    fun mergeShort(sentences: List<List<Word>>, lang: String?): List<List<Word>> {
        val (maxDur, maxChars) = limits(lang)
        val shortChars = if (noSpace(lang)) 4 else 10
        fun isShort(ws: List<Word>) = duration(ws) < 1.0 || joinWords(ws, lang).length < shortChars
        fun fits(ws: List<Word>) = duration(ws) <= maxDur * 0.85 && joinWords(ws, lang).length <= maxChars * 0.8
        val result = ArrayList<List<Word>>()
        for (s in sentences) {
            if (result.isNotEmpty()) {
                val prev = result.last()
                val gap = s[0].start - prev.last().end
                if (gap <= 0.6 && (isShort(prev) || isShort(s)) && fits(prev + s)) {
                    result[result.size - 1] = prev + s
                    continue
                }
            }
            result.add(s)
        }
        return result
    }

    fun splitLong(words: List<Word>, lang: String?, depth: Int = 0): List<List<Word>> {
        val (maxDur, maxChars) = limits(lang)
        val textLen = joinWords(words, lang).length
        if ((duration(words) <= maxDur && textLen <= maxChars) || words.size < 2 || depth > 8) return listOf(words)
        var bestI = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 1 until words.size) {
            val left = words.subList(0, i)
            val right = words.subList(i, words.size)
            if (duration(left) < MIN_PART_DURATION || duration(right) < MIN_PART_DURATION) continue
            val pause = max(0.0, words[i].start - words[i - 1].end)
            val prev = words[i - 1].text.trim().trimEnd { it in CLOSERS }
            val punct = when {
                prev.isNotEmpty() && prev.last() in SENTENCE_END -> 3.0
                prev.isNotEmpty() && prev.last() in SOFT_BREAK -> 2.0
                lang == "en" && words[i].text.trim().lowercase() in CONJUNCTIONS_EN -> 1.0
                else -> 0.0
            }
            val leftLen = joinWords(left, lang).length
            val balance = 1.0 - abs(leftLen - (textLen - leftLen)) / max(textLen, 1).toDouble()
            val score = punct + 2.0 * min(pause, 1.0) + 1.5 * balance
            if (score > bestScore) {
                bestI = i; bestScore = score
            }
        }
        if (bestI < 0) return listOf(words)
        return splitLong(words.subList(0, bestI), lang, depth + 1) + splitLong(words.subList(bestI, words.size), lang, depth + 1)
    }

    /** 세그먼트 묶음을 번역 단위(문장) 목록으로 바꾼다. */
    fun buildUnits(segments: List<Segment>, lang: String?): List<DraftUnit> {
        val words = segmentsToWords(segments, lang)
        if (words.isEmpty()) return emptyList()
        val units = ArrayList<DraftUnit>()
        for (sent in mergeShort(splitSentences(words, lang), lang)) {
            val text = joinWords(sent, lang)
            if (text.isEmpty()) continue
            val pieces = splitLong(sent, lang)
            var parts: List<SubUnit.Part>? = null
            if (pieces.size > 1) {
                parts = pieces.mapNotNull { p ->
                    val t = joinWords(p, lang)
                    if (t.isEmpty()) null else SubUnit.Part(p[0].start, p.maxOf { it.end }, t)
                }.takeIf { it.size >= 2 }
            }
            units.add(DraftUnit(sent[0].start, sent.maxOf { it.end }, text, parts))
        }
        return units
    }
}
