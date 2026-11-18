package com.kosub.player.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 번역 단위(보통 한 문장). 너무 길면 parts로 여러 자막 줄에 나눠 표시한다. */
data class SubUnit(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    var ko: String? = null,
    val parts: List<Part>? = null,
    var edited: Boolean = false,
) {
    data class Part(val start: Double, val end: Double, val text: String)
}

/** 화면이나 SRT 파일에 표시되는 자막 한 덩어리. */
data class Cue(
    val start: Double,
    var end: Double,
    val text: String,
    val src: String = "",
    val unitId: Int = -1,
    val translated: Boolean = true,
)

object Subtitles {
    const val KO_LINE = 22
    const val LATIN_LINE = 42
    const val CJK_LINE = 18
    private const val CPS_KO = 14.0
    private const val CPS_LATIN = 17.0
    private const val MIN_DURATION = 0.8
    private const val LINGER = 0.3
    private const val MIN_GAP = 0.05
    private const val SPLIT_AFTER = ".?!…,;:。？！，、；："

    /** 번역문을 원문 조각 길이 비율대로 나눈다. 빈 조각이 생기면 null. */
    fun splitByWeights(text: String, weights: List<Int>): List<String>? {
        val n = weights.size
        val clean = text.trim().split(Regex("\\s+")).joinToString(" ")
        if (n <= 1) return listOf(clean)
        var remaining = weights.sum().toDouble().coerceAtLeast(1.0)
        var rest = clean
        val pieces = ArrayList<String>()
        for (i in 0 until n - 1) {
            val frac = if (remaining > 0) weights[i] / remaining else 1.0 / (n - i)
            remaining -= weights[i]
            val cut = bestCut(rest, frac * rest.length)
            val left = rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
            if (left.isEmpty() || rest.isEmpty()) return null
            pieces.add(left)
        }
        pieces.add(rest)
        return pieces
    }

