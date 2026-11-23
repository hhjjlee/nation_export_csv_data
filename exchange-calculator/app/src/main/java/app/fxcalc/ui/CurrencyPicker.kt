package app.fxcalc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fxcalc.data.Currencies

/** 통화 선택 시트. [marked] 는 체크 표시할 통화(이미 목록에 있는 통화 등). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyPickerSheet(
    title: String,
    codes: List<String>,
    marked: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Fx.Sheet,
    ) {
        CurrencyPickerContent(title, codes, marked, onPick, Modifier.fillMaxHeight(0.92f))
    }
}

@Composable
fun CurrencyPickerContent(
    title: String,
    codes: List<String>,
    marked: Set<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(codes, q) {
        if (q.isEmpty()) codes
        else codes.filter { it.contains(q, ignoreCase = true) || Currencies.name(it).contains(q, ignoreCase = true) }
    }
    Column(modifier.imePadding()) {
        Text(
            title,
            color = Fx.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("코드·이름 검색 (예: 베트남, VND)", maxLines = 1) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "지우기") }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Fx.Accent,
                unfocusedBorderColor = Fx.Divider,
                focusedContainerColor = Fx.Bg,
                unfocusedContainerColor = Fx.Bg,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(filtered, key = { it }) { code ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clickable { onPick(code) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlagText(code, 26.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(code, color = Fx.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(Currencies.name(code), color = Fx.TextMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    if (code in marked) Icon(Icons.Rounded.Check, "선택됨", tint = Fx.Accent)
                }
                HorizontalDivider(color = Fx.Divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 60.dp))
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "검색 결과가 없어요",
                        color = Fx.TextMuted,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}
