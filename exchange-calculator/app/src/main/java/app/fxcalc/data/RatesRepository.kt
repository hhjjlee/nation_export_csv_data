package app.fxcalc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** USD 기준 환율표 */
data class RateTable(
    val rates: Map<String, Double>,
    /** 데이터 제공처가 환율을 갱신한 시각 (epoch ms) */
    val updatedAt: Long,
    /** 이 앱이 받아온 시각 (epoch ms) */
    val fetchedAt: Long,
    val source: String,
) {
    /** amount(from) → to. 둘 중 하나라도 없으면 null */
    fun convert(amount: BigDecimal, from: String, to: String): BigDecimal? {
        if (from == to) return amount
        val rf = rates[from] ?: return null
        val rt = rates[to] ?: return null
        if (rf <= 0.0) return null
        return amount.multiply(BigDecimal(rt.toString()), MC).divide(BigDecimal(rf.toString()), MC)
    }

    fun toJson(): String = JSONObject().apply {
        put("updatedAt", updatedAt)
        put("fetchedAt", fetchedAt)
        put("source", source)
        put("rates", JSONObject().apply { rates.forEach { (k, v) -> put(k, v) } })
    }.toString()

    companion object {
        private val MC = MathContext(30)

        fun fromJson(s: String): RateTable? = runCatching {
            val o = JSONObject(s)
            val r = o.getJSONObject("rates")
            val map = HashMap<String, Double>()
            r.keys().forEach { k -> map[k] = r.getDouble(k) }
            RateTable(map, o.getLong("updatedAt"), o.getLong("fetchedAt"), o.getString("source"))
        }.getOrNull()
    }
}

/**
 * 키 없이 쓰는 무료 환율 API 두 곳을 차례로 시도한다.
 *  1) ExchangeRate-API Open Access (open.er-api.com) – 하루 1회 갱신
 *  2) fawazahmed0 currency-api (jsDelivr / Cloudflare Pages) – 하루 1회 갱신
 * 마지막으로 받은 값은 기기에 저장해 오프라인에서도 계산할 수 있게 한다.
 */
class RatesRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rates", Context.MODE_PRIVATE)

    fun cached(): RateTable? = prefs.getString(KEY, null)?.let(RateTable::fromJson)

    suspend fun fetch(): RateTable = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (source in SOURCES) {
            try {
                val table = source()
                if (table.rates.size > 20) {
                    prefs.edit().putString(KEY, table.toJson()).apply()
                    return@withContext table
                }
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException(errors.joinToString(" / "))
    }

    companion object {
        private const val KEY = "table"

        private val SOURCES: List<() -> RateTable> = listOf(
            { parseErApi(httpGet("https://open.er-api.com/v6/latest/USD")) },
            { parseFawaz(httpGet("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json")) },
            { parseFawaz(httpGet("https://latest.currency-api.pages.dev/v1/currencies/usd.min.json")) },
        )

        private fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        fun parseErApi(body: String, now: Long = System.currentTimeMillis()): RateTable {
            val o = JSONObject(body)
            require(o.optString("result") == "success") { "er-api: ${o.optString("error-type")}" }
            val r = o.getJSONObject("rates")
            val map = HashMap<String, Double>()
            r.keys().forEach { k -> map[k.uppercase()] = r.getDouble(k) }
            val updated = o.optLong("time_last_update_unix", now / 1000) * 1000
            return RateTable(map, updated, now, "ExchangeRate-API")
        }

        fun parseFawaz(body: String, now: Long = System.currentTimeMillis()): RateTable {
            val o = JSONObject(body)
            val r = o.getJSONObject("usd")
            val known = java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.toHashSet()
            val map = HashMap<String, Double>()
            r.keys().forEach { k ->
                val code = k.uppercase()
                if (code.length == 3 && code in known) map[code] = r.getDouble(k)
            }
            map["USD"] = 1.0
            val updated = runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(o.getString("date"))!!.time
            }.getOrDefault(now)
            return RateTable(map, updated, now, "currency-api")
        }
    }
}
