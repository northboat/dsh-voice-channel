package com.dsh.channel.whale.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.channel.whale.Emotion
import com.dsh.channel.whale.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前形象包有该情绪的素材就播对应动画，否则回退内置程序化鲸鱼。
 * 回退动画本身也按 emotion / phase 变，所以哪怕包是空的也不是一张死图。
 */
@Composable
fun WhaleAvatar(emotion: Emotion, phase: Phase, pack: AvatarPack?, modifier: Modifier = Modifier) {
    val ref = remember(pack, emotion) { pack?.asset(emotion) }
    if (ref != null) {
        PackedAvatar(ref = ref, description = "形象：${emotion.key}", modifier = modifier)
    } else {
        ProgrammaticWhale(emotion = emotion, phase = phase, modifier = modifier)
    }
}

/**
 * 系统 ImageDecoder 直接解成 Drawable（多帧 gif → AnimatedImageDrawable），交给 ImageView 播。
 *
 * 这里不用 Coil：它的解码器链会先匹配到位图解码器，多帧 gif 只解出第一帧
 * （实测拿到的是 BitmapDrawable），显式挂 decoderFactory 也没绕过去。
 */
@Composable
private fun PackedAvatar(ref: AvatarRef, description: String, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, ref) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = when (ref) {
                    is AvatarRef.Asset -> ImageDecoder.createSource(context.assets, ref.path)
                    is AvatarRef.Local -> ImageDecoder.createSource(ref.file)
                }
                ImageDecoder.decodeDrawable(source).also { drawable ->
                    // 素材的 NETSCAPE 循环次数可能是 1，不强制无限循环就只播一遍然后定格。
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    }
                }
            }.getOrNull()
        }
        // 静态图也能跑这个调用，AnimatedImageDrawable 才会真的开始播放。
        (value as? Animatable)?.start()
    }

    // 换情绪时新素材要解码几十毫秒，这期间先留着上一张，否则画面会闪空。
    val shown = remember { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(drawable) {
        if (drawable != null) {
            (shown.value as? Animatable)?.takeIf { it.isRunning }?.stop()
            shown.value = drawable
        }
    }

    val loaded = shown.value
    if (loaded != null) {
        // 素材本身是多帧 gif，不再叠缩放/位移，否则整张卡片会跟着抖；只做切换淡入。
        var visible by remember(loaded) { mutableStateOf(false) }
        LaunchedEffect(loaded) { visible = true }
        val fade by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(180),
            label = "fade",
        )
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = description
                }
            },
            update = { view ->
                if (view.drawable !== loaded) {
                    view.setImageDrawable(loaded)
                    // 解码时还没有 callback，start() 不会真的跑；挂到 ImageView 后再启一次。
                    (loaded as? Animatable)?.start()
                }
            },
            modifier = modifier.graphicsLayer { alpha = fade },
        )
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
