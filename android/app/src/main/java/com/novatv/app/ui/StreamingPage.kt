package com.novatv.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import com.novatv.app.data.Tmdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Serviço de streaming (Netflix, Globoplay...): logo, cores e palavras das categorias. */
data class StreamService(val label: String, val logo: String, val bg: Int, val fg: Int, val keys: List<String>)

/**
 * Tela do streaming, aberta por cima da tela inicial: cabeçalho com a marca, filtros
 * Tudo / Filmes / Séries e grade de capas. Voltar fecha e retorna ao Início.
 * Conteúdo: categorias da lista do cliente com o nome do serviço; sem elas, os títulos
 * do serviço no Brasil (TMDB) que existem no catálogo do cliente.
 */
class StreamingPage(act: MainActivity, private val service: StreamService) : Page(act, "stream") {

    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, if (portrait) 10 else 22)
        setPadding(pad, Ui.dp(act, 4), pad, 0)
        clipChildren = false
        clipToPadding = false
    }
    override val view: View = root

    private val count = TextView(act)
    private val filters = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    private val grid = RecyclerView(act)
    private val loading = ProgressBar(act)
    private val empty = TextView(act)
    private val posters = PosterAdapter({ _, item -> open(item) }, onLong = { pos, item -> fav(pos, item) })
    private var all: List<Media> = emptyList()
    private var filter: Kind? = null
    private var job: Job? = null
    private var loaded = false

    init {
        root.addView(header(), LinearLayout.LayoutParams(-1, -2))
        root.addView(filters, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(act, 10); bottomMargin = Ui.dp(act, 6) })
        val content = FrameLayout(act)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val span = ((act.resources.displayMetrics.widthPixels - Ui.dp(act, 44)) / Ui.dp(act, if (portrait) 112 else 132)).coerceIn(3, 9)
        grid.layoutManager = GridLayoutManager(act, span)
        grid.adapter = posters
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 24))
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        content.addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 42), Ui.dp(act, 42), Gravity.CENTER))
        empty.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Ui.text2)
            visibility = View.GONE
            setPadding(Ui.dp(act, 24), 0, Ui.dp(act, 24), 0)
        }
        content.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        buildFilters()
    }

    /** Faixa com a cor da marca, logo em círculo, nome e total de títulos. */
    private fun header(): View {
        val r = Ui.radius(act) * 1.2f
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(act, 14)
            setPadding(p, p, p, p)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.withAlpha(service.bg, 235), Ui.withAlpha(Ui.shade(service.bg, -0.45f), 150), Color.TRANSPARENT)).apply {
                cornerRadius = r
            }
        }
        val size = Ui.dp(act, if (portrait) 54 else 64)
        val circle = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(service.bg)
                setStroke(Ui.dp(act, 2), Ui.withAlpha(Color.WHITE, 120))
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        val initials = TextView(act).apply {
            text = service.label
            gravity = Gravity.CENTER
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(service.fg)
            maxLines = 2
        }
        circle.addView(initials, FrameLayout.LayoutParams(-1, -1))
        if (service.logo.isNotBlank()) {
            val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            circle.addView(img, FrameLayout.LayoutParams(-1, -1))
            img.load(service.logo) { listener(onSuccess = { _, _ -> initials.visibility = View.GONE }) }
        }
        box.addView(circle, LinearLayout.LayoutParams(size, size))
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 14), 0, 0, 0) }
        texts.addView(TextView(act).apply {
            text = service.label
            textSize = if (portrait) 22f else 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        })
        count.apply {
            text = "Carregando títulos..."
            textSize = 13f
            setTextColor(Ui.withAlpha(Color.WHITE, 210))
        }
        texts.addView(count)
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val back = TextView(act).apply {
            text = "‹  Voltar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = Ui.button(act, false)
            setTextColor(Ui.textColors(Color.WHITE, Ui.focusText))
            setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
            setOnClickListener { act.closeOverlay() }
        }
        Ui.focusZoom(back, 1.06f)
        box.addView(back)
        return box
    }

    private fun buildFilters() {
        filters.removeAllViews()
        val movies = all.count { it.kind == Kind.MOVIE }
        val series = all.count { it.kind == Kind.SERIES }
        chip("Tudo" + if (loaded) " (${all.size})" else "", null)
        if (!loaded || movies > 0) chip("Filmes" + if (loaded) " ($movies)" else "", Kind.MOVIE)
        if (!loaded || series > 0) chip("Séries" + if (loaded) " ($series)" else "", Kind.SERIES)
    }

    private fun chip(label: String, kind: Kind?) {
        val selected = filter == kind
        val tv = TextView(act).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            isSelected = selected
            setPadding(Ui.dp(act, 18), Ui.dp(act, 8), Ui.dp(act, 18), Ui.dp(act, 8))
            val r = Ui.dp(act, 40).toFloat()
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.accent, r))
                addState(intArrayOf(android.R.attr.state_selected), Ui.rounded(Ui.withAlpha(service.bg.takeIf { it != Color.WHITE && it != Color.BLACK } ?: Ui.primary, 230), r))
                addState(intArrayOf(), Ui.rounded(Ui.surface, r, Ui.withAlpha(Color.WHITE, 40), Ui.dp(act, 1)))
            }
            setTextColor(Ui.textColors(Color.WHITE, Ui.focusText))
            setOnClickListener {
                filter = kind
                buildFilters()
                apply()
                filters.getChildAt(listOf(null, Kind.MOVIE, Kind.SERIES).indexOf(kind).coerceAtMost(filters.childCount - 1))?.requestFocus()
            }
        }
        Ui.focusZoom(tv, 1.06f)
        filters.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(act, 8) })
    }

    override fun onShow() {
        if (!loaded && job?.isActive != true) load()
        else posters.refreshFavs()
    }

    override fun onHide() {}

    override fun onDestroy() {
        job?.cancel()
    }

    override fun focusContent(): Boolean =
        grid.getChildAt(0)?.requestFocus() ?: filters.getChildAt(0)?.requestFocus() ?: false

    private fun load() {
        job?.cancel()
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        job = act.launch {
            val list = runCatching { fetch() }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emptyList()
            }
            all = list
            loaded = true
            loading.visibility = View.GONE
            count.text = when (all.size) {
                0 -> "Nenhum título encontrado"
                1 -> "1 título na sua lista"
                else -> "${all.size} títulos na sua lista"
            }
            buildFilters()
            apply()
            // foco no primeiro cartaz (controle remoto)
            grid.post { if (!grid.hasFocus()) focusContent() }
        }
    }

    /** Categorias com o nome do serviço; sem elas, TMDB (o que está no serviço e existe no catálogo). */
    private suspend fun fetch(): List<Media> = coroutineScope {
        val cats = listOf(Kind.MOVIE, Kind.SERIES).map { k ->
            async { runCatching { withTimeoutOrNull(30_000) { Repo.categories(k) } }.getOrNull().orEmpty() }
        }.flatMap { it.await() }.filter { !it.locked && it.special == null && Repo.matches(it.name, service.keys) }
        val fromCats = cats.map { c -> async { runCatching { withTimeoutOrNull(30_000) { Repo.items(c) } }.getOrNull().orEmpty() } }
            .flatMap { it.await() }
        val list = if (fromCats.isNotEmpty()) fromCats else {
            val ids = Tmdb.providerIds(service.label)
            if (!Tmdb.enabled || ids.isEmpty()) emptyList()
            else listOf(false, true).map { tv ->
                async { runCatching { withTimeoutOrNull(60_000) { Tmdb.providerInCatalog(ids, tv) } }.getOrNull().orEmpty() }
            }.flatMap { it.await() }
        }
        list.distinctBy { it.key }.sortedByDescending { it.added }
    }

    private fun apply() {
        val list = if (filter == null) all else all.filter { it.kind == filter }
        posters.submit(list.map { PosterAdapter.from(it, showKind = filter == null) })
        grid.scrollToPosition(0)
        empty.visibility = if (loaded && list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (Tmdb.enabled) "Nenhum título de ${service.label} encontrado na sua lista."
        else "Nenhum título de ${service.label} encontrado.\nCrie categorias com o nome do serviço ou ative o TMDB no painel."
    }

    private fun open(item: PosterItem) {
        val m = item.media ?: return
        act.openDetail(m)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun fav(pos: Int, item: PosterItem) {
        val m = item.media ?: return
        val added = Prefs.toggleFavorite(m)
        Dialogs.toast(act, if (added) "♥ Adicionado aos favoritos" else "Removido dos favoritos")
        posters.refreshFavs()
        posters.notifyItemChanged(pos)
    }
}
