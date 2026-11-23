package app.fxcalc

import app.fxcalc.calc.PriceParser
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceParserTest {
    private fun values(text: String) = PriceParser.parse(text).map { it.value.stripTrailingZeros().toPlainString() }

    @Test fun vietnameseDotGrouping() {
        assertEquals(listOf("35000"), values("Phở bò 35.000đ"))
        assertEquals(listOf("3500000"), values("3.500.000 VND"))
    }

    @Test fun commaGrouping() {
        assertEquals(listOf("35000"), values("35,000"))
        assertEquals(listOf("1234.56"), values("$1,234.56"))
    }

    @Test fun europeanDecimalComma() {
        assertEquals(listOf("1234.56"), values("1.234,56 €"))
        assertEquals(listOf("12.5"), values("12,5"))
    }

    @Test fun decimals() {
        assertEquals(listOf("12.99"), values("Price 12.99"))
        assertEquals(listOf("0.5"), values("0.500"))
    }

    @Test fun kSuffix() {
        assertEquals(listOf("35000"), values("Trà sữa 35k"))
        assertEquals(listOf("45000"), values("45 K"))
        assertEquals(listOf("2"), values("2 kg").take(1))
    }

    @Test fun spaceGrouping() {
        assertEquals(listOf("3500000"), values("3 500 000 ₫"))
    }

    @Test fun multiplePricesInOneLine() {
        assertEquals(listOf("25000", "30000"), values("S 25.000  L 30.000"))
    }

    @Test fun skipsTimesDatesPhonesPercentsAndModelNumbers() {
        assertEquals(emptyList<String>(), values("12:30"))
        assertEquals(emptyList<String>(), values("22/11/2026"))
        assertEquals(emptyList<String>(), values("090-123-4567"))
        assertEquals(emptyList<String>(), values("50%"))
        assertEquals(emptyList<String>(), values("A4"))
    }

    @Test fun skipsHugeNumbers() {
        assertEquals(emptyList<String>(), values("1234567890123"))
    }
}
