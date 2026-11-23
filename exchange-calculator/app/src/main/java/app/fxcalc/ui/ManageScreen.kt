package app.fxcalc.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fxcalc.data.Currencies
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun ManageScreen(
    currencies: List<String>,
    canRemove: Boolean,
    canAdd: Boolean,
    onBack: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val view = LocalView.current
    // 드래그 중에는 화면 목록을 바로 바꿔야 부드럽게 움직인다.
    var items by remember(currencies) { mutableStateOf(currencies) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onMove(from.index, to.index)
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Fx.Bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Fx.TopBar)
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = Fx.TextPrimary) }
            Text("통화 관리", color = Fx.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "오른쪽 ≡ 를 끌어서 순서를 바꿀 수 있어요 · 최소 2개, 최대 12개",
            color = Fx.TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(items, key = { it }) { code ->
                ReorderableItem(reorderState, key = code) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elev")
                    Surface(color = if (isDragging) Fx.RowActive else Fx.Bg, shadowElevation = elevation) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onRemove(code) }, enabled = canRemove) {
                                Icon(
                                    Icons.Rounded.RemoveCircleOutline,
                                    "$code 삭제",
                                    tint = if (canRemove) Fx.Danger else Fx.TextMuted,
                                )
                            }
                            FlagText(code, 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(code, color = Fx.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                Text(Currencies.name(code), color = Fx.TextMuted, fontSize = 13.sp, maxLines = 1)
                            }
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                                ),
                            ) {
                                Icon(Icons.Rounded.DragHandle, "순서 바꾸기", tint = Fx.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canAdd) Fx.NavSelectedBg else Fx.TopBar)
                    .border(1.dp, Fx.Divider, RoundedCornerShape(14.dp))
                    .clickable(enabled = canAdd, onClick = onAdd),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = if (canAdd) Fx.Accent else Fx.TextMuted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (canAdd) "통화 추가" else "최대 12개까지 추가할 수 있어요",
                    color = if (canAdd) Fx.Accent else Fx.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
