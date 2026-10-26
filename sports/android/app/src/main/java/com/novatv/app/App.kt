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
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        installGuard()
        Prefs.init(this)
        AppConfig.load(this)
        Net.init(this)
        Ui.loadCachedTheme()
    }

    /** Tela em primeiro plano (para fechar só ela se um erro acontecer ao abri-la). */
    @Volatile
    private var top: android.app.Activity? = null

    /**
     * Rede de segurança: um erro inesperado na tela (toque, lista, animação) é registrado e
     * enviado ao painel, mas NÃO fecha o app. Se o erro for ao abrir uma tela, fecha só ela.
     */
    private fun installGuard() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) { top = a }
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) { top = a }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) { if (top === a) top = null }
        })
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            var errors = 0
            var windowStart = 0L
            while (true) {
                try {
                    android.os.Looper.loop()
                } catch (e: Throwable) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - windowStart > 10_000) { windowStart = now; errors = 0 }
                    errors++
                    CrashReporter.nonFatal(e)
                    // muitos erros seguidos: deixa o sistema tratar (evita tela travada em laço)
                    if (errors > 6) throw e
                    val trace = android.util.Log.getStackTraceString(e)
                    val inLifecycle = listOf("performLaunchActivity", "handleLaunchActivity", "performCreate", "performResume", "handleResumeActivity", "performStart")
                        .any { trace.contains(it) }
                    val act = top
                    if (inLifecycle && act != null && !act.isFinishing) runCatching { act.finish() }
                    runCatching { android.widget.Toast.makeText(this, "Ops! Algo não saiu como esperado. Tente de novo.", android.widget.Toast.LENGTH_SHORT).show() }
                    // envia o relatório ao painel já, sem esperar a próxima abertura
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { CrashReporter.sendPending(this@App) } }
                }
            }
        }
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
