package app.fxcalc.data

import java.util.Currency
import java.util.Locale

object Currencies {
    /** 첫 실행 시 보여줄 통화 (요청 이미지와 같은 순서) */
    val DEFAULT = listOf("USD", "KRW", "VND", "SGD", "MYR")

    /** 통화 선택 목록 맨 위에 보여줄 자주 쓰는 통화 */
    val POPULAR = listOf(
        "KRW", "USD", "JPY", "EUR", "CNY", "VND", "THB", "SGD", "MYR", "PHP",
        "IDR", "TWD", "HKD", "GBP", "AUD", "CAD", "CHF", "NZD", "MNT", "KHR",
        "LAK", "MOP", "INR", "AED", "TRY",
    )

    /** 환율 데이터를 아직 못 받았을 때 고를 수 있는 기본 목록 */
    val FALLBACK_CODES = (POPULAR + listOf(
        "BRL", "MXN", "RUB", "ZAR", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF",
        "SAR", "QAR", "KWD", "ILS", "EGP", "PKR", "BDT", "LKR", "NPR", "MMK",
        "KZT", "UZS", "ARS", "CLP", "COP", "PEN",
    )).distinct()

    private val nameOverrides = mapOf(
        "KRW" to "대한민국 원",
        "USD" to "미국 달러",
        "VND" to "베트남 동",
        "CNY" to "중국 위안",
        "JPY" to "일본 엔",
        "EUR" to "유로",
    )

    // 통화 코드 앞 두 글자가 나라 코드가 아닌 경우
    private val flagOverrides = mapOf(
        "EUR" to "EU", "ANG" to "CW", "XCD" to "AG", "XAF" to "CM",
        "XOF" to "SN", "XPF" to "PF", "XCG" to "CW",
    )

    fun name(code: String): String {
        nameOverrides[code]?.let { return it }
        return runCatching { Currency.getInstance(code).getDisplayName(Locale.KOREAN) }
            .getOrNull()
            ?.takeIf { it != code }
            ?: code
    }

    fun fractionDigits(code: String): Int =
        runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()
            ?.takeIf { it >= 0 } ?: 2

    /** 국기 이모지 (갤럭시 기본 이모지 글꼴로 표시) */
    fun flag(code: String): String {
        val cc = flagOverrides[code]
            ?: if (code.startsWith("X")) return "🌐" /* 🌐 */ else code.take(2)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
        val sb = StringBuilder()
        for (ch in cc) sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
        return sb.toString()
    }

    /** 선택 목록 정렬: 자주 쓰는 통화 먼저, 나머지는 코드순 */
    fun sortedForPicker(codes: Collection<String>): List<String> {
        val set = codes.toSet()
        val head = POPULAR.filter { it in set }
        val tail = (set - head.toSet()).sorted()
        return head + tail
    }
}
