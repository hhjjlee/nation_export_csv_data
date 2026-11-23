package app.fxcalc.calc

import java.math.BigDecimal

/** 문자열 안에서 찾은 가격 하나 */
data class ParsedPrice(val value: BigDecimal, val start: Int, val end: Int)

/**
 * 가격표 사진에서 인식한 글자에서 숫자(가격)를 찾는다.
 *  - 35.000 / 35,000 / 3.500.000 / 3 500 000 → 천 단위 구분
 *  - 1,234.56 / 1.234,56 / 12.99 / 12,5 → 소수점
 *  - 35k → 35,000 (베트남 등에서 흔한 표기)
 *  - 시간(12:30), 날짜(22/11), 전화번호(090-123), 퍼센트(50%), 모델명(A4) 은 건너뛴다.
 */
object PriceParser {
    private const val MAX_DIGITS = 12

    private val pattern = Regex(
        "(?<![A-Za-z0-9:/\\-.,])" +
            "(\\d{1,3}(?:[ \\u00A0]\\d{3})+(?:[.,]\\d{1,2})?|\\d[\\d.,]*\\d|\\d)" +
            "(?:[ ]?([kK])(?![A-Za-z]))?" +
            "(?![0-9:/\\-%]|[.,]\\d)"
    )

    fun parse(text: String): List<ParsedPrice> {
        val out = ArrayList<ParsedPrice>()
        for (m in pattern.findAll(text)) {
            val raw = m.groupValues[1]
            val digits = raw.count { it.isDigit() }
            if (digits > MAX_DIGITS) continue
            var v = normalize(raw) ?: continue
            if (m.groupValues[2].isNotEmpty()) v = v.multiply(BigDecimal(1000))
            if (v.signum() <= 0) continue
            out += ParsedPrice(v, m.range.first, m.range.last + 1)
        }
        return out
    }

    /** 구분기호가 섞인 숫자 문자열을 BigDecimal 로 바꾼다. */
    fun normalize(raw: String): BigDecimal? {
        val s = raw.replace(" ", "").replace(" ", "").trimEnd('.', ',')
        if (s.isEmpty()) return null
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        val cleaned = when {
            dots == 0 && commas == 0 -> s
            dots > 0 && commas > 0 -> {
                // 마지막에 나오는 기호가 소수점
                val decimal = if (s.lastIndexOf('.') > s.lastIndexOf(',')) '.' else ','
                val group = if (decimal == '.') ',' else '.'
                if (s.count { it == decimal } > 1) return null
                s.replace(group.toString(), "").replace(decimal, '.')
            }
            else -> {
                val sep = if (dots > 0) '.' else ','
                val count = dots + commas
                val afterLast = s.length - s.lastIndexOf(sep) - 1
                val intPart = s.substringBefore(sep)
                val isGrouping = count > 1 || (afterLast == 3 && intPart != "0")
                if (isGrouping) {
                    // 천 단위 묶음은 모두 3자리여야 한다.
                    val groups = s.split(sep)
                    if (groups.drop(1).any { it.length != 3 } || groups[0].length > 3) return null
                    s.replace(sep.toString(), "")
                } else {
                    s.replace(sep, '.')
                }
            }
        }
        return cleaned.toBigDecimalOrNull()
    }
}
