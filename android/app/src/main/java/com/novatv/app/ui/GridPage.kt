package com.novatv.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novatv.app.R
import com.novatv.app.data.Category
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Filmes, Séries, Kids e Anime: categorias à esquerda (ou chips no celular) + grade de cartazes. */
class GridPage(act: MainActivity, id: String, private val mode: String) : Page(act, id) {

    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_grid, act.pagesContainer, false)

    private val catList: RecyclerView = view.findViewById(R.id.catList)
    private val grid: RecyclerView = view.findViewById(R.id.grid)
    private val loading: ProgressBar = view.findViewById(R.id.loading)
    private val empty: TextView = view.findViewById(R.id.empty)
    private val kids = mode == "kids"
    private val section: SectionStyle? = Sections.of(mode)
    private val accent = section?.accent ?: Ui.accent

    private val cats = CategoryAdapter(portrait, ::onCategory, onRight = {
        (current?.special == "home" && homeView?.focus() == true) || focusGrid()
    })
    private val posters = PosterAdapter(::onItem, accentColor = accent, onLong = ::onLong)
    private val layout = GridLayoutManager(act, spanGuess())
    private var loaded = false
    private var job: Job? = null
    private var current: Category? = null
    private var homeView: HomeView? = null
    private var pendingOpen: Category? = null
    private val pageKind: Kind? = when (mode) {
        "movies" -> Kind.MOVIE
        "series" -> Kind.SERIES
        else -> null
    }

    init {
        view.findViewById<View>(R.id.catPanel)?.background = Ui.panel(act)
        val headerView = view.findViewById<TextView>(R.id.catHeader)
        header(headerView)
        headerView?.text = when (mode) {
            "kids" -> "🧸 Kids"
            "anime" -> "Anime"
            "series" -> "Séries"
            "favorites" -> "♥ Favoritos"
            else -> "Filmes"
        }
        section?.let { st ->
            // cabeçalho e painel no tema da seção
            headerView?.text = st.title
            headerView?.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(st.palette[0], st.palette[st.palette.size / 2]),
            ).apply { cornerRadius = Ui.radius(act) * (if (st.id == "kids") 1.6f else 0.8f) }
            headerView?.textSize = if (st.id == "kids") 18f else 16f
            view.findViewById<View>(R.id.catPanel)?.background = Ui.rounded(Ui.withAlpha(android.graphics.Color.BLACK, 70), Ui.radius(act) * 1.4f)
            cats.section = st
            posters.section = st
            empty.setTextColor(android.graphics.Color.WHITE)
        }
        empty.setTextColor(Ui.text2)
        cats.showKind = kids || mode == "anime" || mode == "favorites"
        catList.layoutManager = LinearLayoutManager(act, if (portrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        catList.adapter = cats
        catList.itemAnimator = null
        grid.layoutManager = layout
        grid.adapter = posters
        grid.itemAnimator = null
        grid.setItemViewCacheSize(24)
        grid.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) {
                val span = ((r - l) / Ui.dp(act, if (portrait) 112 else 128)).coerceIn(2, 9)
                if (span != layout.spanCount) grid.post { layout.spanCount = span }
            }
        }
    }

    private fun spanGuess(): Int {
        val w = act.resources.displayMetrics.widthPixels * (if (portrait) 1f else 0.74f)
        return (w / Ui.dp(act, if (portrait) 112 else 128)).toInt().coerceIn(2, 9)
    }

    override fun onShow() {
        if (current?.special == "home") homeView?.onShow()
        if (!loaded) load()
        else {
            // favoritos podem ter mudado em outra tela
            posters.refreshFavs()
            current?.takeIf { it.special == "fav" }?.let { open(it, keepScroll = true) } ?: posters.refreshAll()
        }
    }

    override fun onHide() {
        homeView?.onHide()
    }

    override fun reload() {
        loaded = false
        homeView?.reload()
        load()
    }

    /** Aberto por um atalho/“ver tudo” da tela inicial. */
    fun openFromOutside(c: Category) {
        if (!loaded) {
            pendingOpen = c
            return
        }
        val pos = cats.items.indexOfFirst { it.key == c.key && it.special == c.special }
        if (pos >= 0) {
            catList.scrollToPosition(pos)
            onCategory(pos, cats.items[pos])
        } else {
            cats.select(-1)
            if (c.locked && !Repo.isUnlocked(c)) Dialogs.pin(act) { Repo.unlock(c); open(c) } else open(c)
        }
        act.launch { kotlinx.coroutines.delay(300); focusGrid() }
    }

    override fun focusContent(): Boolean =
        (current?.special == "home" && homeView?.focus() == true) || focusGrid() || catList.requestFocus()

    private fun load() {
        job?.cancel()
        job = act.launch {
            setLoading(true)
            try {
                val home = if (pageKind != null && com.novatv.app.data.PanelConfig.current.homeMosaic)
                    listOf(Category("home", "Início", pageKind, special = "home")) else emptyList()
                val list: List<Category> = home + when (mode) {
                    "movies" -> listOf(Category("fav", "Favoritos", Kind.MOVIE, special = "fav")) + Repo.categories(Kind.MOVIE)
                    "series" -> listOf(Category("fav", "Favoritos", Kind.SERIES, special = "fav")) + Repo.categories(Kind.SERIES)
                    "kids" -> Repo.kidsCategories()
                    "anime" -> Repo.animeCategories()
                    "favorites" -> listOf(
                        Category("fav", "Canais", Kind.LIVE, special = "fav"),
                        Category("fav", "Filmes", Kind.MOVIE, special = "fav"),
                        Category("fav", "Séries", Kind.SERIES, special = "fav"),
                    )
                    else -> emptyList()
                }
                val last = Prefs.lastCategory(id)
                var sel = if (home.isNotEmpty()) 0 else list.indexOfFirst { it.key == last && !it.locked }
                if (sel < 0) sel = list.indexOfFirst { it.special == null && !it.locked }
                if (sel < 0 && list.isNotEmpty()) sel = 0
                cats.submit(list, sel)
                loaded = true
                val pend = pendingOpen
                pendingOpen = null
                if (pend != null) {
                    openFromOutside(pend)
                } else if (sel >= 0) {
                    catList.scrollToPosition(sel)
                    open(list[sel])
                } else {
                    setLoading(false)
                    showEmpty(when (mode) {
                        "kids" -> "Nenhum conteúdo infantil encontrado.\nO administrador pode ajustar as palavras-chave no painel."
                        "anime" -> "Nenhum anime encontrado."
                        else -> "Nada por aqui ainda."
                    })
                }
            } catch (e: Exception) {
                // troca rápida de aba/categoria cancela o carregamento anterior: não é erro
                if (e is kotlinx.coroutines.CancellationException) throw e
                setLoading(false)
                act.handleError(e)
                showRetry("Não foi possível carregar.") { load() }
            }
        }
    }

    private fun onCategory(pos: Int, c: Category) {
        if (c.locked && !Repo.isUnlocked(c)) {
            Dialogs.pin(act) {
                Repo.unlock(c)
                cats.select(pos)
                open(c)
            }
            return
        }
        cats.select(pos)
        open(c)
    }

    private fun open(c: Category, keepScroll: Boolean = false) {
        current = c
        if (c.special == "home") {
            showHome(true)
            return
        }
        showHome(false)
        if (c.special == null || mode == "favorites") Prefs.setLastCategory(id, c.key)
        job?.cancel()
        job = act.launch {
            if (!keepScroll) setLoading(true)
            try {
                val items = Repo.items(c)
                posters.submit(items.map { PosterAdapter.from(it, showKind = kids || mode == "anime") })
                if (!keepScroll) grid.scrollToPosition(0)
                setLoading(false)
                if (items.isEmpty()) showEmpty(if (c.special == "fav") favEmpty(c.kind) else "Nenhum título nesta categoria.")
                else empty.visibility = View.GONE
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setLoading(false)
                act.handleError(e)
                showRetry("Não foi possível abrir esta categoria.") { open(c) }
            }
        }
    }

    /** Troca entre a grade de cartazes e o “Início” em mosaico. */
    private fun showHome(on: Boolean) {
        val kindNow = pageKind ?: return
        if (on) {
            val hv = homeView ?: HomeView(act, kindNow).also {
                homeView = it
                (grid.parent as ViewGroup).addView(it.view, FrameLayout.LayoutParams(-1, -1))
            }
            grid.visibility = View.GONE
            empty.visibility = View.GONE
            loading.visibility = View.GONE
            hv.view.visibility = View.VISIBLE
            hv.onShow()
        } else {
            homeView?.let { it.onHide(); it.view.visibility = View.GONE }
            grid.visibility = View.VISIBLE
        }
    }

    private fun setLoading(on: Boolean) {
        loading.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            empty.visibility = View.GONE
            posters.submit(emptyList())
        }
    }

    private fun showEmpty(msg: String) {
        empty.setOnClickListener(null)
        empty.isFocusable = false
        empty.text = msg
        empty.visibility = View.VISIBLE
    }

    /** Erro de conexão: a mensagem vira um botão "tentar de novo". */
    private fun showRetry(msg: String, retry: () -> Unit) {
        empty.text = "$msg\n\n⟳  ${if (Ui.isTv) "Pressione OK" else "Toque aqui"} para tentar de novo"
        empty.visibility = View.VISIBLE
        empty.isFocusable = true
        empty.setOnClickListener { retry() }
    }

    private fun onItem(pos: Int, item: PosterItem) {
        val m = item.media ?: return
        if (m.kind == Kind.LIVE) {
            val lives = posters.items.mapNotNull { it.media }.filter { it.kind == Kind.LIVE }
            PlayQueue.live(lives, lives.indexOfFirst { it.key == m.key }.coerceAtLeast(0))
            Prefs.addRecent(m)
            act.openPlayer()
        } else {
            act.openDetail(m)
        }
    }

    private fun onLong(pos: Int, item: PosterItem) {
        val m: Media = item.media ?: return
        val added = Prefs.toggleFavorite(m)
        Dialogs.toast(act, if (added) "♥ Adicionado aos favoritos" else "Removido dos favoritos")
        posters.refreshFavs()
        if (current?.special == "fav") current?.let { open(it, keepScroll = true) } else posters.notifyItemChanged(pos)
    }

    private fun favEmpty(kind: Kind): String {
        val what = when (kind) {
            Kind.LIVE -> "canal"
            Kind.MOVIE -> "filme"
            Kind.SERIES -> "série"
        }
        val how = if (Ui.isTv) "segure OK" else "toque e segure"
        return "Nenhum favorito aqui ainda.\nEm qualquer $what, $how ou use o botão ♥ para salvar."
    }

    private fun focusGrid(): Boolean {
        if (posters.itemCount == 0) return false
        val first = layout.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        val v = layout.findViewByPosition(first) ?: return false
        return v.requestFocus()
    }
}
