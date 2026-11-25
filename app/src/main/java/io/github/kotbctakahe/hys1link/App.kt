package io.github.kotbctakahe.hys1link

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.net.Http

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        io.github.kotbctakahe.hys1link.core.CrashLog.install(this)
        Core.get(this)
    }

    /** Images from img.linkm.me use the same private CA bundle as the chat server. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client(this) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
        .components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
