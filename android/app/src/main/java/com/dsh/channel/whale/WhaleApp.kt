package com.dsh.channel.whale

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder

/**
 * 全局 ImageLoader：必须在这里注册动图解码器。
 * 只靠 ImageRequest 的 decoderFactory 时，多帧 gif 只会解出第一帧（看着就是静态图）。
 */
class WhaleApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ImageDecoderDecoder.Factory()) }
        .build()
}
