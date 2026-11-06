package com.dsh.channel.whale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.channel.whale.Phase
import com.dsh.channel.whale.UiState
import com.dsh.channel.whale.net.LanDiscovery
import kotlinx.coroutines.delay

@Composable
fun DshWhaleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0B1020),
            surface = Color(0xFF151D33),
            primary = Color(0xFF7C8CFF),
            onPrimary = Color.White,
            onBackground = Color(0xFFE8EDF9),
            onSurface = Color(0xFFE8EDF9),
        ),
        content = content,
    )
}

@Composable
fun WhaleScreen(
    state: UiState,
    onCall: () -> Unit,
    onSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onClearMessage: () -> Unit,
    onEndpoint: (String) -> Unit,
    onPin: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onChooseFound: (LanDiscovery.Found) -> Unit,
) {
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(8000)
            onClearMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1020), Color(0xFF121C38), Color(0xFF0B1020))
                )
            )
            .systemBarsPadding(),
    ) {
        IconButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color(0xFF93A1C0))
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WhaleAvatar(
                emotion = state.emotion,
                phase = state.phase,
                modifier = Modifier.size(260.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = state.subtitle.ifBlank { " " },
                color = Color(0xFFC7D2EC),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            )
            Text(
                text = state.status,
                color = Color(0xFF6F7FA3),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            state.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Color(0x33F87171),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = message,
                        color = Color(0xFFFFD9D9),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FloatingActionButton(
                onClick = onCall,
                shape = CircleShape,
                containerColor = if (state.callActive) Color(0xFFF87171) else Color(0xFF7C8CFF),
            ) {
                if (state.callActive) {
                    HangupGlyph(Modifier.size(26.dp), Color.White)
                } else {
                    MicGlyph(Modifier.size(26.dp), Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    !state.callActive -> "轻触开始通话"
                    state.phase == Phase.SPEAKING -> "随口插话就能打断"
                    state.phase == Phase.THINKING -> "点一下挂断"
                    else -> "在听你说 · 点一下挂断"
                },
                color = Color(0xFF6F7FA3),
                fontSize = 11.sp,
            )
        }

        if (state.showSettings) {
            SettingsSheet(
                state = state,
                onDismiss = onDismissSettings,
                onEndpoint = onEndpoint,
                onPin = onPin,
                onScan = onScan,
                onConnect = onConnect,
                onChooseFound = onChooseFound,
            )
        }
    }
}

/** 自绘挂断：倒扣的听筒，与 MicGlyph 一样不引入扩展图标库。 */
@Composable
private fun HangupGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.095f
        val top = h * 0.42f
        val bottom = h * 0.62f
        drawLine(
            color = tint,
            start = Offset(w * 0.22f, top),
            end = Offset(w * 0.78f, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.22f, top),
            end = Offset(w * 0.22f, bottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.78f, top),
            end = Offset(w * 0.78f, bottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 自绘麦克风：material-icons-extended 里的 Mic 要背 34 MB，这里 20 行画完。 */
@Composable
private fun MicGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val capW = w * 0.32f
        val capH = h * 0.44f
        // 话筒胶囊
        drawRoundRect(
            color = tint,
            topLeft = Offset((w - capW) / 2f, h * 0.08f),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capW / 2f, capW / 2f),
            style = Stroke(width = stroke),
        )
        // U 形拾音环
        val arcW = w * 0.62f
        val arcH = h * 0.54f
        drawArc(
            color = tint,
            startAngle = 18f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset((w - arcW) / 2f, h * 0.18f),
            size = Size(arcW, arcH),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // 支杆与底座
        drawLine(
            color = tint,
            start = Offset(w / 2f, h * 0.72f),
            end = Offset(w / 2f, h * 0.87f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.30f, h * 0.89f),
            end = Offset(w * 0.70f, h * 0.89f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
