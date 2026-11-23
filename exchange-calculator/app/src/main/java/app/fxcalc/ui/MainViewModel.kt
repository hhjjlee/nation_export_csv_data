package app.fxcalc.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fxcalc.calc.AmountFormat
import app.fxcalc.calc.CalcState
import app.fxcalc.calc.Calculator
import app.fxcalc.calc.Key
import app.fxcalc.data.Currencies
import app.fxcalc.data.RateTable
import app.fxcalc.data.RatesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class UiState(
    val currencies: List<String> = Currencies.DEFAULT,
    val active: Int = 0,
    val calc: CalcState = CalcState("1", fresh = true),
    val rates: RateTable? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
    val keypadExpanded: Boolean = true,
    val scanFrom: String = "VND",
    val scanTo: String = "KRW",
) {
    val activeCode: String get() = currencies[active]
    val amount: BigDecimal? get() = Calculator.evaluate(calc.expr)

    fun valueFor(i: Int): BigDecimal? {
        val a = amount ?: return null
        if (i == active) return a
        return rates?.convert(a, activeCode, currencies[i])
    }

    fun displayFor(i: Int): String =
        if (i == active) Calculator.display(calc.expr)
        else valueFor(i)?.let(AmountFormat::format) ?: "—"

    /** 사용 가능한 통화 코드 (환율을 받았으면 그 목록, 아니면 기본 목록) */
    val availableCodes: List<String>
        get() = Currencies.sortedForPicker(rates?.rates?.keys ?: Currencies.FALLBACK_CODES)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RatesRepository(app)
    private val prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(rates = repo.cached()) }
        refreshIfStale()
    }

    // ---------- 환율 ----------

    fun refreshIfStale() {
        val r = _state.value.rates
        if (r == null || System.currentTimeMillis() - r.fetchedAt > STALE_MS) refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try {
                val table = repo.fetch()
                _state.update { it.copy(rates = table, loading = false, error = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    // ---------- 변환기 ----------

    fun onKey(key: Key) = mutate { it.copy(calc = Calculator.input(it.calc, key)) }

    /** 다른 행을 누르면 그 행이 입력 대상이 되고, 현재 환산값을 이어받는다. */
    fun selectRow(i: Int) = mutate { s ->
        if (i == s.active) return@mutate s.copy(keypadExpanded = true)
        val v = s.valueFor(i)
        val expr = v?.let(Calculator::toPlainInput) ?: "0"
        s.copy(active = i, calc = CalcState(expr, fresh = true), keypadExpanded = true)
    }

    /** 행의 ▼ 를 눌러 통화를 바꾼다. 이미 목록에 있는 통화면 자리를 맞바꾼다. */
    fun replaceCurrency(i: Int, code: String) = mutate { s ->
        val list = s.currencies.toMutableList()
        val j = list.indexOf(code)
        if (j == i) return@mutate s
        val activeCode = s.activeCode
        if (j >= 0) {
            list[j] = list[i]
            list[i] = code
            s.copy(currencies = list, active = list.indexOf(activeCode))
        } else {
            list[i] = code
            s.copy(currencies = list)
        }
    }

    fun addCurrency(code: String) = mutate { s ->
        if (code in s.currencies || s.currencies.size >= MAX_CURRENCIES) s
        else s.copy(currencies = s.currencies + code)
    }

    fun removeCurrency(code: String) = mutate { s ->
        if (s.currencies.size <= MIN_CURRENCIES || code !in s.currencies) return@mutate s
        val list = s.currencies - code
        if (code == s.activeCode) {
            val newActive = list.first()
            val v = s.valueFor(s.currencies.indexOf(newActive))
            s.copy(currencies = list, active = 0, calc = CalcState(v?.let(Calculator::toPlainInput) ?: "0", true))
        } else {
            s.copy(currencies = list, active = list.indexOf(s.activeCode))
        }
    }

    fun moveCurrency(from: Int, to: Int) = mutate { s ->
        if (from !in s.currencies.indices || to !in s.currencies.indices) return@mutate s
        val list = s.currencies.toMutableList().apply { add(to, removeAt(from)) }
        s.copy(currencies = list, active = list.indexOf(s.activeCode))
    }

    fun setKeypadExpanded(expanded: Boolean) = mutate { it.copy(keypadExpanded = expanded) }

    // ---------- 가격 스캐너 ----------

    fun setScanPair(from: String, to: String) = mutate { it.copy(scanFrom = from, scanTo = to) }

    /** 스캐너에서 고른 가격을 변환기로 보낸다. */
    fun useScannedValue(code: String, value: BigDecimal) = mutate { s ->
        var list = s.currencies
        if (code !in list) list = (list + code).takeLast(MAX_CURRENCIES)
        s.copy(
            currencies = list,
            active = list.indexOf(code),
            calc = CalcState(Calculator.toPlainInput(value), fresh = true),
            keypadExpanded = true,
        )
    }

    // ---------- 저장 ----------

    private inline fun mutate(crossinline f: (UiState) -> UiState) {
        _state.update(f)
        save(_state.value)
    }

    private fun save(s: UiState) {
        prefs.edit()
            .putString("currencies", s.currencies.joinToString(","))
            .putInt("active", s.active)
            .putString("expr", s.calc.expr)
            .putBoolean("keypad", s.keypadExpanded)
            .putString("scanFrom", s.scanFrom)
            .putString("scanTo", s.scanTo)
            .apply()
    }

    private fun load(): UiState {
        val list = prefs.getString("currencies", null)
            ?.split(",")?.filter { it.length == 3 }?.distinct()
            ?.takeIf { it.size >= MIN_CURRENCIES }
            ?: Currencies.DEFAULT
        val active = prefs.getInt("active", 0).coerceIn(0, list.lastIndex)
        val expr = prefs.getString("expr", null)?.takeIf { Calculator.evaluate(it) != null } ?: "1"
        return UiState(
            currencies = list,
            active = active,
            calc = CalcState(expr, fresh = true),
            keypadExpanded = prefs.getBoolean("keypad", true),
            scanFrom = prefs.getString("scanFrom", null) ?: "VND",
            scanTo = prefs.getString("scanTo", null) ?: "KRW",
        )
    }

    companion object {
        const val MIN_CURRENCIES = 2
        const val MAX_CURRENCIES = 12
        private const val STALE_MS = 30 * 60 * 1000L
    }
}
