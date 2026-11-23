package app.fxcalc

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.fxcalc.calc.CalcState
import app.fxcalc.data.RateTable
import app.fxcalc.ui.BottomNav
import app.fxcalc.ui.ConverterScreen
import app.fxcalc.ui.CurrencyPickerContent
import app.fxcalc.ui.Fx
import app.fxcalc.ui.FxTheme
import app.fxcalc.ui.ManageScreen
import app.fxcalc.ui.ScanDetection
import app.fxcalc.ui.ScanOverlay
import app.fxcalc.ui.ScannerScreen
import app.fxcalc.ui.TopBar
import app.fxcalc.ui.UiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal

/**
 * 갤럭시 S25 화면 크기(384 x 832 dp)로 각 화면을 그려 PNG 로 저장한다.
 * 결과: app/screenshots 폴더의 PNG 파일
 * (테스트용 가짜 환율이며 실제 값이 아님)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val fakeRates = RateTable(
        rates = mapOf("USD" to 1.0, "KRW" to 1345.2, "VND" to 25785.5, "SGD" to 1.29, "MYR" to 4.12, "JPY" to 150.1, "EUR" to 0.92),
        updatedAt = 1763856000000L,
        fetchedAt = 1763856000000L,
        source = "test",
    )

    private val state = UiState(
        active = 2,
        calc = CalcState("3500000", fresh = false),
        rates = fakeRates,
    )

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")

    @Test fun converter() {
        compose.setContent {
            FxTheme {
                Column(Modifier.fillMaxSize().background(Fx.Bg)) {
                    TopBar({}, {}, {})
                    Box(Modifier.weight(1f)) {
                        ConverterScreen(state, {}, {}, {}, {}, {}, {})
                    }
                    BottomNav(0) {}
                }
            }
        }
        shot("1_converter")
    }

    @Test fun converterWithExpressionAndCollapsedKeypad() {
        compose.setContent {
            FxTheme {
                Column(Modifier.fillMaxSize().background(Fx.Bg)) {
                    TopBar({}, {}, {})
                    Box(Modifier.weight(1f)) {
                        ConverterScreen(
                            state.copy(
                                currencies = listOf("USD", "KRW", "VND", "SGD", "MYR", "JPY", "EUR"),
                                calc = CalcState("1250000+380000*2", fresh = false),
                                keypadExpanded = false,
                            ),
                            {}, {}, {}, {}, {}, {},
                        )
                    }
                    BottomNav(0) {}
                }
            }
        }
        shot("2_converter_collapsed")
    }

    @Test fun manage() {
        compose.setContent {
            FxTheme { ManageScreen(state.currencies, true, true, {}, { _, _ -> }, {}, {}) }
        }
        shot("3_manage")
    }

    @Test fun picker() {
        compose.setContent {
            FxTheme {
                Box(Modifier.fillMaxSize().background(Fx.Sheet)) {
                    CurrencyPickerContent("통화 선택", state.availableCodes, setOf("VND"), {})
                }
            }
        }
        shot("4_picker")
    }

    @Test fun scannerPermission() {
        compose.setContent {
            FxTheme {
                Column(Modifier.fillMaxSize().background(Fx.Bg)) {
                    TopBar({}, {}, {})
                    Box(Modifier.weight(1f)) {
                        ScannerScreen(state, {}, {}, {}, { _, _ -> }, cameraEnabled = false)
                    }
                    BottomNav(1) {}
                }
            }
        }
        shot("5_scanner_permission")
    }

    @Test fun scannerOverlay() {
        // 카메라 대신 회색 배경 위에 인식 결과 라벨만 그려 본다 (px 좌표).
        val detections = listOf(
            ScanDetection(Rect(600, 400, 900, 470), BigDecimal("35000")),
            ScanDetection(Rect(600, 600, 900, 670), BigDecimal("45000")),
            ScanDetection(Rect(560, 800, 940, 880), BigDecimal("120000")),
        )
        compose.setContent {
            FxTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF6B6B6B))) {
                    ScanOverlay(detections, "VND", "KRW", fakeRates, {})
                }
            }
        }
        shot("6_scanner_overlay")
    }
}
