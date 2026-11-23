package app.fxcalc.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** 키패드에서 들어오는 입력 */
sealed interface Key {
    data class Digit(val d: Char) : Key
    data object Dot : Key
    data class Op(val op: Char) : Key // '+', '-', '*', '/'
    data object Equals : Key
    data object Clear : Key
    data object Back : Key
}

/**
 * 입력 중인 식. [expr] 는 내부 표기(ASCII 연산자, 구분기호 없음)로 저장한다.
 * [fresh] 가 true 이면 다음 숫자 입력이 기존 값을 대체한다(= 직후, 행 전환 직후).
 */
data class CalcState(val expr: String = "0", val fresh: Boolean = true)

object Calculator {
    private const val OPS = "+-*/"
    private const val MAX_INT_DIGITS = 15
    private const val MAX_FRACTION_DIGITS = 8
    private const val MAX_EXPR_LEN = 60

    fun input(state: CalcState, key: Key): CalcState = when (key) {
        is Key.Digit -> digit(state, key.d)
        Key.Dot -> dot(state)
        is Key.Op -> op(state, key.op)
        Key.Equals -> equals(state)
        Key.Clear -> CalcState("0", fresh = true)
        Key.Back -> back(state)
    }

    /** 식의 마지막 숫자 부분. 맨 앞의 '-' 는 부호이므로 연산자로 보지 않는다. */
    private fun currentNumber(expr: String): String {
        var idx = -1
        for (k in expr.indices.reversed()) {
            if (expr[k] in OPS && k > 0) { idx = k; break }
        }
        return expr.substring(idx + 1)
    }

    private fun digit(state: CalcState, d: Char): CalcState {
        if (state.fresh) return CalcState(d.toString(), fresh = false)
        val expr = state.expr
        if (expr.length >= MAX_EXPR_LEN) return state
        val num = currentNumber(expr)
        val unsigned = num.removePrefix("-")
        if (unsigned == "0") {
            // 0 뒤에 숫자를 치면 0 을 대체한다.
            return state.copy(expr = expr.dropLast(1) + d, fresh = false)
        }
        if ('.' in unsigned) {
            if (unsigned.substringAfter('.').length >= MAX_FRACTION_DIGITS) return state
        } else if (unsigned.length >= MAX_INT_DIGITS) {
            return state
        }
        return state.copy(expr = expr + d, fresh = false)
    }

    private fun dot(state: CalcState): CalcState {
        if (state.fresh) return CalcState("0.", fresh = false)
        val expr = state.expr
        if (expr.length >= MAX_EXPR_LEN) return state
        val num = currentNumber(expr)
        if ('.' in num) return state
        if (num.isEmpty() || num == "-") return state.copy(expr = expr + "0.", fresh = false)
        return state.copy(expr = "$expr.", fresh = false)
    }

    private fun op(state: CalcState, op: Char): CalcState {
        var expr = state.expr
        if (expr.isEmpty()) expr = "0"
        if (expr.length >= MAX_EXPR_LEN) return state
        if (expr.last() == '.') expr = expr.dropLast(1)
        expr = if (expr.length > 1 && expr.last() in OPS) expr.dropLast(1) + op else expr + op
        return CalcState(expr, fresh = false)
    }

    private fun equals(state: CalcState): CalcState {
        val v = evaluate(state.expr) ?: return state
        return CalcState(toPlainInput(v), fresh = true)
    }

    private fun back(state: CalcState): CalcState {
        val e = state.expr.dropLast(1)
        return if (e.isEmpty() || e == "-") CalcState("0", fresh = false) else CalcState(e, fresh = false)
    }

    /** 식을 계산한다. 끝에 붙은 연산자/소수점은 무시. 0 으로 나누면 null. */
    fun evaluate(expr: String): BigDecimal? {
        var e = expr.trim()
        while (e.isNotEmpty() && (e.last() in OPS || e.last() == '.')) {
            if (e.length == 1 && e == "-") return BigDecimal.ZERO
            e = e.dropLast(1)
        }
        if (e.isEmpty()) return BigDecimal.ZERO

        val numbers = ArrayList<BigDecimal>()
        val ops = ArrayList<Char>()
        val sb = StringBuilder()
        for ((i, c) in e.withIndex()) {
            if (c in OPS && !(i == 0 && c == '-')) {
                numbers += sb.toString().toBigDecimalOrNull() ?: return null
                sb.clear()
                ops += c
            } else {
                sb.append(c)
            }
        }
        numbers += sb.toString().toBigDecimalOrNull() ?: return null

        // 1단계: 곱셈·나눗셈
        val n2 = ArrayList<BigDecimal>().apply { add(numbers[0]) }
        val o2 = ArrayList<Char>()
        for (i in ops.indices) {
            val rhs = numbers[i + 1]
            when (ops[i]) {
                '*' -> n2[n2.lastIndex] = n2.last().multiply(rhs, MC)
                '/' -> {
                    if (rhs.signum() == 0) return null
                    n2[n2.lastIndex] = n2.last().divide(rhs, MC)
                }
                else -> { o2 += ops[i]; n2 += rhs }
            }
        }
        // 2단계: 덧셈·뺄셈
        var acc = n2[0]
        for (i in o2.indices) {
            acc = if (o2[i] == '+') acc.add(n2[i + 1], MC) else acc.subtract(n2[i + 1], MC)
        }
        return acc
    }

    /** 다른 행을 눌렀을 때 그 값을 입력값으로 옮기기 위한 문자열 (구분기호 없음). */
    fun toPlainInput(v: BigDecimal): String {
        val scale = AmountFormat.displayScale(v)
        val r = v.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = if (r.signum() == 0) "0" else r.toPlainString()
        return s
    }

    /** 화면 표시용: 천 단위 구분기호와 연산자 기호(+ − × ÷)를 붙인다. */
    fun display(expr: String): String {
        val out = StringBuilder()
        val num = StringBuilder()
        fun flush() {
            if (num.isEmpty()) return
            val s = num.toString()
            val neg = s.startsWith("-")
            val body = s.removePrefix("-")
            val intPart = body.substringBefore('.')
            val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
            if (neg) out.append('-')
            out.append(grouped)
            if ('.' in body) out.append('.').append(body.substringAfter('.'))
            num.clear()
        }
        for ((i, c) in expr.withIndex()) {
            if (c in OPS && !(i == 0 && c == '-')) {
                flush()
                out.append(
                    when (c) {
                        '+' -> '+'
                        '-' -> '−'
                        '*' -> '×'
                        else -> '÷'
                    }
                )
            } else {
                num.append(c)
            }
        }
        flush()
        return out.toString()
    }

    private val MC = MathContext(30, RoundingMode.HALF_UP)
}
