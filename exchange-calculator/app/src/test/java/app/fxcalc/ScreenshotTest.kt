package app.fxcalc

import android.graphics.Rect
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
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
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * 갤럭시 S25 화면 크기(384 x 832 dp)로 각 화면을 그려 PNG 로 저장한다.
 * 실행: ./gradlew recordPaparazziDebug → app/src/test/snapshots/images 폴더
 * (테스트용 가짜 환율이며 실제 값이 아님)
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1152,
            screenHeight = 2496,
            xdpi = 480,
            ydpi = 480,
            density = Density.XXHIGH,
        ),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 0.5,
    )

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

    /** rememberLauncherForActivityResult 가 동작하도록 빈 레지스트리를 넣어 준다. */
    private val fakeRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) = paparazzi.snapshot(name) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
            FxTheme { content() }
        }
    }

    @Test fun converter() = shot("1_converter") {
        Column(Modifier.fillMaxSize().background(Fx.Bg)) {
            TopBar({}, {}, {})
            Box(Modifier.weight(1f)) {
                ConverterScreen(state, {}, {}, {}, {}, {}, {})
            }
            BottomNav(0) {}
        }
    }

    @Test fun converterWithExpressionAndCollapsedKeypad() = shot("2_converter_collapsed") {
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

    @Test fun manage() = shot("3_manage") {
        ManageScreen(state.currencies, true, true, {}, { _, _ -> }, {}, {})
    }

    @Test fun picker() = shot("4_picker") {
        Box(Modifier.fillMaxSize().background(Fx.Sheet)) {
            CurrencyPickerContent("통화 선택", state.availableCodes, setOf("VND"), {})
        }
    }

    @Test fun scannerPermission() = shot("5_scanner_permission") {
        Column(Modifier.fillMaxSize().background(Fx.Bg)) {
            TopBar({}, {}, {})
            Box(Modifier.weight(1f)) {
                ScannerScreen(state, {}, {}, {}, { _, _ -> }, cameraEnabled = false)
            }
            BottomNav(1) {}
        }
    }

    @Test fun scannerOverlay() = shot("6_scanner_overlay") {
        // 카메라 대신 회색 배경 위에 인식 결과 라벨만 그려 본다 (px 좌표).
        val detections = listOf(
            ScanDetection(Rect(600, 400, 900, 470), BigDecimal("35000")),
            ScanDetection(Rect(600, 600, 900, 670), BigDecimal("45000")),
            ScanDetection(Rect(560, 800, 940, 880), BigDecimal("120000")),
        )
        Box(Modifier.fillMaxSize().background(Color(0xFF6B6B6B))) {
            ScanOverlay(detections, "VND", "KRW", fakeRates, {})
        }
    }
}
