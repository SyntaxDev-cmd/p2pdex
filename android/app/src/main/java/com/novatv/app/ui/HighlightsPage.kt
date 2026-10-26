package com.novatv.app.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.novatv.app.data.Category
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Destaques: banners do painel, continuar assistindo, favoritos e lançamentos. */
class HighlightsPage(act: MainActivity, id: String) : Page(act, id) {

    private val column = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(act, if (portrait) 8 else 18), 0, Ui.dp(act, 8), Ui.dp(act, 30))
        clipChildren = false
        clipToPadding = false
    }
    private val scroll = ScrollView(act).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        clipChildren = false
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val loading = ProgressBar(act)
    override val view: View = FrameLayout(act).apply {
        addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 42), Ui.dp(act, 42), android.view.Gravity.CENTER))
    }

    private var loaded = false
    private var job: Job? = null
    private var autoJob: Job? = null
    private var bannerList: RecyclerView? = null
    private var firstRow: RecyclerView? = null

    override fun onShow() {
        if (!loaded) load() else refreshResume()
        startAuto()
    }

    override fun onHide() {
        autoJob?.cancel()
    }

    override fun reload() {
        loaded = false
        load()
    }

    override fun focusContent(): Boolean {
        val rv = bannerList ?: firstRow ?: return false
        return rv.getChildAt(0)?.requestFocus() ?: false
    }

    private var resumeRow: Pair<View, PosterAdapter>? = null
    private val rowAdapters = mutableListOf<PosterAdapter>()

    private fun refreshResume() {
        rowAdapters.forEach { it.refreshFavs(); it.notifyDataSetChanged() }
        val items = resumeItems()
        resumeRow?.let { (container, adapter) ->
            adapter.submit(items)
            container.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun resumeItems(): List<PosterItem> = Prefs.resumes().mapNotNull { r ->
        val m = r.media ?: return@mapNotNull null
        PosterItem(r.title, r.image.ifEmpty { m.icon }, m.kind, progress = if (r.duration > 0) (r.position * 100 / r.duration).toInt() else 0, media = m)
    }

    private fun load() {
        job?.cancel()
        column.removeAllViews()
        rowAdapters.clear()
        bannerList = null
        firstRow = null
        loading.visibility = View.VISIBLE
        job = act.launch {
            val cfg = Repo.config
            if (cfg.banners.isNotEmpty()) addBanners()

            val resume = addRow("Continuar assistindo", resumeItems(), Kind.MOVIE)
            resumeRow = resume
            if (resume.second.itemCount == 0) resume.first.visibility = View.GONE

            val favs = Prefs.favorites().filter { it.kind != Kind.LIVE }.map { PosterAdapter.from(it) }
            if (favs.isNotEmpty()) addRow("Minha lista", favs, Kind.MOVIE)
            val favLive = Prefs.favorites(Kind.LIVE).map { PosterAdapter.from(it) }
            if (favLive.isNotEmpty()) addRow("Canais favoritos", favLive, Kind.LIVE)

            try {
                val movieCats = async { runCatching { pick(Repo.categories(Kind.MOVIE), 4) }.getOrDefault(emptyList()) }
                val seriesCats = async { runCatching { pick(Repo.categories(Kind.SERIES), 3) }.getOrDefault(emptyList()) }
                val cats = interleave(movieCats.await(), seriesCats.await())
                val rows = cats.map { c -> c to async { runCatching { Repo.items(c) }.getOrDefault(emptyList()) } }
                loading.visibility = View.GONE
                for ((c, deferred) in rows) {
                    val items: List<Media> = deferred.await()
                    if (items.isEmpty()) continue
                    val sorted = items.sortedByDescending { it.added }.take(30).map { PosterAdapter.from(it) }
                    addRow(c.name, sorted, c.kind)
                }
            } catch (e: Exception) {
                act.handleError(e)
            }
            loading.visibility = View.GONE
            loaded = true
            if (column.childCount == 0) {
                column.addView(TextView(act).apply {
                    text = "Nenhum destaque disponível."
                    setTextColor(Ui.text2)
                    setPadding(0, Ui.dp(act, 40), 0, 0)
                    gravity = android.view.Gravity.CENTER
                })
            }
        }
    }

    private val priority = listOf("lanc", "estreia", "2026", "2025", "cinema", "novidade", "recente", "top", "netflix", "4k")

    private fun pick(list: List<Category>, n: Int): List<Category> {
        val usable = list.filter { !it.locked && !Repo.matches(it.name, Repo.config.adultKeywords) }
        val scored = usable.sortedBy { c ->
            val name = Repo.normalize(c.name)
            val idx = priority.indexOfFirst { name.contains(it) }
            if (idx < 0) 100 + usable.indexOf(c) else idx
        }
        return scored.take(n)
    }

    private fun interleave(a: List<Category>, b: List<Category>): List<Category> {
        val out = mutableListOf<Category>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out.add(it) }
            b.getOrNull(i)?.let { out.add(it) }
        }
        return out
    }

    private fun title(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.text)
        setPadding(Ui.dp(act, 8), Ui.dp(act, 14), 0, Ui.dp(act, 2))
    }

    private fun addRow(name: String, items: List<PosterItem>, kind: Kind): Pair<View, PosterAdapter> {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        box.addView(title(name))
        val adapter = PosterAdapter({ _, item -> open(item) }, onLong = { pos, item -> toggleFav(pos, item) }, fixedWidthDp = if (kind == Kind.LIVE) 150 else if (portrait) 108 else 122)
        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 24), Ui.dp(act, 8))
            setHasFixedSize(true)
            isHorizontalScrollBarEnabled = false
        }
        adapter.submit(items)
        rowAdapters.add(adapter)
        box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (firstRow == null && items.isNotEmpty()) firstRow = rv
        Ui.fadeIn(box)
        return box to adapter
    }

    private fun addBanners() {
        val banners = Repo.config.banners
        val adapter = BannerAdapter { pos -> act.bannerAction(banners[pos]) }
        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 40), Ui.dp(act, 6))
            isHorizontalScrollBarEnabled = false
        }
        PagerSnapHelper().attachToRecyclerView(rv)
        adapter.submit(banners.map { BannerAdapter.Item(it.title, it.subtitle, it.image) })
        column.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bannerList = rv
    }

    /** Banners giram sozinhos quando o usuário não está mexendo neles. */
    private fun startAuto() {
        autoJob?.cancel()
        autoJob = act.launch {
            var pos = 0
            while (isActive) {
                delay(7000)
                val rv = bannerList ?: continue
                val count = rv.adapter?.itemCount ?: 0
                if (count < 2 || rv.hasFocus() || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) continue
                pos = ((rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() + 1) % count
                rv.smoothScrollToPosition(pos)
            }
        }
    }

    private fun toggleFav(pos: Int, item: PosterItem) {
        val m = item.media ?: return
        val added = Prefs.toggleFavorite(m)
        Dialogs.toast(act, if (added) "♥ Adicionado aos favoritos" else "Removido dos favoritos")
        rowAdapters.forEach { it.refreshFavs(); it.notifyDataSetChanged() }
    }

    private fun open(item: PosterItem) {
        val m = item.media ?: return
        if (m.kind == Kind.LIVE) {
            val lives = Prefs.favorites(Kind.LIVE)
            PlayQueue.live(lives.ifEmpty { listOf(m) }, lives.indexOfFirst { it.key == m.key }.coerceAtLeast(0))
            act.openPlayer()
        } else {
            act.openDetail(m)
        }
    }
}
