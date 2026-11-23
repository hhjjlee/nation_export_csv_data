package app.fxcalc.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.fxcalc.calc.AmountFormat
import app.fxcalc.calc.PriceParser
import app.fxcalc.data.Currencies
import app.fxcalc.data.RateTable
import com.google.mlkit.vision.text.Text as MlText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.math.BigDecimal

/** 카메라 화면 좌표(px) 위의 가격 하나 */
data class ScanDetection(val box: Rect, val value: BigDecimal)

/** ML Kit 인식 결과에서 가격과 그 위치를 뽑는다. */
fun extractPrices(text: MlText): List<ScanDetection> {
    val out = ArrayList<ScanDetection>()
    for (block in text.textBlocks) for (line in block.lines) {
        val prices = PriceParser.parse(line.text)
        if (prices.isEmpty()) continue
        // 줄 안에서 각 단어(element)의 글자 위치를 찾아, 가격에 해당하는 단어 상자만 합친다.
        var cursor = 0
        val spans = line.elements.mapNotNull { el ->
            val idx = line.text.indexOf(el.text, cursor)
            if (idx < 0) null else {
                cursor = idx + el.text.length
                Triple(idx, cursor, el.boundingBox)
            }
        }
        for (p in prices) {
            val rect = Rect()
            var found = false
            for ((s, e, b) in spans) {
                if (b == null || s >= p.end || e <= p.start) continue
                if (!found) rect.set(b) else rect.union(b)
                found = true
            }
            val box = if (found) rect else line.boundingBox ?: continue
            out += ScanDetection(box, p.value)
        }
    }
    return out.take(40)
}

@Composable
fun ScannerScreen(
    state: UiState,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onSwap: () -> Unit,
    onUse: (String, BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    cameraEnabled: Boolean = true,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            cameraEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && cameraEnabled) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted && cameraEnabled) {
            CameraWithOverlay(state.scanFrom, state.scanTo, state.rates, onUse)
        } else {
            PermissionNeeded(
                askedBefore = asked,
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                },
            )
        }
        PairBar(
            from = state.scanFrom,
            to = state.scanTo,
            onPickFrom = onPickFrom,
            onPickTo = onPickTo,
            onSwap = onSwap,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
        if (state.rates == null) {
            Text(
                "환율 정보가 아직 없어요. 인터넷 연결 후 변환기에서 새로고침 해주세요.",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun CameraWithOverlay(
    from: String,
    to: String,
    rates: RateTable?,
    onUse: (String, BigDecimal) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var detections by remember { mutableStateOf<List<ScanDetection>>(emptyList()) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var torch by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView 방식: 일시정지 때 현재 화면을 그대로 캡처할 수 있다.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val executor = ContextCompat.getMainExecutor(context)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                val text = result.getValue(recognizer) ?: return@MlKitAnalyzer
                if (frozen == null) detections = extractPrices(text)
            },
        )
        previewView.controller = controller
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            recognizer.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        frozen?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        ScanOverlay(detections, from, to, rates, onTap = { onUse(from, it) })

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (detections.isEmpty()) "가격표에 카메라를 비춰주세요" else "환산된 금액을 누르면 변환기로 보내요",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundButton(
                    onClick = {
                        torch = !torch
                        controller.enableTorch(torch)
                    },
                    size = 52,
                ) {
                    Icon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "플래시", tint = Color.White)
                }
                RoundButton(
                    onClick = { frozen = if (frozen == null) previewView.bitmap else null },
                    size = 68,
                    bg = Fx.Label,
                ) {
                    Icon(
                        if (frozen == null) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (frozen == null) "화면 고정" else "다시 스캔",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(52.dp))
            }
        }
    }
}

/** 인식된 가격 위에 환산 금액 라벨을 겹쳐 그린다. 좌표는 카메라 미리보기 px 기준. */
@Composable
fun ScanOverlay(
    detections: List<ScanDetection>,
    from: String,
    to: String,
    rates: RateTable?,
    onTap: (BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rates == null) return
    val density = LocalDensity.current
    val digits = remember(to) { Currencies.fractionDigits(to) }
    val items = remember(detections, from, to, rates) {
        detections.mapNotNull { d ->
            val v = rates.convert(d.value, from, to) ?: return@mapNotNull null
            d to AmountFormat.formatForCurrency(v, digits)
        }
    }
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            items.forEach { (d, label) ->
                val fontSp = with(density) { (d.box.height() * 0.62f).toSp().value }.coerceIn(12f, 24f)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Fx.Label)
                        .clickable { onTap(d.value) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = Color.White, fontSize = fontSp.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(3.dp))
                    Text(to, color = Color(0xFFCFDBFF), fontSize = (fontSp * 0.6f).sp, maxLines = 1)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val b = items[i].first.box
                val x = (b.centerX() - p.width / 2).coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                val y = (b.centerY() - p.height / 2).coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0))
                p.place(x, y)
            }
        }
    }
}

@Composable
private fun PairBar(
    from: String,
    to: String,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC1B1C1F))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PairChip(from, onPickFrom)
        IconButton(onClick = onSwap) { Icon(Icons.Rounded.SwapHoriz, "통화 바꾸기", tint = Fx.Accent) }
        PairChip(to, onPickTo)
    }
}

@Composable
private fun PairChip(code: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagText(code, 22.sp)
        Spacer(Modifier.width(6.dp))
        Text(code, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Rounded.ArrowDropDown, null, tint = Fx.TextSecondary)
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, size: Int, bg: Color = Color(0x99000000), content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PermissionNeeded(askedBefore: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Fx.Bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.PhotoCamera, null, tint = Fx.Accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("가격 스캐너", color = Fx.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "가격표를 카메라로 비추면 숫자를 읽어\n선택한 통화로 바로 환산해 보여줘요.\n카메라 권한이 필요해요.",
            color = Fx.TextMuted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Fx.AccentStrong, contentColor = Color.White),
        ) { Text("카메라 권한 허용", fontSize = 16.sp) }
        if (askedBefore) {
            Spacer(Modifier.height(8.dp))
            Text(
                "권한 창이 안 뜨면 설정에서 직접 허용해 주세요",
                color = Fx.Accent,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(8.dp),
            )
        }
    }
}
