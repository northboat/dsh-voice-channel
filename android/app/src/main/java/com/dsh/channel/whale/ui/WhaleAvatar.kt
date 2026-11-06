package com.dsh.channel.whale.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.dsh.channel.whale.Emotion
import com.dsh.channel.whale.Phase
import org.json.JSONObject

private data class EmotionMap(
    val dir: String,
    val suffix: String,
    val extension: String,
    val emotions: Map<String, String>,
)

private fun loadEmotionMap(context: Context): EmotionMap = runCatching {
    val text = context.assets.open("whale/emotion-map.json").bufferedReader().use { it.readText() }
    val obj = JSONObject(text)
    val emotions = obj.getJSONObject("emotions")
    val map = emotions.keys().asSequence().associateWith { emotions.getString(it) }
    EmotionMap(
        dir = obj.optString("dir", "whale"),
        suffix = obj.optString("suffix", "-zh-dark"),
        extension = obj.optString("extension", ".gif"),
        emotions = map,
    )
}.getOrDefault(EmotionMap("whale", "-zh-dark", ".gif", emptyMap()))

/**
 * 优先播放 assets/whale 下按情绪映射的自建 gif（命名参考 dsh-Go 的 4 状态），
 * 资源缺失时回退到内置程序化鲸鱼动画，保证开箱即用且不分发外部美术素材。
 */
@Composable
fun WhaleAvatar(emotion: Emotion, phase: Phase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val map = remember { loadEmotionMap(context) }
    val files = remember(map.dir) { context.assets.list(map.dir)?.toSet() ?: emptySet() }
    val key = map.emotions[emotion.key] ?: "idle"
    val fileName = "$key${map.suffix}${map.extension}"

    if (files.contains(fileName)) {
        val request = remember(fileName) {
            ImageRequest.Builder(context)
                .data("file:///android_asset/${map.dir}/$fileName")
                .decoderFactory(ImageDecoderDecoder.Factory())
                .crossfade(true)
                .build()
        }
        AsyncImage(model = request, contentDescription = "鲸鱼表情：$key", modifier = modifier)
    } else {
        ProgrammaticWhale(emotion = emotion, phase = phase, modifier = modifier)
    }
}

@Composable
private fun ProgrammaticWhale(emotion: Emotion, phase: Phase, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "whale")
    val bob by transition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val bubble by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "bubble",
    )
    val body = when (emotion) {
        Emotion.HAPPY -> Color(0xFF8FA4FF)
        Emotion.ANGRY, Emotion.ERROR -> Color(0xFF9B7BEF)
        Emotion.SLEEPY -> Color(0xFF5C6BAE)
        Emotion.SAD -> Color(0xFF6E86C9)
        else -> Color(0xFF7C8CFF)
    }
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWhale(emotion, phase, body, bob, bubble)
        }
    }
}

private fun DrawScope.drawWhale(
    emotion: Emotion,
    phase: Phase,
    body: Color,
    bob: Float,
    bubble: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f + bob
    val bodyW = w * 0.56f
    val bodyH = h * 0.34f

    val tail = Path().apply {
        moveTo(cx + bodyW / 2f - 6f, cy)
        lineTo(cx + bodyW / 2f + w * 0.14f, cy - h * 0.16f)
        lineTo(cx + bodyW / 2f + w * 0.10f, cy)
        lineTo(cx + bodyW / 2f + w * 0.17f, cy + h * 0.14f)
        close()
    }
    drawPath(tail, body)

    drawOval(
        color = body,
        topLeft = Offset(cx - bodyW / 2f, cy - bodyH / 2f),
        size = Size(bodyW, bodyH),
    )
    drawOval(
        color = Color.White,
        topLeft = Offset(cx - bodyW * 0.36f, cy + bodyH * 0.06f),
        size = Size(bodyW * 0.72f, bodyH * 0.40f),
    )

    val eyeX = cx - bodyW * 0.22f
    val eyeY = cy - bodyH * 0.12f
    when {
        emotion == Emotion.SLEEPY -> drawLine(
            color = Color(0xFF0B1020),
            start = Offset(eyeX - 10f, eyeY),
            end = Offset(eyeX + 10f, eyeY),
            strokeWidth = 4f,
        )
        emotion == Emotion.HAPPY -> {
            val smile = Path().apply {
                moveTo(eyeX - 10f, eyeY + 5f)
                quadraticTo(eyeX, eyeY - 10f, eyeX + 10f, eyeY + 5f)
            }
            drawPath(smile, Color(0xFF0B1020), style = Stroke(width = 4f))
        }
        else -> drawCircle(Color(0xFF0B1020), radius = 5f, center = Offset(eyeX, eyeY))
    }

    if (phase == Phase.LISTENING || phase == Phase.THINKING || emotion == Emotion.HAPPY) {
        val alpha = (1f - bubble).coerceAtLeast(0f) * 0.5f
        val y = cy - bodyH / 2f - bubble * h * 0.26f
        drawCircle(
            Color.White.copy(alpha = alpha),
            radius = 5f + bubble * 6f,
            center = Offset(cx - bodyW * 0.16f, y),
        )
        drawCircle(
            Color.White.copy(alpha = alpha * 0.7f),
            radius = 3f + bubble * 4f,
            center = Offset(cx - bodyW * 0.02f, y - h * 0.06f),
        )
    }
}
