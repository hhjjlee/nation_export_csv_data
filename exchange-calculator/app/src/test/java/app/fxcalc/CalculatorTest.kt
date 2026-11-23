package app.fxcalc

import app.fxcalc.calc.AmountFormat
import app.fxcalc.calc.CalcState
import app.fxcalc.calc.Calculator
import app.fxcalc.calc.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class CalculatorTest {
    private fun type(keys: String, start: CalcState = CalcState("0", fresh = true)): CalcState {
        var s = start
        for (c in keys) {
            val k = when (c) {
                in '0'..'9' -> Key.Digit(c)
                '.' -> Key.Dot
                '+', '-', '*', '/' -> Key.Op(c)
                '=' -> Key.Equals
                'C' -> Key.Clear
                '<' -> Key.Back
                else -> error("bad key $c")
            }
            s = Calculator.input(s, k)
        }
        return s
    }

    private fun eval(e: String) = Calculator.evaluate(e)?.stripTrailingZeros()?.toPlainString()

    @Test fun typingReplacesFreshValue() {
        assertEquals("3500000", type("3500000", CalcState("1", fresh = true)).expr)
    }

    @Test fun leadingZeroIsReplaced() {
        assertEquals("5", type("005").expr)
        assertEquals("0.05", type("0.05").expr)
    }

    @Test fun onlyOneDotPerNumber() {
        assertEquals("1.25", type("1..2.5").expr)
        assertEquals("1.5+0.5", type("1.5+.5").expr)
    }

    @Test fun operatorReplacesPreviousOperator() {
        assertEquals("12*", type("12+-*").expr)
    }

    @Test fun precedence() {
        assertEquals("14", eval("2+3*4"))
        assertEquals("5", eval("20/4"))
        assertEquals("-2", eval("2-4"))
        assertEquals("7", eval("10-6/2"))
    }

    @Test fun trailingOperatorIgnored() {
        assertEquals("12", eval("12+"))
        assertEquals("3", eval("3."))
    }

    @Test fun divideByZeroIsNull() {
        assertNull(Calculator.evaluate("5/0"))
    }

    @Test fun equalsGivesFreshResult() {
        val s = type("3500000+20000=")
        assertEquals("3520000", s.expr)
        assertEquals(true, s.fresh)
        assertEquals("7", type("7", s).expr)
    }

    @Test fun negativeResultCanContinue() {
        val s = type("2-5=")
        assertEquals("-3", s.expr)
        assertEquals("-3+1", type("+1", s).expr)
        assertEquals("-2", eval("-3+1"))
    }

    @Test fun backspace() {
        assertEquals("12", type("123<").expr)
        assertEquals("0", type("1<").expr)
        assertEquals("0", type("C").expr)
    }

    @Test fun displayGroupsThousands() {
        assertEquals("3,500,000", Calculator.display("3500000"))
        assertEquals("3,500,000+20,000", Calculator.display("3500000+20000"))
        assertEquals("1,234.50×2", Calculator.display("1234.50*2"))
        assertEquals("-1,000−5÷2", Calculator.display("-1000-5/2"))
        assertEquals("0.", Calculator.display("0."))
    }

    @Test fun amountFormat() {
        assertEquals("182,580.47", AmountFormat.format(BigDecimal("182580.4712")))
        assertEquals("135.73", AmountFormat.format(BigDecimal("135.7299")))
        assertEquals("100.00", AmountFormat.format(BigDecimal("100")))
        assertEquals("175.10", AmountFormat.format(BigDecimal("175.1")))
        assertEquals("0.000038", AmountFormat.format(BigDecimal("0.0000380012")))
        assertEquals("0.5432", AmountFormat.format(BigDecimal("0.54321")))
        assertEquals("0", AmountFormat.format(BigDecimal.ZERO))
    }

    @Test fun amountFormatForCurrency() {
        assertEquals("1,850", AmountFormat.formatForCurrency(BigDecimal("1849.6"), 0))
        assertEquals("12.90", AmountFormat.formatForCurrency(BigDecimal("12.9"), 2))
        assertEquals("0.03831", AmountFormat.formatForCurrency(BigDecimal("0.038312"), 0))
    }

    @Test fun plainInputRoundsLikeDisplay() {
        assertEquals("135.73", Calculator.toPlainInput(BigDecimal("135.7299")))
        assertEquals("182580.47", Calculator.toPlainInput(BigDecimal("182580.4712")))
        assertEquals("0.000038", Calculator.toPlainInput(BigDecimal("0.0000380012")))
    }
}
