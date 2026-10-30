package com.novatv.app.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.novatv.app.R

/** FrameLayout com proporção fixa (largura manda). ratio = altura / largura. */
class AspectFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    var ratio: Float = 9f / 16f
        set(v) {
            field = v
            requestLayout()
        }

    init {
        if (attrs != null) {
            val a = ctx.obtainStyledAttributes(attrs, R.styleable.AspectFrame)
            ratio = a.getFloat(R.styleable.AspectFrame_ratio, ratio)
            a.recycle()
        }
    }

    /** Altura fixa em px (ignora a proporção). 0 = usa a proporção. */
    var fixedHeight: Int = 0
        set(v) {
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (fixedHeight > 0) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY))
            return
        }
        val h = (w * ratio).toInt()
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hMax = MeasureSpec.getSize(heightMeasureSpec)
        if (hMode != MeasureSpec.UNSPECIFIED && h > hMax && hMax > 0) {
            // não cabe na altura: limita pela altura e reduz a largura
            val nw = (hMax / ratio).toInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(nw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hMax, MeasureSpec.EXACTLY))
            setMeasuredDimension(nw, hMax)
            return
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/**
 * ScrollView à prova do erro clássico de TV Box ("parameter must be a descendant of this view"):
 * acontece quando o item focado sai da tela durante uma atualização e o ScrollView tenta rolar até ele.
 */
class SafeScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : android.widget.ScrollView(ctx, attrs) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        try {
            super.onSizeChanged(w, h, oldw, oldh)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun requestChildFocus(child: android.view.View?, focused: android.view.View?) {
        try {
            super.requestChildFocus(child, focused)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun requestChildRectangleOnScreen(child: android.view.View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean =
        try {
            super.requestChildRectangleOnScreen(child, rectangle, immediate)
        } catch (e: IllegalArgumentException) {
            false
        }
}

class SafeHorizontalScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : android.widget.HorizontalScrollView(ctx, attrs) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        try {
            super.onSizeChanged(w, h, oldw, oldh)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun requestChildFocus(child: android.view.View?, focused: android.view.View?) {
        try {
            super.requestChildFocus(child, focused)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun requestChildRectangleOnScreen(child: android.view.View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean =
        try {
            super.requestChildRectangleOnScreen(child, rectangle, immediate)
        } catch (e: IllegalArgumentException) {
            false
        }
}

/**
 * Capa gerada para canais/conteúdos sem imagem (ou com imagem quebrada):
 * fundo em degradê com cor fixa por nome + iniciais grandes. Nada fica "em branco".
 */
class LetterTile(name: String, private val emoji: String = "") : android.graphics.drawable.Drawable() {
    private val clean = name.replace(Regex("""[\[\(].*?[\]\)]|\b(FHD|HD|SD|4K|UHD|H265|HEVC)\b|[|•:_\-]+"""), " ").trim()
    private val text = emoji.ifEmpty {
        clean.split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
            .take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }
    }
    private val hue = (Math.abs(clean.lowercase().hashCode()) % 360).toFloat()
    private val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))
    private val c2 = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.75f, 0.45f))
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 2f, 0x66000000)
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.isEmpty) return
        bg.shader = android.graphics.LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), c1, c2, android.graphics.Shader.TileMode.CLAMP,
        )
        val r = minOf(b.width(), b.height()) * 0.12f
        canvas.drawRoundRect(android.graphics.RectF(b), r, r, bg)
        fg.textSize = minOf(b.width(), b.height()) * (if (text.length > 1 && emoji.isEmpty()) 0.36f else 0.42f)
        val y = b.exactCenterY() - (fg.descent() + fg.ascent()) / 2f
        canvas.drawText(text, b.exactCenterX(), y, fg)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fg.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 300
    override fun getIntrinsicHeight() = 225
}
