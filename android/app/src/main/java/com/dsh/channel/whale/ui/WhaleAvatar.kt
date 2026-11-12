package com.dsh.channel.whale.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dsh.channel.whale.Emotion
import com.dsh.channel.whale.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * 当前选择的是素材包就播动画，否则画内置 Eva。
 *
 * 素材包按 [packEmotion] 取片——通话阶段（待机 / 聆听 / 思考 / 说完）驱动，
 * 与插件下发的 `emotion` 无关；内置 Eva 仍按 `emotion` 改眼形。
 * 内置形象不依赖任何素材文件：任何包被删光、导入失败都不会白屏。
 */
@Composable
fun WhaleAvatar(
    emotion: Emotion,
    phase: Phase,
    pack: AvatarPack?,
    modifier: Modifier = Modifier,
    packEmotion: Emotion = emotion,
) {
    val ref = remember(pack, packEmotion) { pack?.asset(packEmotion) }
    if (ref != null) {
        PackedAvatar(ref = ref, description = "形象：${packEmotion.key}", modifier = modifier)
    } else {
        BuiltInEva(emotion = emotion, phase = phase, modifier = modifier)
    }
}

/**
 * 系统 ImageDecoder 解成 Drawable（多帧动画 → AnimatedImageDrawable），由 Compose 画布自己绘制。
 *
 * 不用 ImageView：View 层绘制在 Compose 之上，画布上的层次管不住它，看起来像贴图。
 * 也不用 Coil：它的解码器链先命中位图解码器，多帧只解出第一帧。
 * 像素画必须关双线性过滤（`setFilterBitmap(false)`），否则放大 5 倍就是一团糊。
 */
@Composable
private fun PackedAvatar(ref: AvatarRef, description: String, modifier: Modifier) {
    val drawable by produceState<Drawable?>(null, ref) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = (ref as AvatarRef.Local).file
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)).also { decoded ->
                    // 素材的 NETSCAPE 循环次数可能是 1，不强制无限循环就只播一遍然后定格。
                    if (decoded is AnimatedImageDrawable) {
                        decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    }
                }
            }.getOrNull()
        }
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
            animationSpec = tween(220),
            label = "fade",
        )
        // 没有 ImageView 就不会有人 invalidate 它，自己给个 callback 推进帧。
        var tick by remember(loaded) { mutableIntStateOf(0) }
        DisposableEffect(loaded) {
            val handler = Handler(Looper.getMainLooper())
            val callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) { tick++ }
                override fun scheduleDrawable(who: Drawable, what: Runnable, whenAt: Long) {
                    handler.postAtTime(what, whenAt)
                }
                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                    handler.removeCallbacks(what)
                }
            }
            loaded.callback = callback
            // 像素画放大要关掉双线性过滤，否则整张图糊成一片。
            loaded.setFilterBitmap(false)
            (loaded as? Animatable)?.start()
            onDispose {
                (loaded as? Animatable)?.stop()
                loaded.callback = null
                handler.removeCallbacksAndMessages(null)
            }
        }
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = description }
                    // 只有淡入那 220 ms 才需要离屏层；常驻 Offscreen 等于每帧多渲染一整屏。
                    .graphicsLayer {
                        alpha = fade
                        if (fade < 0.999f) compositingStrategy = CompositingStrategy.Offscreen
                    },
            ) {
                tick
                // 素材按原比例缩放：宽度占屏 86%、高度最多 55%，比铺满整屏清晰得多；
                // 中心略高于屏幕中线，给底部通话按钮留位置。
                val iw = loaded.intrinsicWidth.takeIf { it > 0 } ?: size.width.toInt()
                val ih = loaded.intrinsicHeight.takeIf { it > 0 } ?: size.height.toInt()
                val scale = minOf(size.width * 0.86f / iw, size.height * 0.55f / ih)
                val dw = iw * scale
                val dh = ih * scale
                val left = (size.width - dw) / 2f
                val top = (size.height - dh) / 2f - size.height * 0.06f
                // 素材本身已经是透明底，直接画即可：以前那层整屏 saveLayer 羽化
                // 每帧都要分配 1280×2800 的离屏缓冲，主线程直接被拖到掉帧。
                drawIntoCanvas { canvas ->
                    loaded.setBounds(
                        left.toInt(),
                        top.toInt(),
                        (left + dw).toInt(),
                        (top + dh).toInt(),
                    )
                    loaded.draw(canvas.nativeCanvas)
                }
            }
        }
    }
}

