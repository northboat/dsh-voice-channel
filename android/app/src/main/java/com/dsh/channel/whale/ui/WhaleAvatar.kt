package com.dsh.channel.whale.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dsh.channel.whale.Emotion
import com.dsh.channel.whale.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 形象只有一种：内置的 Deepy 素材包。
 *
 * 素材按 [packEmotion] 取片——通话阶段（待机 / 聆听 / 思考 / 说完）驱动，
 * 与插件下发的 `emotion` 无关。包文件由 [AvatarPacks] 在首次启动时从 assets 复制，
 * 所以正常情况下 [pack] 不会为空；真取不到就画空白，不画别的形象兜底。
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


