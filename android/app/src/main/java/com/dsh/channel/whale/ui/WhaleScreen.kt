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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.channel.whale.Emotion
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
    onPack: (String) -> Unit,
    onImport: () -> Unit,
    onDeletePack: (String) -> Unit,
    onRenamePack: (String, String) -> Unit,
) {
    val pack = remember(state.packs, state.packId) { AvatarPacks.find(state.packs, state.packId) }

    // 素材包不看服务端情绪，只看通话走到哪一步：待机 sleeping、通话中 happy、
    // 思考 typing、说完先闪 2 s notification 再回 happy。
    var wasSpeaking by remember { mutableStateOf(false) }
    var justFinished by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.SPEAKING) {
            wasSpeaking = true
            return@LaunchedEffect
        }
        if (wasSpeaking) {
            wasSpeaking = false
            justFinished = true
            try {
                delay(2000)
            } finally {
                justFinished = false
            }
        }
    }
    val packEmotion = when {
        !state.callActive -> Emotion.SLEEPY
        justFinished -> Emotion.NEUTRAL // 包内 neutral → idle（notification）
        state.phase == Phase.THINKING -> Emotion.THINKING
        else -> Emotion.HAPPY
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(8000)
            onClearMessage()
        }
    }

    // 背景跟着素材底色走：素材底是什么色，页面就是什么色，边界自然融掉。
    // 有素材时必须是纯色——渐变会让素材的四边重新显出轮廓。
    val background = remember(state.tint) {
        if (state.tint == UiState.DEFAULT_TINT) {
            Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF121C38), Color(0xFF0B1020)))
        } else {
            SolidColor(backgroundOf(state.tint))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .systemBarsPadding(),
    ) {
        // 脸就是这一整块屏幕：五官按整屏比例铺开，没有边框，也没有任何底衬；
        // 文字和通话按钮只是浮在脸的最下方，不占脸的面积。
        WhaleAvatar(
            emotion = state.emotion,
            phase = state.phase,
            pack = pack,
            modifier = Modifier.fillMaxSize(),
            packEmotion = packEmotion,
        )

        // 设置按钮放在形象之后：素材再大也盖不住它。
        IconButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color(0xFF93A1C0))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 152.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 只留字幕：状态文案统一由底部按钮那一行显示，别在这里再写一遍。
            Text(
                text = state.subtitle.ifBlank { " " },
                color = Color(0xFFC7D2EC),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
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
                onPack = onPack,
                onImport = onImport,
                onDeletePack = onDeletePack,
                onRenamePack = onRenamePack,
            )
        }
    }
}

/**
 * 素材底色 → 背景色：色相不动，只把明度 / 饱和度限制在深色主题还能看清字的范围。
 * 素材本身够暗时（绝大多数深色素材）会原样返回，做到「素材底就是背景」。
 */
private fun backgroundOf(tint: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tint, hsv)
    hsv[1] = hsv[1].coerceIn(0.05f, 0.60f)
    hsv[2] = hsv[2].coerceIn(0.04f, 0.26f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
