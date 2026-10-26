package com.novatv.app.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.view.WindowInsetsCompat.Type
import coil.load
import com.novatv.app.data.PanelConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus

/**
 * Base das telas. As tarefas em segundo plano usam um escopo com tratador de erros:
 * um erro inesperado é registrado (e enviado ao painel) em vez de fechar o app.
 */
abstract class BaseActivity : Activity(), CoroutineScope by MainScope() + CoroutineExceptionHandler({ _, e ->
    com.novatv.app.data.CrashReporter.nonFatal(e)
}) {

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

    /** Afasta o conteúdo do entalhe (notch), das barras do sistema e do teclado. O player usa a tela toda. */
    open val fitInsets: Boolean = true

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!fitInsets) return
        val content = findViewById<View>(android.R.id.content) ?: return
        // Android 15+ desenha sempre de borda a borda: sem isso, textos ficam sob as barras/entalhe
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val none = androidx.core.graphics.Insets.NONE
            // tela cheia: só o entalhe; com barras visíveis, até o Android 14 o sistema já ajusta sozinho
            val edgeToEdge = Build.VERSION.SDK_INT >= 35 && !immersiveMode
            val cut = if (immersiveMode) insets.getInsets(Type.displayCutout()) else none
            val bars = if (edgeToEdge) insets.getInsets(Type.systemBars() or Type.displayCutout()) else none
            val ime = if (edgeToEdge) insets.getInsets(Type.ime()) else none
            v.setPadding(
                maxOf(cut.left, bars.left),
                maxOf(cut.top, bars.top),
                maxOf(cut.right, bars.right),
                maxOf(cut.bottom, bars.bottom, ime.bottom),
            )
            insets
        }
        content.requestApplyInsets()
    }

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
