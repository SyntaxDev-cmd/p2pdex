package com.novatv.app.ui

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import com.novatv.app.App
import com.novatv.app.data.PanelConfig
import kotlin.math.min

/** Tema dinâmico (cores do painel), escala automática e efeitos de foco. */
object Ui {

    // ---- cores atuais (preenchidas a partir do painel)
    var primary = Color.parseColor("#E1061B"); private set
    var accent = Color.WHITE; private set
    var focusText = Color.parseColor("#E1061B"); private set
    var bg1 = Color.parseColor("#8E0A16"); private set
    var bg2 = Color.parseColor("#2B0206"); private set
    var surface = Color.argb(90, 0, 0, 0); private set
    var surfaceSolid = Color.BLACK; private set
    var text = Color.WHITE; private set
    var text2 = Color.parseColor("#F3C9CD"); private set
    var kids = Color.parseColor("#FFB300"); private set
    var radiusDp = 12; private set
    var scale = 100; private set

    val isTv: Boolean by lazy { detectTv(App.instance) }

    private fun parse(c: String, def: Int): Int = try {
        Color.parseColor(c)
    } catch (e: IllegalArgumentException) {
        def
    }

    fun loadCachedTheme() = apply(PanelConfig.current)

    fun apply(cfg: PanelConfig) {
        primary = parse(cfg.color("primary", "#E1061B"), primary)
        accent = parse(cfg.color("accent", "#FFFFFF"), accent)
        focusText = parse(cfg.color("focus_text", "#E1061B"), focusText)
        bg1 = parse(cfg.color("bg1", "#8E0A16"), bg1)
        bg2 = parse(cfg.color("bg2", "#2B0206"), bg2)
        surfaceSolid = parse(cfg.color("surface", "#000000"), Color.BLACK)
        surface = withAlpha(surfaceSolid, (cfg.surfaceOpacity.coerceIn(0, 100) * 255) / 100)
        text = parse(cfg.color("text", "#FFFFFF"), text)
        text2 = parse(cfg.color("text2", "#F3C9CD"), text2)
        kids = parse(cfg.color("kids", "#FFB300"), kids)
        radiusDp = cfg.radius.coerceIn(0, 32)
        scale = cfg.scale.coerceIn(80, 130)
    }

    fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    // ---------------------------------------------------------- dispositivo

    private fun detectTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.software.leanback_only")) return true
        // TV Box genérica: sem touchscreen
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun isLowRam(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice || am.memoryClass <= 128
    }

    /**
     * Normaliza a densidade: em TV a interface é desenhada para 540dp de altura
     * (igual em 720p, 1080p ou 4K). No celular mantém a densidade do sistema.
     * Aplica também a escala escolhida no painel.
     */
    fun scaledContext(base: Context): Context {
        val res = base.resources
        val dm = res.displayMetrics
        val cfg = Configuration(res.configuration)
        var dpi = dm.densityDpi.toFloat()
        val tv = isTv
        if (tv) {
            val h = min(dm.widthPixels, dm.heightPixels).toFloat()
            if (h > 0) dpi = h / 540f * 160f
        }
        dpi *= scale / 100f
        val newDpi = dpi.toInt().coerceIn(96, 720)
        if (newDpi == cfg.densityDpi) return base
        val factor = 160f / newDpi
        cfg.densityDpi = newDpi
        cfg.screenWidthDp = (dm.widthPixels * factor).toInt()
        cfg.screenHeightDp = (dm.heightPixels * factor).toInt()
        cfg.smallestScreenWidthDp = min(cfg.screenWidthDp, cfg.screenHeightDp)
        if (tv) cfg.fontScale = 1f
        return base.createConfigurationContext(cfg)
    }

    // ---------------------------------------------------------- medidas

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())
    fun radius(ctx: Context): Float = dp(ctx, radiusDp).toFloat()

    // ---------------------------------------------------------- drawables

    fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun backgroundGradient(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bg1, bg2))

    fun panel(ctx: Context): Drawable = rounded(surface, radius(ctx))

    /** Fundo de item de lista: foco (controle remoto) > selecionado > normal. */
    fun itemBg(ctx: Context, selectedColor: Int = withAlpha(primary, 150), focusColor: Int = accent, radius: Float = radius(ctx) * 0.7f): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(focusColor, radius))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(withAlpha(focusColor, 200), radius))
            addState(intArrayOf(android.R.attr.state_selected), rounded(selectedColor, radius))
            addState(intArrayOf(android.R.attr.state_activated), rounded(selectedColor, radius))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, radius))
        }

    /** Pílula das abas superiores (selecionada = contorno, foco = preenchida). */
    fun tabBg(ctx: Context): Drawable {
        val r = dp(ctx, 40).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(accent, r))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(withAlpha(accent, 200), r))
            addState(intArrayOf(android.R.attr.state_selected), rounded(Color.TRANSPARENT, r, text, dp(ctx, 2)))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, r))
        }
    }

    /** Borda de foco para cartazes. */
    fun posterFrame(ctx: Context, focusColor: Int = accent): Drawable {
        val r = radius(ctx)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, r, focusColor, dp(ctx, 3)))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(withAlpha(Color.WHITE, 40), r, focusColor, dp(ctx, 3)))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, r))
        }
    }

    fun button(ctx: Context, filled: Boolean): Drawable {
        val r = dp(ctx, 40).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(accent, r))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(withAlpha(accent, 210), r))
            addState(intArrayOf(), if (filled) rounded(primary, r) else rounded(withAlpha(Color.WHITE, 30), r, withAlpha(Color.WHITE, 90), dp(ctx, 1)))
        }
    }

    fun iconButton(ctx: Context): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(accent, 200)) })
        addState(intArrayOf(), GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(surface)
            setStroke(dp(ctx, 1), withAlpha(Color.WHITE, 50))
        })
    }

    fun inputBg(ctx: Context): Drawable {
        val r = radius(ctx)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(withAlpha(Color.BLACK, 120), r, accent, dp(ctx, 2)))
            addState(intArrayOf(), rounded(withAlpha(Color.BLACK, 90), r, withAlpha(Color.WHITE, 60), dp(ctx, 1)))
        }
    }

    /** Texto: em foco usa a cor de texto em foco. */
    fun textColors(normal: Int = text, focused: Int = focusText): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(focused, focused, normal),
    )

    // ---------------------------------------------------------- efeitos

    private val interp = DecelerateInterpolator()

    /** Zoom suave + elevação ao receber foco (efeito "TV moderna"). */
    fun focusZoom(v: View, zoom: Float = 1.06f, extra: ((Boolean) -> Unit)? = null) {
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) zoom else 1f
            view.animate().scaleX(s).scaleY(s).translationZ(if (has) dp(view.context, 8).toFloat() else 0f)
                .setDuration(160).setInterpolator(interp).start()
            extra?.invoke(has)
        }
    }

    fun clipRounded(v: View, radiusPx: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        v.clipToOutline = true
    }

    fun fadeIn(v: View, duration: Long = 220) {
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(duration).start()
    }
}
