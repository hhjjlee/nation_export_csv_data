package app.fxcalc.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.fxcalc.data.Currencies
import app.fxcalc.data.RateTable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 폭에 맞춰 글자 크기를 줄이는 한 줄 텍스트 (긴 금액·수식용) */
@Composable
fun AutoSizeText(
    text: String,
    style: TextStyle,
    color: Color,
    maxSize: TextUnit,
    minSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val size = remember(text, maxW, style) {
            var s = maxSize.value
            while (s > minSize.value) {
                val w = measurer.measure(text, style.copy(fontSize = s.sp), maxLines = 1, softWrap = false).size.width
                if (w <= maxW) break
                s -= 1f
            }
            s.sp
        }
        Text(
            text = text,
            style = style.copy(fontSize = size),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun FlagText(code: String, size: TextUnit, modifier: Modifier = Modifier) {
    Text(Currencies.flag(code), fontSize = size, modifier = modifier, maxLines = 1)
}

fun updatedLabel(rates: RateTable?): String {
    if (rates == null) return "환율 정보 없음"
    val fmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    return "${fmt.format(Date(rates.updatedAt))} 기준"
}
