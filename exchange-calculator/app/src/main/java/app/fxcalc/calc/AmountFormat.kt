package app.fxcalc.calc

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object AmountFormat {
    private const val SIGNIFICANT_FOR_SMALL = 4
    private const val MAX_SCALE = 10

    /** 1 이상이면 소수 2자리, 1 미만이면 유효숫자 4자리까지 보여준다. */
    fun displayScale(v: BigDecimal): Int {
        val abs = v.abs()
        if (abs.signum() == 0) return 0
        if (abs >= BigDecimal.ONE) return 2
        val leadingZeros = abs.scale() - abs.precision()
        return (leadingZeros + SIGNIFICANT_FOR_SMALL).coerceIn(2, MAX_SCALE)
    }

    /** 변환기 목록에 표시하는 금액 (예: 182,580.47). 1 이상은 항상 소수 2자리로 맞춘다. */
    fun format(v: BigDecimal): String {
        val scale = displayScale(v)
        return format(v, scale, minFraction = if (v.abs() >= BigDecimal.ONE) 2 else 0)
    }

    /**
     * 통화 기본 소수 자릿수에 맞춰 표시 (예: KRW 1,850 / USD 12.99).
     * 1 미만의 아주 작은 값은 유효숫자를 살린다.
     */
    fun formatForCurrency(v: BigDecimal, fractionDigits: Int): String {
        val abs = v.abs()
        val scale = if (abs.signum() != 0 && abs < BigDecimal.ONE) displayScale(v) else fractionDigits
        return format(v, scale, minFraction = if (abs >= BigDecimal.ONE) fractionDigits else 0)
    }

    private fun format(v: BigDecimal, scale: Int, minFraction: Int = 0): String {
        val df = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).apply {
            maximumFractionDigits = scale
            minimumFractionDigits = minFraction.coerceAtMost(scale)
            roundingMode = RoundingMode.HALF_UP
        }
        val s = df.format(v)
        return if (s == "-0") "0" else s
    }
}
