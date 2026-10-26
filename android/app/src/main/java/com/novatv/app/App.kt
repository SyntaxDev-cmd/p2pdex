package com.novatv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.novatv.app.data.Net
import com.novatv.app.data.Prefs
import com.novatv.app.ui.Ui

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Net.init(this)
        Ui.loadCachedTheme()
    }

    override fun newImageLoader(): ImageLoader {
        val lowRam = Ui.isLowRam(this)
        return ImageLoader.Builder(this)
            .okHttpClient(Net.images)
            .crossfade(if (lowRam) 0 else 180)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.12 else 0.22)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img"))
                    .maxSizeBytes(if (lowRam) 60L * 1024 * 1024 else 200L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
