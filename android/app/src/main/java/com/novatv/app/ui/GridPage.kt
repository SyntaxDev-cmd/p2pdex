package com.novatv.app.ui

import android.view.LayoutInflater
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
    private val accent = if (kids) Ui.kids else Ui.accent

    private val cats = CategoryAdapter(portrait, ::onCategory, onRight = { focusGrid() })
    private val posters = PosterAdapter(::onItem, accentColor = accent, onLong = ::onLong)
    private val layout = GridLayoutManager(act, spanGuess())
    private var loaded = false
    private var job: Job? = null
    private var current: Category? = null

    init {
        view.findViewById<View>(R.id.catPanel)?.background = Ui.panel(act)
        val headerView = view.findViewById<TextView>(R.id.catHeader)
        header(headerView)
        headerView?.text = when (mode) {
            "kids" -> "🧸 Kids"
            "anime" -> "Anime"
            "series" -> "Séries"
            else -> "Filmes"
        }
        if (kids) headerView?.background = Ui.rounded(Ui.kids, Ui.radius(act) * 0.8f)
        empty.setTextColor(Ui.text2)
        cats.showKind = kids || mode == "anime"
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
        if (!loaded) load()
    }

    override fun reload() {
        loaded = false
        load()
    }

    override fun focusContent(): Boolean = focusGrid() || catList.requestFocus()

    private fun load() {
        job?.cancel()
        job = act.launch {
            setLoading(true)
            try {
                val list: List<Category> = when (mode) {
                    "movies" -> listOf(Category("fav", "★ Favoritos", Kind.MOVIE, special = "fav")) + Repo.categories(Kind.MOVIE)
                    "series" -> listOf(Category("fav", "★ Favoritos", Kind.SERIES, special = "fav")) + Repo.categories(Kind.SERIES)
                    "kids" -> Repo.kidsCategories()
                    "anime" -> Repo.animeCategories()
                    else -> emptyList()
                }
                val last = Prefs.lastCategory(id)
                var sel = list.indexOfFirst { it.key == last && !it.locked }
                if (sel < 0) sel = list.indexOfFirst { it.special == null && !it.locked }
                if (sel < 0 && list.isNotEmpty()) sel = 0
                cats.submit(list, sel)
                loaded = true
                if (sel >= 0) {
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
                setLoading(false)
                act.handleError(e)
                showEmpty("Não foi possível carregar.\n${e.message.orEmpty()}")
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

    private fun open(c: Category) {
        current = c
        if (c.special == null) Prefs.setLastCategory(id, c.key)
        job?.cancel()
        job = act.launch {
            setLoading(true)
            try {
                val items = Repo.items(c)
                posters.submit(items.map { PosterAdapter.from(it, showKind = kids || mode == "anime") })
                grid.scrollToPosition(0)
                setLoading(false)
                if (items.isEmpty()) showEmpty(if (c.special == "fav") "Segure OK em um título para favoritar." else "Nenhum título nesta categoria.")
            } catch (e: Exception) {
                setLoading(false)
                act.handleError(e)
                showEmpty("Erro ao carregar.")
            }
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
        empty.text = msg
        empty.visibility = View.VISIBLE
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
        Dialogs.toast(act, if (added) "★ Adicionado aos favoritos" else "Removido dos favoritos")
        if (current?.special == "fav") current?.let { open(it) }
    }

    private fun focusGrid(): Boolean {
        if (posters.itemCount == 0) return false
        val first = layout.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        val v = layout.findViewByPosition(first) ?: return false
        return v.requestFocus()
    }
}
