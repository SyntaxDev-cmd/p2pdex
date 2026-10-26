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
            cache = null
            particles.clear()
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    /** Cenário fixo desenhado uma vez (leve para TV Box); só as partículas se mexem. */
    private var cache: android.graphics.Bitmap? = null
    private val animated = !Ui.isLowRam(ctx)

    private class Particle(
        var x: Float, var y: Float, val r: Float, val speed: Float, val drift: Float,
        val phase: Float, val color: Int, var rot: Float, val spin: Float, val kind: Int,
    )

    private val particles = ArrayList<Particle>()
    private var lastFrame = 0L
    private var time = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache = null
        particles.clear()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) restart()
    }

    /** Volta a animar (ex.: ao reaparecer depois de ficar transparente ou escondido). */
    fun restart() {
        lastFrame = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle()
        cache = null
    }

    override fun onDraw(c: Canvas) {
        val s = style ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        if (!animated || s.id == "sports") {
            drawScene(c, w, h, s)
            return
        }
        val bmp = cache ?: runCatching {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565).also { b ->
                drawScene(Canvas(b), w, h, s)
            }
        }.getOrNull().also { cache = it }
        if (bmp == null) {
            drawScene(c, w, h, s)
            return
        }
        c.drawBitmap(bmp, 0f, 0f, null)
        if (particles.isEmpty()) spawn(w, h, s)
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.033f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        time += dt
        drawParticles(c, w, h, dt, s)
        // ~30 quadros por segundo, só enquanto a seção está na tela
        if (isShown && alpha > 0.01f) postInvalidateDelayed(33) else lastFrame = 0L
    }

    private fun drawScene(c: Canvas, w: Float, h: Float, s: SectionStyle) {
        p.shader = LinearGradient(0f, 0f, w * 0.3f, h, s.bgTop, s.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val rnd = Random(42) // sempre o mesmo desenho
        when (s.id) {
            "kids" -> drawKids(c, w, h, rnd, s, staticOnly = animated)
            "sports" -> drawField(c, w, h)
            "anime" -> drawSakura(c, w, h, rnd, staticOnly = animated)
        }
        // véu escuro embaixo para o conteúdo ficar legível
        p.shader = LinearGradient(0f, h * 0.35f, 0f, h, Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private fun spawn(w: Float, h: Float, s: SectionStyle) {
        val rnd = Random(7)
        val unit = min(w, h)
        if (s.id == "kids") {
            // bolhas que sobem balançando
            repeat(14) { i ->
                particles.add(Particle(rnd.nextFloat() * w, rnd.nextFloat() * h, unit * (0.03f + rnd.nextFloat() * 0.07f),
                    unit * (0.03f + rnd.nextFloat() * 0.05f), unit * (0.01f + rnd.nextFloat() * 0.02f), rnd.nextFloat() * 6.28f,
                    s.palette[i % s.palette.size], 0f, 0f, 0))
            }
            // estrelas que piscam
            repeat(12) {
                particles.add(Particle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.85f, unit * (0.012f + rnd.nextFloat() * 0.018f),
                    0f, 0f, rnd.nextFloat() * 6.28f, Color.rgb(255, 236, 120), rnd.nextFloat() * 360f, 20f + rnd.nextFloat() * 40f, 1))
            }
            // balões
            repeat(3) { i ->
                particles.add(Particle(w * (0.15f + i * 0.33f + rnd.nextFloat() * 0.1f), h * (0.4f + rnd.nextFloat() * 0.6f), unit * 0.05f,
                    unit * (0.02f + rnd.nextFloat() * 0.02f), unit * 0.015f, rnd.nextFloat() * 6.28f, s.palette[(i * 2) % s.palette.size], 0f, 0f, 2))
            }
        } else {
            // pétalas de cerejeira caindo e girando
            repeat(30) {
                val pink = rnd.nextBoolean()
                particles.add(Particle(rnd.nextFloat() * w, rnd.nextFloat() * h, unit * (0.008f + rnd.nextFloat() * 0.014f),
                    unit * (0.04f + rnd.nextFloat() * 0.06f), unit * (0.03f + rnd.nextFloat() * 0.04f), rnd.nextFloat() * 6.28f,
                    if (pink) Color.argb(170, 255, 160, 205) else Color.argb(130, 255, 225, 240), rnd.nextFloat() * 360f, 40f + rnd.nextFloat() * 120f, 3))
            }
            // brilhos neon
            repeat(10) {
                particles.add(Particle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, unit * (0.004f + rnd.nextFloat() * 0.006f),
                    0f, 0f, rnd.nextFloat() * 6.28f, if (rnd.nextBoolean()) s.accent else Color.rgb(39, 215, 255), 0f, 0f, 4))
            }
        }
    }

    private fun drawParticles(c: Canvas, w: Float, h: Float, dt: Float, s: SectionStyle) {
        for (pt in particles) {
            when (pt.kind) {
                0 -> { // bolha
                    pt.y -= pt.speed * dt
                    if (pt.y < -pt.r * 2) { pt.y = h + pt.r * 2; pt.x = (pt.x + w * 0.37f) % w }
                    val x = pt.x + sin(time * 0.9f + pt.phase) * pt.drift
                    p.color = Color.argb(70, Color.red(pt.color), Color.green(pt.color), Color.blue(pt.color))
                    c.drawCircle(x, pt.y, pt.r, p)
                    stroke.color = Color.argb(110, 255, 255, 255)
                    stroke.strokeWidth = pt.r * 0.08f
                    c.drawCircle(x, pt.y, pt.r, stroke)
                    p.color = Color.argb(110, 255, 255, 255)
                    c.drawCircle(x - pt.r * 0.35f, pt.y - pt.r * 0.35f, pt.r * 0.18f, p)
                }
                1 -> { // estrela piscando
                    val a = (0.35f + 0.65f * ((sin(time * 2.2f + pt.phase) + 1f) / 2f))
                    p.color = Color.argb((210 * a).toInt(), 255, 236, 120)
                    pt.rot += pt.spin * dt
                    c.save()
                    c.rotate(pt.rot, pt.x, pt.y)
                    star(c, pt.x, pt.y, pt.r * (0.8f + 0.4f * a))
                    c.restore()
                }
                2 -> { // balão
                    pt.y -= pt.speed * dt
                    if (pt.y < -pt.r * 4) { pt.y = h + pt.r * 3; pt.x = (pt.x + w * 0.29f) % w }
                    val x = pt.x + sin(time * 0.7f + pt.phase) * pt.drift
                    stroke.color = Color.argb(120, 255, 255, 255)
                    stroke.strokeWidth = 2f
                    c.drawLine(x, pt.y + pt.r * 1.2f, x + sin(time + pt.phase) * pt.r * 0.3f, pt.y + pt.r * 3.2f, stroke)
                    p.color = Color.argb(200, Color.red(pt.color), Color.green(pt.color), Color.blue(pt.color))
                    c.drawOval(RectF(x - pt.r, pt.y - pt.r * 1.2f, x + pt.r, pt.y + pt.r * 1.2f), p)
                    p.color = Color.argb(90, 255, 255, 255)
                    c.drawOval(RectF(x - pt.r * 0.55f, pt.y - pt.r * 0.8f, x - pt.r * 0.15f, pt.y - pt.r * 0.2f), p)
                }
                3 -> { // pétala
                    pt.y += pt.speed * dt
                    pt.x += (pt.drift * 0.6f + sin(time * 1.3f + pt.phase) * pt.drift) * dt
                    pt.rot += pt.spin * dt
                    if (pt.y > h + pt.r * 3) { pt.y = -pt.r * 3; pt.x = (pt.x + w * 0.41f) % w }
                    if (pt.x > w + pt.r * 3) pt.x = -pt.r * 3
                    p.color = pt.color
                    c.save()
                    c.rotate(pt.rot, pt.x, pt.y)
                    val squash = 0.55f + 0.45f * kotlin.math.abs(sin(time * 2f + pt.phase))
                    c.drawOval(RectF(pt.x - pt.r * 1.6f, pt.y - pt.r * squash, pt.x + pt.r * 1.6f, pt.y + pt.r * squash), p)
                    c.restore()
                }
                else -> { // brilho neon
                    val a = (sin(time * 3f + pt.phase) + 1f) / 2f
                    p.color = Color.argb((180 * a).toInt(), Color.red(pt.color), Color.green(pt.color), Color.blue(pt.color))
                    c.drawCircle(pt.x, pt.y, pt.r * (1f + a), p)
                }
            }
        }
    }

    private fun drawKids(c: Canvas, w: Float, h: Float, rnd: Random, s: SectionStyle, staticOnly: Boolean = false) {
        val unit = min(w, h)
        // arco-íris suave no alto
        val colors = intArrayOf(0xFFFF5A7A.toInt(), 0xFFFFB020.toInt(), 0xFFFFE14D.toInt(), 0xFF2ECC71.toInt(), 0xFF1EA7FF.toInt(), 0xFFB45CFF.toInt())
        stroke.strokeWidth = unit * 0.03f
        colors.forEachIndexed { i, col ->
            stroke.color = Color.argb(45, Color.red(col), Color.green(col), Color.blue(col))
            val r = unit * (0.75f - i * 0.035f)
            c.drawArc(RectF(w * 0.78f - r, h * 0.55f - r, w * 0.78f + r, h * 0.55f + r), 180f, 180f, false, stroke)
        }
        if (!staticOnly) {
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
        }
        // nuvens no rodapé
        p.color = Color.argb(55, 255, 255, 255)
        var x = -unit * 0.1f
        val crnd = Random(9)
        while (x < w + unit * 0.2f) {
            val r = unit * (0.09f + crnd.nextFloat() * 0.06f)
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

    private fun drawSakura(c: Canvas, w: Float, h: Float, rnd: Random, staticOnly: Boolean = false) {
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
        if (staticOnly) return
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