    /** ideal 근처의 공백/문장부호 뒤를 자를 위치로 고른다. */
    fun bestCut(text: String, ideal: Double): Int {
        if (text.length < 2) return text.length
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in 1 until text.length) {
            val bonus = when {
                text[i] == ' ' -> if (text[i - 1] in SPLIT_AFTER) 0.6 else 0.0
                text[i - 1] in SPLIT_AFTER -> 0.3
                else -> continue
            }
            val score = abs(i - ideal) / max(text.length, 1) - bonus * 0.15
            if (score < bestScore) {
                best = i; bestScore = score
            }
        }
        if (best < 0) best = ideal.roundToInt()
        return best.coerceIn(1, text.length - 1)
    }

    fun wrapText(text: String, maxLen: Int, spaced: Boolean = true, maxLines: Int = 3): String {
        val t = if (spaced) text.trim().split(Regex("\\s+")).joinToString(" ") else text.trim()
        if (t.length <= maxLen) return t
        val lines = min(maxLines, ceil(t.length / maxLen.toDouble()).toInt())
        return balancedSplit(t, lines, spaced).joinToString("\n")
    }

    fun balancedSplit(text: String, lines: Int, spaced: Boolean): List<String> {
        if (lines <= 1 || text.length < 2) return listOf(text)
        val ideal = text.length / lines.toDouble()
        val cut: Int = if (spaced) {
            bestCut(text, ideal)
        } else {
            var found = -1
            val window = max(2, (ideal * 0.3).toInt())
            loop@ for (d in 0..window) {
                for (i in intArrayOf(ideal.toInt() + d, ideal.toInt() - d)) {
                    if (i > 0 && i < text.length && text[i - 1] in SPLIT_AFTER) {
                        found = i; break@loop
                    }
                }
            }
            if (found < 0) ideal.roundToInt().coerceIn(1, text.length - 1) else found
        }
        val left = text.substring(0, cut).trimEnd()
        val right = text.substring(cut).trimStart()
        if (left.isEmpty() || right.isEmpty()) return listOf(text)
        return listOf(left) + balancedSplit(right, lines - 1, spaced)
    }

    /** mode: "ko" = 한국어(번역 전이면 원문), "src" = 원문 */
    fun buildCues(units: Collection<SubUnit>, mode: String = "ko"): List<Cue> {
        val cues = ArrayList<Cue>()
        for (u in units.sortedWith(compareBy({ it.start }, { it.id }))) {
            val ko = u.ko?.trim()?.takeIf { it.isNotEmpty() }
            val translated = ko != null
            val parts = u.parts?.takeIf { it.size > 1 }
            if (parts != null) {
                val srcs = parts.map { it.text }
                val texts: List<String>? = when {
                    mode == "src" -> srcs
                    ko != null -> splitByWeights(ko, srcs.map { max(1, it.length) })
                    else -> srcs
                }
                if (texts != null) {
                    for (i in parts.indices) {
                        cues.add(Cue(parts[i].start, parts[i].end, texts[i], srcs[i], u.id, translated))
                    }
                    continue
                }
            }
            val text = if (mode == "src") u.text else (ko ?: u.text)
            cues.add(Cue(u.start, u.end, text, u.text, u.id, translated))
        }
        polish(cues, korean = mode != "src")
        return cues
    }

    private fun readingTime(text: String, korean: Boolean): Double =
        min(6.0, text.length / (if (korean) CPS_KO else CPS_LATIN))

    private fun polish(cues: MutableList<Cue>, korean: Boolean) {
        for (i in cues.indices) {
            val c = cues[i]
            val next = if (i + 1 < cues.size) cues[i + 1].start else c.end + 30.0
            if (c.end > next - MIN_GAP) c.end = max(c.start + 0.2, next - MIN_GAP)
            val want = max(c.end + LINGER, c.start + max(MIN_DURATION, readingTime(c.text, korean)))
            c.end = max(c.end, min(want, next - MIN_GAP))
        }
    }

    fun srtTime(t: Double): String {
        val ms = max(0L, (t * 1000).roundToLong())
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    fun formatClock(t: Double): String {
        val total = max(0, t.toInt())
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** mode: "ko"(한국어), "src"(원문), "both"(한국어 + 원문) */
    fun cuesToSrt(cues: List<Cue>, mode: String = "ko", srcLang: String? = null): String {
        val spaced = Languages.usesSpaces(srcLang)
        val srcLine = if (spaced) LATIN_LINE else CJK_LINE
        val sb = StringBuilder()
        var n = 0
        for (c in cues) {
            val body = when (mode) {
                "src" -> wrapText(c.src.ifEmpty { c.text }, srcLine, spaced)
                "both" -> {
                    var b = wrapText(c.text, KO_LINE)
                    if (c.src.isNotEmpty() && c.src != c.text) b += "\n" + wrapText(c.src, srcLine, spaced)
                    b
                }
                else -> wrapText(c.text, KO_LINE)
            }
            if (body.isBlank()) continue
            n++
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(n).append("\n").append(srtTime(c.start)).append(" --> ").append(srtTime(c.end)).append("\n")
            sb.append(body).append("\n")
        }
        return sb.toString()
    }

    private val TIME_RE = Regex(
        "(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})"
    )

    fun parseSrt(raw: String): List<Cue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n")
        val cues = ArrayList<Cue>()
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            for ((idx, line) in lines.withIndex()) {
                val m = TIME_RE.find(line) ?: continue
                val g = m.groupValues
                val start = g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt() + g[4].padEnd(3, '0').toInt() / 1000.0
                val end = g[5].toInt() * 3600 + g[6].toInt() * 60 + g[7].toInt() + g[8].padEnd(3, '0').toInt() / 1000.0
                val body = lines.drop(idx + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
                if (body.isNotEmpty()) cues.add(Cue(start, end, body, "", cues.size + 1, true))
                break
            }
        }
        return cues.sortedBy { it.start }
    }

    fun cuesToUnits(cues: List<Cue>): List<SubUnit> = cues.mapIndexed { i, c ->
        val flat = c.text.split(Regex("\\s+")).joinToString(" ")
        SubUnit(i + 1, c.start, c.end, flat, flat)
    }
}
