package com.novatv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Identidade visual de cada seção (Kids, Jogos, Anime). */
data class SectionStyle(
    val id: String,
    val title: String,
    val bgTop: Int,
    val bgBottom: Int,
    val accent: Int,
    /** cores alegres usadas em cartões e categorias */
    val palette: IntArray,
    val focusText: Int = Color.WHITE,
)

object Sections {
    val kids = SectionStyle(
        id = "kids", title = "🧸 Mundo Kids",
        bgTop = 0xFF2EC5FF.toInt(), bgBottom = 0xFF7B4DFF.toInt(), accent = 0xFFFFD233.toInt(),
        palette = intArrayOf(0xFFFF5A7A.toInt(), 0xFFFFB020.toInt(), 0xFF2ECC71.toInt(), 0xFF1EA7FF.toInt(), 0xFFB45CFF.toInt(), 0xFFFF7A3D.toInt()),
        focusText = 0xFF2B1460.toInt(),
    )
    val sports = SectionStyle(
        id = "sports", title = "⚽ Jogos do dia",
        bgTop = 0xFF0B6B35.toInt(), bgBottom = 0xFF02160B.toInt(), accent = 0xFFB6FF3B.toInt(),
        palette = intArrayOf(0xFF19C463.toInt(), 0xFF0FA3B1.toInt(), 0xFFF5B800.toInt(), 0xFFE53935.toInt()),
        focusText = 0xFF06210F.toInt(),
    )
    val anime = SectionStyle(
        id = "anime", title = "🌸 Anime",
        bgTop = 0xFF2A0B4F.toInt(), bgBottom = 0xFF0B0418.toInt(), accent = 0xFFFF5FB4.toInt(),
        palette = intArrayOf(0xFFFF5FB4.toInt(), 0xFF8A5CFF.toInt(), 0xFF27D7FF.toInt(), 0xFFFFC94D.toInt()),
        focusText = 0xFF2A0B4F.toInt(),
    )

    fun of(tabId: String): SectionStyle? = when (tabId) {
        "kids" -> kids
        "sports" -> sports
        "anime" -> anime
        else -> null
    }
}

/**
 * Fundo ilustrado desenhado por código (sem imagens: leve até em TV Box simples).
 * Kids: céu com bolhas coloridas e estrelas · Jogos: gramado com linhas do campo e refletores ·
 * Anime: noite neon com pétalas de cerejeira.
 */
class SectionBackdrop(ctx: Context) : View(ctx) {
    var style: SectionStyle? = null
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val s = style ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        p.shader = LinearGradient(0f, 0f, w * 0.3f, h, s.bgTop, s.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val rnd = Random(42) // sempre o mesmo desenho
        when (s.id) {
            "kids" -> drawKids(c, w, h, rnd, s)
            "sports" -> drawField(c, w, h)
            "anime" -> drawSakura(c, w, h, rnd)
        }
        // véu escuro embaixo para o conteúdo ficar legível
        p.shader = LinearGradient(0f, h * 0.35f, 0f, h, Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private fun drawKids(c: Canvas, w: Float, h: Float, rnd: Random, s: SectionStyle) {
        val unit = min(w, h)
        // bolhas
        repeat(16) { i ->
            val color = s.palette[i % s.palette.size]
            val r = unit * (0.04f + rnd.nextFloat() * 0.11f)
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r, Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
            p.shader = null
            p.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.18f, p)
        }
        // estrelas
        p.color = Color.argb(150, 255, 236, 120)
        repeat(14) {
            star(c, rnd.nextFloat() * w, rnd.nextFloat() * h * 0.8f, unit * (0.012f + rnd.nextFloat() * 0.02f))
        }
        // nuvens no rodapé
        p.color = Color.argb(55, 255, 255, 255)
        var x = -unit * 0.1f
        while (x < w + unit * 0.2f) {
            val r = unit * (0.09f + rnd.nextFloat() * 0.06f)
            c.drawCircle(x, h + r * 0.35f, r, p)
            x += r * 1.3f
        }
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = Math.PI / 5 * i - Math.PI / 2
            val x = cx + (rad * cos(a)).toFloat()
            val y = cy + (rad * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun drawField(c: Canvas, w: Float, h: Float) {
        // faixas do gramado
        val stripes = 10
        p.color = Color.argb(28, 255, 255, 255)
        for (i in 0 until stripes step 2) c.drawRect(w / stripes * i, 0f, w / stripes * (i + 1), h, p)
        // linhas do campo
        stroke.color = Color.argb(60, 255, 255, 255)
        stroke.strokeWidth = min(w, h) * 0.006f
        val m = min(w, h) * 0.06f
        c.drawRect(m, m, w - m, h - m, stroke)
        c.drawLine(w / 2, m, w / 2, h - m, stroke)
        c.drawCircle(w / 2, h / 2, min(w, h) * 0.16f, stroke)
        val boxH = h * 0.5f
        val boxW = w * 0.12f
        c.drawRect(m, h / 2 - boxH / 2, m + boxW, h / 2 + boxH / 2, stroke)
        c.drawRect(w - m - boxW, h / 2 - boxH / 2, w - m, h / 2 + boxH / 2, stroke)
        c.drawArc(RectF(m + boxW - h * 0.08f, h / 2 - h * 0.1f, m + boxW + h * 0.08f, h / 2 + h * 0.1f), -90f, 180f, false, stroke)
        c.drawArc(RectF(w - m - boxW - h * 0.08f, h / 2 - h * 0.1f, w - m - boxW + h * 0.08f, h / 2 + h * 0.1f), 90f, 180f, false, stroke)
        // luz dos refletores
        p.shader = RadialGradient(w * 0.15f, 0f, w * 0.5f, Color.argb(70, 255, 255, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.85f, 0f, w * 0.5f, Color.argb(70, 255, 255, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private fun drawSakura(c: Canvas, w: Float, h: Float, rnd: Random) {
        val unit = min(w, h)
        // lua neon
        p.shader = RadialGradient(w * 0.82f, h * 0.2f, unit * 0.32f, Color.argb(120, 255, 95, 180), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.color = Color.argb(60, 255, 210, 240)
        c.drawCircle(w * 0.82f, h * 0.2f, unit * 0.09f, p)
        // grade sutil "cyber"
        stroke.color = Color.argb(18, 138, 92, 255)
        stroke.strokeWidth = 1f
        val step = unit * 0.08f
        var x = 0f
        while (x < w) { c.drawLine(x, h * 0.55f, x + (x - w / 2) * 0.6f, h, stroke); x += step }
        var y = h * 0.55f
        while (y < h) { c.drawLine(0f, y, w, y, stroke); y += step * 0.6f }
        // pétalas
        repeat(26) {
            val px = rnd.nextFloat() * w
            val py = rnd.nextFloat() * h
            val r = unit * (0.008f + rnd.nextFloat() * 0.016f)
            p.color = if (rnd.nextBoolean()) Color.argb(150, 255, 160, 205) else Color.argb(110, 255, 220, 235)
            c.save()
            c.rotate(rnd.nextFloat() * 360f, px, py)
            c.drawOval(RectF(px - r * 1.6f, py - r, px + r * 1.6f, py + r), p)
            c.restore()
        }
    }
}
