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
