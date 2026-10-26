package com.novatv.app.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import coil.load
import com.novatv.app.data.PanelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

abstract class BaseActivity : Activity(), CoroutineScope by MainScope() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Ui.scaledContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.takeIf { keepScreenOn } ?: 0)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    open val keepScreenOn: Boolean = false

    /** Tela cheia sem barras. Telas com digitação no celular desligam para o teclado não cobrir os campos. */
    open val immersiveMode: Boolean = true

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveMode) immersive()
    }

    @Suppress("DEPRECATION")
    protected fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    /** Fundo do app conforme o painel: degradê, cor sólida ou imagem. */
    protected fun applyBackground(bg: ImageView?) {
        val cfg = PanelConfig.current
        window.setBackgroundDrawable(
            if (cfg.backgroundMode == "solid") android.graphics.drawable.ColorDrawable(Ui.bg1) else Ui.backgroundGradient()
        )
        if (bg == null) return
        if (cfg.backgroundMode == "image" && cfg.background.isNotEmpty()) {
            bg.visibility = View.VISIBLE
            bg.load(cfg.background) { crossfade(300) }
        } else {
            bg.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}
