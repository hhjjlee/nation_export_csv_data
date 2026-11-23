package app.fxcalc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface PickerTarget {
    data class Row(val index: Int) : PickerTarget
    data object Add : PickerTarget
    data object ScanFrom : PickerTarget
    data object ScanTo : PickerTarget
}

@Composable
fun FxApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    // 앱으로 돌아올 때 30분 넘게 지났으면 환율을 새로 받는다.
    LifecycleResumeEffect(Unit) {
        vm.refreshIfStale()
        onPauseOrDispose { }
    }
    BackHandler(enabled = managing) { managing = false }
    BackHandler(enabled = !managing && tab == 1) { tab = 0 }

    FxTheme {
        Box(Modifier.fillMaxSize().background(Fx.Bg)) {
            if (managing) {
                ManageScreen(
                    currencies = state.currencies,
                    canRemove = state.currencies.size > MainViewModel.MIN_CURRENCIES,
                    canAdd = state.currencies.size < MainViewModel.MAX_CURRENCIES,
                    onBack = { managing = false },
                    onMove = vm::moveCurrency,
                    onRemove = vm::removeCurrency,
                    onAdd = { picker = PickerTarget.Add },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        onRefresh = vm::refresh,
                        onManage = { managing = true },
                        onInfo = { showInfo = true },
                    )
                    Box(Modifier.weight(1f)) {
                        if (tab == 0) {
                            ConverterScreen(
                                state = state,
                                onKey = vm::onKey,
                                onRowClick = vm::selectRow,
                                onPickCurrency = { picker = PickerTarget.Row(it) },
                                onManage = { managing = true },
                                onRefresh = vm::refresh,
                                onToggleKeypad = vm::setKeypadExpanded,
                            )
                        } else {
                            ScannerScreen(
                                state = state,
                                onPickFrom = { picker = PickerTarget.ScanFrom },
                                onPickTo = { picker = PickerTarget.ScanTo },
                                onSwap = { vm.setScanPair(state.scanTo, state.scanFrom) },
                                onUse = { code, value ->
                                    vm.useScannedValue(code, value)
                                    tab = 0
                                },
                            )
                        }
                    }
                    BottomNav(tab, onSelect = { tab = it })
                }
            }

            picker?.let { target ->
                val (title, marked) = when (target) {
                    is PickerTarget.Row -> "통화 선택" to setOf(state.currencies.getOrElse(target.index) { "" })
                    PickerTarget.Add -> "통화 추가" to state.currencies.toSet()
                    PickerTarget.ScanFrom -> "가격표 통화" to setOf(state.scanFrom)
                    PickerTarget.ScanTo -> "바꿀 통화" to setOf(state.scanTo)
                }
                CurrencyPickerSheet(
                    title = title,
                    codes = state.availableCodes,
                    marked = marked,
                    onDismiss = { picker = null },
                    onPick = { code ->
                        when (target) {
                            is PickerTarget.Row -> vm.replaceCurrency(target.index, code)
                            PickerTarget.Add -> vm.addCurrency(code)
                            PickerTarget.ScanFrom -> vm.setScanPair(code, state.scanTo)
                            PickerTarget.ScanTo -> vm.setScanPair(state.scanFrom, code)
                        }
                        picker = null
                    },
                )
            }

            if (showInfo) InfoDialog(state, onDismiss = { showInfo = false })
        }
    }
}

@Composable
fun TopBar(onRefresh: () -> Unit, onManage: () -> Unit, onInfo: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Fx.TopBar)
            .statusBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(48.dp))
        Text(
            "환율 계산기",
            color = Fx.TextPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "메뉴", tint = Fx.TextPrimary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("환율 새로고침") }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text("통화 관리") }, onClick = { menu = false; onManage() })
                DropdownMenuItem(text = { Text("환율 정보 · 출처") }, onClick = { menu = false; onInfo() })
            }
        }
    }
}

@Composable
fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Fx.NavBg)
            .navigationBarsPadding()
            .height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("변환기", Icons.Rounded.CurrencyExchange, selected == 0) { onSelect(0) }
        NavItem("가격 스캐너", Icons.Outlined.PhotoCamera, selected == 1) { onSelect(1) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) Fx.NavSelectedBg else Fx.NavBg)
                .padding(horizontal = 22.dp, vertical = 5.dp),
        ) {
            Icon(icon, null, tint = if (selected) Fx.Accent else Fx.TextMuted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) Fx.Accent else Fx.TextMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun InfoDialog(state: UiState, onDismiss: () -> Unit) {
    val fmt = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREA)
    val r = state.rates
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
        title = { Text("환율 정보") },
        text = {
            Text(
                buildString {
                    if (r != null) {
                        appendLine("출처: ${r.source}")
                        appendLine("환율 기준: ${fmt.format(Date(r.updatedAt))}")
                        appendLine("받아온 시각: ${fmt.format(Date(r.fetchedAt))}")
                        appendLine("통화 수: ${r.rates.size}개")
                    } else {
                        appendLine("아직 환율을 받아오지 못했어요.")
                    }
                    appendLine()
                    appendLine("무료 공개 API(키 없음)에서 하루 1회 갱신되는 기준 환율을 사용해요.")
                    append("은행·환전소의 실제 환율(수수료 포함)과는 다를 수 있으니 참고용으로 써주세요.")
                },
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        },
    )
}
