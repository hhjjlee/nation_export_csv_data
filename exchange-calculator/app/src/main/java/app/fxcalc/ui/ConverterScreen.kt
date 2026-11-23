package app.fxcalc.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fxcalc.calc.Key

@Composable
fun ConverterScreen(
    state: UiState,
    onKey: (Key) -> Unit,
    onRowClick: (Int) -> Unit,
    onPickCurrency: (Int) -> Unit,
    onManage: () -> Unit,
    onRefresh: () -> Unit,
    onToggleKeypad: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 54.dp,
    keyHeight: Dp = 52.dp,
) {
    Column(modifier.fillMaxSize().background(Fx.Bg)) {
        RateStatus(state, onRefresh)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(state.currencies, key = { _, c -> c }) { i, code ->
                CurrencyRow(
                    code = code,
                    value = state.displayFor(i),
                    active = i == state.active,
                    height = rowHeight,
                    onClick = { onRowClick(i) },
                    onPick = { onPickCurrency(i) },
                )
            }
            item(key = "manage") {
                ManageButton(onManage)
            }
        }
        Keypad(
            expanded = state.keypadExpanded,
            refreshing = state.loading,
            onToggle = onToggleKeypad,
            onKey = onKey,
            onRefresh = onRefresh,
            keyHeight = keyHeight,
        )
    }
}

/** 화면 위쪽 상태 표시: ✓ 기준 시각 / 불러오는 중 / 실패 */
@Composable
private fun RateStatus(state: UiState, onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onRefresh),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(22.dp), color = Fx.Accent, strokeWidth = 2.dp)
            state.error -> Icon(Icons.Outlined.ErrorOutline, null, tint = Fx.Danger, modifier = Modifier.size(26.dp))
            else -> Icon(Icons.Outlined.CheckCircle, null, tint = Fx.TextSecondary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(8.dp))
        val text = when {
            state.loading -> "환율 불러오는 중…"
            state.error && state.rates != null -> "업데이트 실패 · ${updatedLabel(state.rates)} (탭하여 재시도)"
            state.error -> "환율을 불러오지 못했어요 · 탭하여 재시도"
            else -> updatedLabel(state.rates)
        }
        Text(text, color = if (state.error) Fx.Danger else Fx.TextMuted, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurrencyRow(
    code: String,
    value: String,
    active: Boolean,
    height: Dp,
    onClick: () -> Unit,
    onPick: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(if (active) Fx.RowActive else Fx.Bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { clipboard.setText(AnnotatedString(value.replace(",", ""))) },
            )
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 국기 + 코드 + ▼ : 누르면 통화 선택
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPick)
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagText(code, 30.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                code,
                color = if (active) Fx.TextPrimary else Fx.TextSecondary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(Icons.Rounded.ArrowDropDown, "통화 변경", tint = Fx.TextSecondary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoSizeText(
                text = value,
                style = TextStyle(fontWeight = if (active) FontWeight.Normal else FontWeight.Light),
                color = if (active) Fx.TextPrimary else Fx.TextSecondary,
                maxSize = 30.sp,
                minSize = 14.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (active) BlinkingCursor()
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val t = rememberInfiniteTransition(label = "cursor")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            RepeatMode.Restart,
        ),
        label = "blink",
    )
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(2.dp)
            .height(30.dp)
            .alpha(a)
            .background(Fx.AccentStrong)
    )
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Fx.TopBar)
                .border(1.dp, Fx.Divider, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Fx.TextPrimary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("통화 관리", color = Fx.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }
}
