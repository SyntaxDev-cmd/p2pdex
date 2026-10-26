package com.novatv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.novatv.app.data.AppConfig
import com.novatv.app.data.CrashReporter
import com.novatv.app.data.Net
import com.novatv.app.data.Prefs
import com.novatv.app.ui.Ui

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        Prefs.init(this)
        AppConfig.load(this)
        Net.init(this)
        Ui.loadCachedTheme()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // app em segundo plano ou sistema com pouca memória: libera catálogos grandes
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) com.novatv.app.data.Repo.trimMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.novatv.app.data.Repo.trimMemory()
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
