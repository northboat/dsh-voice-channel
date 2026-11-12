package com.dsh.channel.whale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.channel.whale.UiState
import com.dsh.channel.whale.net.LanDiscovery

@OptIn(ExperimentalMaterial3Api::class)
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
    onRenamePack: (String, String) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<AvatarPack?>(null) }
    var deleteTarget by remember { mutableStateOf<AvatarPack?>(null) }

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
                text = "连接桌面端，就能开口聊天。",
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

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("形象", fontSize = 13.sp, color = Color(0xFF93A1C0))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Text("导入素材", fontSize = 13.sp, color = Color(0xFF8FA4FF))
                }
            }
            Text(
                text = "Deepy 是内置形象，不能删除；导入 zip 后可以改名、覆盖导入或删除。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFF6F7FA3),
            )
            Spacer(Modifier.height(10.dp))

            state.packs.forEach { pack ->
                AvatarRow(
                    name = pack.name,
                    detail = if (pack.id == AvatarPacks.BUILT_IN) "内置形象" else pack.detail(),
                    selected = state.packId == pack.id,
                    onSelect = { onPack(pack.id) },
                    onRename = if (pack.id == AvatarPacks.BUILT_IN) null else ({ renameTarget = pack }),
                    onDelete = if (pack.id == AvatarPacks.BUILT_IN) null else ({ deleteTarget = pack }),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    deleteTarget?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${pack.name}」？", fontSize = 17.sp) },
            text = {
                Text(
                    "素材会从手机里删掉，需要时可以重新导入 zip。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePack(pack.id)
                        deleteTarget = null
                    },
                ) { Text("删除", color = Color(0xFFF87171)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { pack ->
        var draft by remember(pack.id) { mutableStateOf(pack.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名素材", fontSize = 17.sp) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(24) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenamePack(pack.id, draft)
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }
}

private fun AvatarPack.detail(): String {
    val count = files.count { it.endsWith(".gif", true) || it.endsWith(".webp", true) }
    return if (count == 0) "没有可用动画" else "$count 个动画文件"
}

/** 素材行：点整行切换，右侧「改名」「删除」只对素材包出现。 */
@Composable
private fun AvatarRow(
    name: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color(0xFF1D2A4D) else Color(0xFF0E1528),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (selected) Color(0xFF8FA4FF) else Color(0xFF3A4666), CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    color = if (selected) Color(0xFFE8EDF9) else Color(0xFFC7D2EC),
                )
                Text(detail, fontSize = 11.sp, color = Color(0xFF6F7FA3))
            }
            if (onRename != null) {
                TextButton(onClick = onRename) {
                    Text("改名", fontSize = 12.sp, color = Color(0xFF8FA4FF))
                }
            }
            if (onDelete != null) {
                DeleteBadge(onClick = onDelete)
            }
        }
    }
}

/** 删除按钮：手绘垃圾桶，比一个叉干净（也不依赖图标库）。 */
@Composable
private fun DeleteBadge(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFF1C1622),
        modifier = Modifier.size(30.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(15.dp)) {
                val w = size.width
                val h = size.height
                val ink = Color(0xFFE08585)
                val stroke = w * 0.11f
                val lidY = h * 0.28f
                // 盖子 + 提手
                drawLine(ink, Offset(w * 0.10f, lidY), Offset(w * 0.90f, lidY), stroke, StrokeCap.Round)
                drawLine(ink, Offset(w * 0.34f, lidY), Offset(w * 0.34f, h * 0.14f), stroke, StrokeCap.Round)
                drawLine(ink, Offset(w * 0.66f, h * 0.14f), Offset(w * 0.66f, lidY), stroke, StrokeCap.Round)
                drawLine(ink, Offset(w * 0.34f, h * 0.14f), Offset(w * 0.66f, h * 0.14f), stroke, StrokeCap.Round)
                // 桶身：上宽下窄
                val body = Path().apply {
                    moveTo(w * 0.20f, lidY + stroke)
                    lineTo(w * 0.28f, h * 0.92f)
                    lineTo(w * 0.72f, h * 0.92f)
                    lineTo(w * 0.80f, lidY + stroke)
                }
                drawPath(body, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
                // 两道竖纹
                drawLine(
                    ink.copy(alpha = 0.7f),
                    Offset(w * 0.42f, h * 0.44f),
                    Offset(w * 0.45f, h * 0.80f),
                    stroke * 0.8f,
                    StrokeCap.Round,
                )
                drawLine(
                    ink.copy(alpha = 0.7f),
                    Offset(w * 0.58f, h * 0.44f),
                    Offset(w * 0.55f, h * 0.80f),
                    stroke * 0.8f,
                    StrokeCap.Round,
                )
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
