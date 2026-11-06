package com.dsh.channel.whale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.channel.whale.UiState
import com.dsh.channel.whale.net.LanDiscovery

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onEndpoint: (String) -> Unit,
    onPin: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onChooseFound: (LanDiscovery.Found) -> Unit,
    onPack: (String) -> Unit,
    onImport: () -> Unit,
    onDeletePack: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 40.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "连接桌面端的小鲸鱼，就能开口聊天。",
                fontSize = 13.sp,
                color = Color(0xFF93A1C0),
            )
            Spacer(Modifier.height(22.dp))

            OutlinedTextField(
                value = state.endpointInput,
                onValueChange = onEndpoint,
                label = { Text("桌面端地址") },
                placeholder = { Text("192.168.1.7") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.pinInput,
                onValueChange = onPin,
                label = { Text("配对码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onScan,
                    enabled = !state.scanning,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF202B47),
                        contentColor = Color(0xFFE8EDF9),
                    ),
                ) { Text(if (state.scanning) "搜索中…" else "搜索") }
                Spacer(Modifier.size(10.dp))
                Button(onClick = onConnect, shape = RoundedCornerShape(14.dp)) { Text("连接") }
                Spacer(Modifier.weight(1f))
                ConnectionBadge(state.reachable)
            }

            if (state.found.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                state.found.forEach { found ->
                    DeviceRow(found, onClick = { onChooseFound(found) })
                    Spacer(Modifier.height(8.dp))
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(message, fontSize = 12.sp, color = Color(0xFF8FA4FF))
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("形象", fontSize = 13.sp, color = Color(0xFF93A1C0))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Text("导入 zip", fontSize = 13.sp, color = Color(0xFF8FA4FF))
                }
            }
            Spacer(Modifier.height(2.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PackChip("内置鲸鱼", state.packId.isEmpty()) { onPack("") }
                state.packs.forEach { pack ->
                    if (pack.imported) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PackChip(pack.name, state.packId == pack.id) { onPack(pack.id) }
                            Spacer(Modifier.size(6.dp))
                            DeleteBadge { onDeletePack(pack.id) }
                        }
                    } else {
                        PackChip(pack.name, state.packId == pack.id) { onPack(pack.id) }
                    }
                }
            }
        }
    }
}

/** 形象包选项：选中态用主色填充，未选中是深色底。 */
@Composable
private fun PackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF7C8CFF) else Color(0xFF0E1528),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) Color.White else Color(0xFFC7D2EC),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** 导入包的删除按钮：小圆底 + 手绘叉，避免依赖字体符号。 */
@Composable
private fun DeleteBadge(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFF2A1B22),
        modifier = Modifier.size(26.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(9.dp)) {
                val s = size.width
                val stroke = s * 0.18f
                drawLine(Color(0xFFF87171), Offset(0f, 0f), Offset(s, s), stroke, StrokeCap.Round)
                drawLine(Color(0xFFF87171), Offset(s, 0f), Offset(0f, s), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ConnectionBadge(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (connected) Color(0xFF4ADE80) else Color(0xFF5A6785), CircleShape),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text = if (connected) "已连接" else "未连接",
            fontSize = 12.sp,
            color = Color(0xFF93A1C0),
        )
    }
}

@Composable
private fun DeviceRow(found: LanDiscovery.Found, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E1528))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(found.host, fontSize = 14.sp)
            Text("${found.latencyMs} ms", fontSize = 11.sp, color = Color(0xFF93A1C0))
        }
        Text("连接", fontSize = 13.sp, color = Color(0xFF8FA4FF))
    }
}