/**
 * 内置形象 Eva：只画脸。
 *
 * 参照 EVE 的脸：没有嘴、没有边框，只有一双发光的蓝眼睛，整块屏幕是她的面罩。
 * 情绪靠眼形（灯带 / 弯弧 / 倾角）区分，说话时下面浮出几道声波线，
 * 安静时整体极缓地呼吸。没有任何位图素材。
 */
@Composable
private fun BuiltInEva(emotion: Emotion, phase: Phase, modifier: Modifier) {
    val motion = rememberInfiniteTransition(label = "eva")
    val breath by motion.animateFloat(
        initialValue = 0.982f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val blink by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "blink",
    )
    val pulse by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = breath; scaleY = breath },
        ) {
            drawEvaFace(emotion, phase, blink, pulse)
        }
    }
}

// EVE 的眼睛是一块发光的蓝：从外圈辉光到白色内芯，四层叠出发光感。
private val EvaEyeHalo = Color(0xFF2F6BFF)
private val EvaEyeRim = Color(0xFF3F8CFF)
private val EvaEyeMid = Color(0xFF6CC4FF)
private val EvaEyeCore = Color(0xFFDDF3FF)
private val EvaGlow = Color(0xFFD6DCFF)

private fun DrawScope.drawEvaFace(emotion: Emotion, phase: Phase, blink: Float, pulse: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f

    // 参照 EVE：没有嘴、没有边框，只有一双发光的眼睛——整块屏幕就是她的黑色面罩。
    // 神情全部由「灯带形状 + 倾角 + 辉光」表达。
    // 眨眼：0.94~1 的这一段把眼睛压扁，其余时间正常睁开。
    val lid = if (blink > 0.94f) 1f - (blink - 0.94f) / 0.06f else 1f
    val eyeY = h * 0.40f
    val gap = w * 0.205f
    val eyeLen = w * 0.25f
    val eyeH = w * 0.10f
    val left = Offset(cx - gap, eyeY)
    val right = Offset(cx + gap, eyeY)

    when (emotion) {
        Emotion.HAPPY -> {
            // 参考图 3／4：弯成两道笑弧，是 EVE 最有辨识度的表情。
            arcEye(left, eyeLen, eyeH * lid * 0.33f, tilt = 0.06f, lift = -0.44f)
            arcEye(right, eyeLen, eyeH * lid * 0.33f, tilt = -0.06f, lift = -0.44f)
        }
        Emotion.SAD -> {
            // 光斑内端抬起、外端下垂。
            bar(left, eyeLen * 0.92f, eyeH * lid * 0.84f, -11f)
            bar(right, eyeLen * 0.92f, eyeH * lid * 0.84f, 11f)
        }
        Emotion.ANGRY -> {
            bar(left, eyeLen, eyeH * lid * 0.58f, 16f)
            bar(right, eyeLen, eyeH * lid * 0.58f, -16f)
        }
        Emotion.SURPRISED -> {
            // 参考图 2：瞪成两块短而更亮的光斑。
            bar(left, eyeLen * 0.66f, eyeH * lid * 1.28f, 0f)
            bar(right, eyeLen * 0.66f, eyeH * lid * 1.28f, 0f)
        }
        Emotion.THINKING -> {
            // 参考图 4 的侧看：一眼弯成弧、一眼抬高偏侧。
            arcEye(left, eyeLen * 0.78f, eyeH * lid * 0.30f, tilt = 0.05f, lift = -0.30f)
            arcEye(
                Offset(right.x + eyeLen * 0.05f, right.y - eyeH * 0.55f),
                eyeLen * 1.05f,
                eyeH * lid * 0.34f,
                tilt = -0.12f,
                lift = -0.52f,
            )
        }
        Emotion.SLEEPY -> {
            bar(left, eyeLen * 0.95f, eyeH * lid * 0.32f, 0f)
            bar(right, eyeLen * 0.95f, eyeH * lid * 0.32f, 0f)
        }
        Emotion.ERROR -> {
            cross(left, eyeLen * 0.36f, eyeH * 0.26f)
            cross(right, eyeLen * 0.36f, eyeH * 0.26f)
        }
        else -> {
            // 没有情绪时：两块圆润的蓝色光斑，不是细线（对齐最后这张参考图）。
            bar(left, eyeLen, eyeH * lid, 0f)
            bar(right, eyeLen, eyeH * lid, 0f)
        }
    }

    // 说话/监听时下面浮出声波，幅度跟着 pulse 走。
    if (phase == Phase.SPEAKING || phase == Phase.LISTENING) {
        val baseY = h * 0.745f
        val amp = if (phase == Phase.SPEAKING) h * 0.055f else h * 0.030f
        val alpha = if (phase == Phase.SPEAKING) 0.75f else 0.45f
        for (i in -1..1) {
            val level = when (i) {
                0 -> amp
                else -> amp * (0.55f + 0.45f * pulse)
            }
            drawLine(
                color = EvaGlow.copy(alpha = alpha * (1f - 0.35f * kotlin.math.abs(i))),
                start = Offset(cx + i * w * 0.055f, baseY - level),
                end = Offset(cx + i * w * 0.055f, baseY + level),
                strokeWidth = w * 0.010f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 一条灯带眼：中心点 + 长度 + 厚度 + 倾角（度）。
 *
 * 由外到内叠四层（大范围暗辉光 → 边缘光 → 主体亮蓝 → 白色内芯），
 * 不开真正的模糊，靠层次骗出 EVE 那种 LED 发光感。
 */
private fun DrawScope.bar(center: Offset, len: Float, thick: Float, tiltDeg: Float) {
    if (len <= 1f || thick <= 1f) return
    rotate(tiltDeg, center) {
        val hw = len / 2f
        val hh = thick / 2f
        barLayer(center, hw, hh, EvaEyeHalo, 1.48f, 0.09f)
        barLayer(center, hw, hh, EvaEyeRim, 1.20f, 0.22f)
        barLayer(center, hw, hh, EvaEyeMid, 1.02f, 0.95f)
        barLayer(center, hw, hh, EvaEyeCore, 0.46f, 0.38f)
    }
}

private fun DrawScope.barLayer(
    center: Offset,
    hw: Float,
    hh: Float,
    color: Color,
    scale: Float,
    alpha: Float,
) {
    val r = hh * scale
    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(center.x - hw * scale, center.y - r),
        size = Size(hw * 2f * scale, r * 2f),
        cornerRadius = CornerRadius(r, r),
    )
}

/** 一道弯起来的灯带眼（笑眼用）：倾角是弧度，lift 是弧顶偏移（相对长度）。 */
private fun DrawScope.arcEye(
    center: Offset,
    len: Float,
    thick: Float,
    tilt: Float,
    lift: Float,
) {
    if (thick <= 0.5f) return
    val half = len / 2f
    val dx = cos(tilt) * half
    val dy = sin(tilt) * half
    val path = Path().apply {
        moveTo(center.x - dx, center.y - dy)
        quadraticTo(center.x, center.y + len * lift, center.x + dx, center.y + dy)
    }
    drawPath(path, EvaEyeHalo.copy(alpha = 0.12f), style = Stroke(width = thick * 2.6f, cap = StrokeCap.Round))
    drawPath(path, EvaEyeRim.copy(alpha = 0.28f), style = Stroke(width = thick * 1.6f, cap = StrokeCap.Round))
    drawPath(path, EvaEyeMid, style = Stroke(width = thick, cap = StrokeCap.Round))
    drawPath(path, EvaEyeCore.copy(alpha = 0.65f), style = Stroke(width = thick * 0.36f, cap = StrokeCap.Round))
}

private fun DrawScope.cross(center: Offset, r: Float, thick: Float) {
    val passes = listOf(
        Triple(EvaEyeHalo.copy(alpha = 0.12f), thick * 3.2f, 1f),
        Triple(EvaEyeRim.copy(alpha = 0.30f), thick * 1.8f, 1f),
        Triple(EvaEyeMid, thick, 1f),
    )
    for ((color, width, _) in passes) {
        drawLine(
            color,
            Offset(center.x - r, center.y - r),
            Offset(center.x + r, center.y + r),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(center.x - r, center.y + r),
            Offset(center.x + r, center.y - r),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    }
}
