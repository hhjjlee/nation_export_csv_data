package app.fxcalc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 첨부 이미지의 다크 테마 색상 */
object Fx {
    val Bg = Color(0xFF141517)
    val TopBar = Color(0xFF1B1C1F)
    val RowActive = Color(0xFF1E2635)
    val TextPrimary = Color(0xFFEDEEF0)
    val TextSecondary = Color(0xFFC4C6CA)
    val TextMuted = Color(0xFF8B8E94)
    val Divider = Color(0xFF2A2C30)
    val Sheet = Color(0xFF1F2023)
    val Key = Color(0xFF2B2C30)
    val KeyPressed = Color(0xFF393B40)
    val KeyShadow = Color(0xFF0B0B0C)
    val Accent = Color(0xFF7EA2EC)
    val AccentStrong = Color(0xFF4C7BE0)
    val Clear = Color(0xFFD8615A)
    val Equals = Color(0xFFE47C3A)
    val EqualsBg = Color(0xFF34261E)
    val NavBg = Color(0xFF1A1B1E)
    val NavSelectedBg = Color(0xFF253250)
    val Danger = Color(0xFFE5736C)
    val Label = Color(0xFF2F5BD3)
}

@Composable
fun FxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fx.Accent,
            onPrimary = Color(0xFF0E1A33),
            background = Fx.Bg,
            onBackground = Fx.TextPrimary,
            surface = Fx.Sheet,
            onSurface = Fx.TextPrimary,
            surfaceVariant = Fx.Key,
            onSurfaceVariant = Fx.TextSecondary,
            surfaceContainer = Fx.Sheet,
            surfaceContainerHigh = Fx.Sheet,
            surfaceContainerLow = Fx.Sheet,
            outline = Fx.Divider,
            error = Fx.Danger,
        ),
        content = content,
    )
}
