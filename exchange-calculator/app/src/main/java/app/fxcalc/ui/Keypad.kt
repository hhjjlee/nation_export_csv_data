package app.fxcalc.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fxcalc.calc.Key

private val KeyShape = RoundedCornerShape(12.dp)

@Composable
fun Keypad(
    expanded: Boolean,
    refreshing: Boolean,
    onToggle: (Boolean) -> Unit,
    onKey: (Key) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 52.dp,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Fx.Sheet)
    ) {
        // 손잡이: 탭하거나 위/아래로 끌어서 키패드를 펼치고 접는다.
        var drag by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onToggle(!expanded)
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { drag += it },
                    onDragStarted = { drag = 0f },
                    onDragStopped = {
                        if (drag > 30f) onToggle(false) else if (drag < -30f) onToggle(true)
                    },
                )
                .semantics { contentDescription = if (expanded) "키패드 접기" else "키패드 펼치기" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF5A5C61))
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                KeyRow {
                    TextKey("C", weight = 2f, height = keyHeight, color = Fx.Clear, size = 34, onClick = { onKey(Key.Clear) })
                    IconKey(height = keyHeight, onClick = onRefresh, desc = "환율 새로고침") {
                        SpinningSync(refreshing)
                    }
                    TextKey("÷", height = keyHeight, color = Fx.Accent, onClick = { onKey(Key.Op('/')) })
                }
                KeyRow {
                    DigitKey('7', keyHeight, onKey); DigitKey('8', keyHeight, onKey); DigitKey('9', keyHeight, onKey)
                    TextKey("×", height = keyHeight, color = Fx.Accent, onClick = { onKey(Key.Op('*')) })
                }
                KeyRow {
                    DigitKey('4', keyHeight, onKey); DigitKey('5', keyHeight, onKey); DigitKey('6', keyHeight, onKey)
                    TextKey("−", height = keyHeight, color = Fx.Accent, onClick = { onKey(Key.Op('-')) })
                }
                KeyRow {
                    DigitKey('1', keyHeight, onKey); DigitKey('2', keyHeight, onKey); DigitKey('3', keyHeight, onKey)
                    TextKey("+", height = keyHeight, color = Fx.Accent, onClick = { onKey(Key.Op('+')) })
                }
                KeyRow {
                    DigitKey('0', keyHeight, onKey)
                    TextKey(".", height = keyHeight, onClick = { onKey(Key.Dot) })
                    IconKey(
                        height = keyHeight,
                        onClick = { onKey(Key.Back) },
                        onLongClick = { onKey(Key.Clear) },
                        desc = "지우기",
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = Fx.TextPrimary, modifier = Modifier.size(30.dp))
                    }
                    TextKey("=", height = keyHeight, color = Fx.Equals, bg = Fx.EqualsBg, size = 34, onClick = { onKey(Key.Equals) })
                }
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun RowScope.DigitKey(d: Char, height: Dp, onKey: (Key) -> Unit) =
    TextKey(d.toString(), height = height, onClick = { onKey(Key.Digit(d)) })

@Composable
private fun RowScope.TextKey(
    label: String,
    height: Dp,
    weight: Float = 1f,
    color: Color = Fx.TextPrimary,
    bg: Color = Fx.Key,
    size: Int = 32,
    onClick: () -> Unit,
) = KeyBase(height = height, weight = weight, bg = bg, onClick = onClick, desc = label) {
    Text(label, color = color, fontSize = size.sp, fontWeight = if (label == "C" || label == "=") FontWeight.Medium else FontWeight.Normal)
}

@Composable
private fun RowScope.IconKey(
    height: Dp,
    onClick: () -> Unit,
    desc: String,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) = KeyBase(height = height, weight = 1f, bg = Fx.Key, onClick = onClick, onLongClick = onLongClick, desc = desc, content = content)

/** 살짝 튀어나온 입체 버튼. 누르면 내려앉고 진동한다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.KeyBase(
    height: Dp,
    weight: Float,
    bg: Color,
    onClick: () -> Unit,
    desc: String,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    Box(
        Modifier
            .weight(weight)
            .padding(4.dp)
            .height(height)
            .clip(KeyShape)
            .background(Fx.KeyShadow)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        it()
                    }
                },
            )
            .semantics { contentDescription = desc }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = if (pressed) 2.dp else 0.dp, bottom = if (pressed) 1.dp else 3.dp)
                .clip(KeyShape)
                .background(if (pressed) Fx.KeyPressed else bg)
                .border(0.5.dp, Color(0x12FFFFFF), KeyShape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun SpinningSync(spinning: Boolean) {
    val angle = if (spinning) {
        val t = rememberInfiniteTransition(label = "sync")
        val a by t.animateFloat(
            initialValue = 0f,
            targetValue = -360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        )
        a
    } else 0f
    Icon(Icons.Rounded.Sync, null, tint = Fx.Accent, modifier = Modifier.size(34.dp).rotate(angle))
}
