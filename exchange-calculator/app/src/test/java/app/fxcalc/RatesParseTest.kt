package app.fxcalc

import app.fxcalc.data.Currencies
import app.fxcalc.data.RateTable
import app.fxcalc.data.RatesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

// org.json 은 테스트 의존성(org.json:json)의 실제 구현으로 동작한다.
class RatesParseTest {
    private val erApi = """
        {"result":"success","provider":"https://www.exchangerate-api.com","time_last_update_unix":1763856151,
         "base_code":"USD","rates":{"USD":1,"KRW":1345.2,"VND":25785.5,"SGD":1.29,"MYR":4.12}}
    """.trimIndent()

    private val fawaz = """
        {"date":"2026-11-22","usd":{"usd":1,"krw":1345.2,"vnd":25785.5,"btc":0.00001,"eur":0.92}}
    """.trimIndent()

    @Test fun parsesErApi() {
        val t = RatesRepository.parseErApi(erApi, now = 0)
        assertEquals(1345.2, t.rates.getValue("KRW"), 1e-9)
        assertEquals(1763856151000L, t.updatedAt)
        assertEquals("ExchangeRate-API", t.source)
    }

    @Test fun parsesFawazAndDropsCrypto() {
        val t = RatesRepository.parseFawaz(fawaz, now = 0)
        assertEquals(25785.5, t.rates.getValue("VND"), 1e-9)
        assertTrue("BTC" !in t.rates)
        assertTrue("EUR" in t.rates)
    }

    @Test fun convertsThroughUsd() {
        val t = RatesRepository.parseErApi(erApi, now = 0)
        // 3,500,000 VND → KRW = 3,500,000 / 25785.5 * 1345.2
        val krw = t.convert(BigDecimal("3500000"), "VND", "KRW")!!.setScale(2, RoundingMode.HALF_UP)
        assertEquals(BigDecimal("182590.99"), krw)
        assertEquals(BigDecimal("10"), t.convert(BigDecimal("10"), "USD", "USD"))
    }

    @Test fun jsonRoundTrip() {
        val t = RatesRepository.parseErApi(erApi, now = 42)
        val back = RateTable.fromJson(t.toJson())!!
        assertEquals(t, back)
    }

    @Test fun koreanNamesAndFlags() {
        assertEquals("베트남 동", Currencies.name("VND"))
        assertEquals("대한민국 원", Currencies.name("KRW"))
        assertEquals("🇻🇳", Currencies.flag("VND")) // 🇻🇳
        assertEquals("🇪🇺", Currencies.flag("EUR")) // 🇪🇺
    }
}
