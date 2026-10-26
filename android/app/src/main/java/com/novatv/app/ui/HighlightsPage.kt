package com.novatv.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Banner
import com.novatv.app.data.Category
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import com.novatv.app.data.Tmdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/** Aba DESTAQUES: a tela inicial com mosaico, atalhos e fileiras de filmes, séries e canais. */
class HighlightsPage(act: MainActivity, id: String) : Page(act, id) {
    private val home = HomeView(act, null)
    override val view: View get() = home.view
    override fun onShow() = home.onShow()
    override fun onHide() = home.onHide()
    override fun reload() = home.reload()
    override fun focusContent(): Boolean = home.focus()
}

/**
 * Tela inicial reaproveitável:
 *  - kind = null  → mistura filmes, séries e canais (aba Destaques)
 *  - kind = MOVIE → "Início" da aba Filmes; SERIES → "Início" da aba Séries
 * Topo em mosaico (destaque grande que gira sozinho + 2 cartazes + 4 atalhos coloridos).
 */
class HomeView(private val act: MainActivity, private val kind: Kind?) {

    private val portrait = act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    private val column = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(act, if (portrait) 8 else 18), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 30))
        clipChildren = false
        clipToPadding = false
    }
    private val scroll = ScrollView(act).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        clipChildren = false
        addView(column, ViewGroup.LayoutParams(-1, -2))
    }
    private val loading = ProgressBar(act)
    val view: View = FrameLayout(act).apply {
        addView(scroll, FrameLayout.LayoutParams(-1, -1))
        addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 42), Ui.dp(act, 42), Gravity.CENTER))
    }

    private data class Feature(val title: String, val subtitle: String, val image: String, val poster: String, val media: Media?, val banner: Banner?)

    private var loaded = false
    private var job: Job? = null
    private var rotateJob: Job? = null
    private var hero: View? = null
    private var firstFocusable: View? = null
    private val rowAdapters = mutableListOf<PosterAdapter>()
    private var resumeRow: Pair<View, PosterAdapter>? = null

    fun onShow() {
        if (!loaded) load() else refreshDynamic()
    }

    fun onHide() {
        rotateJob?.cancel()
    }

    fun reload() {
        loaded = false
        load()
    }

    fun focus(): Boolean = (firstFocusable ?: hero)?.requestFocus() ?: false

    private fun refreshDynamic() {
        rowAdapters.forEach { it.refreshFavs(); it.notifyDataSetChanged() }
        resumeRow?.let { (box, adapter) ->
            val items = resumeItems()
            adapter.submit(items)
            box.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
        startRotation()
    }

    private fun kinds(): List<Kind> = if (kind == null) listOf(Kind.MOVIE, Kind.SERIES) else listOf(kind)

    private fun resumeItems(): List<PosterItem> = Prefs.resumes().mapNotNull { r ->
        val m = r.media ?: return@mapNotNull null
        if (kind != null && m.kind != kind) return@mapNotNull null
        PosterItem(r.title, r.image.ifEmpty { m.icon }, m.kind, progress = if (r.duration > 0) (r.position * 100 / r.duration).toInt() else 0, media = m)
    }

    private fun load() {
        job?.cancel()
        rotateJob?.cancel()
        column.removeAllViews()
        rowAdapters.clear()
        hero = null
        firstFocusable = null
        loading.visibility = View.VISIBLE
        job = act.launch {
            try {
                // categorias que viram fileiras (prioriza lançamentos/cinema)
                val catsByKind = kinds().associateWith { k -> runCatching { pick(Repo.categories(k), if (kind == null) 3 else 6) }.getOrDefault(emptyList()) }
                val rows = catsByKind.values.flatten().let { if (kind == null) interleave(it) else it }
                val rowItems = coroutineScope {
                    rows.map { c -> c to async { runCatching { Repo.items(c) }.getOrDefault(emptyList()) } }
                        .map { (c, d) -> c to d.await().sortedByDescending { it.added } }
                }
                val trending = if (Tmdb.enabled) coroutineScope {
                    kinds().map { k -> async { runCatching { Tmdb.trendingInCatalog(k == Kind.SERIES) }.getOrDefault(emptyList()) } }.flatMap { it.await() }
                } else emptyList()

                // ---- mosaico
                if (PanelConfig.current.homeMosaic) {
                    val features = buildFeatures(trending, rowItems)
                    if (features.isNotEmpty()) column.addView(mosaic(features))
                }
                loading.visibility = View.GONE

                // ---- fileiras
                val resume = addRow("Continuar assistindo", resumeItems(), Kind.MOVIE)
                resumeRow = resume
                if (resume.second.itemCount == 0) resume.first.visibility = View.GONE
                if (trending.isNotEmpty()) {
                    addRow(if (kind == Kind.SERIES) "Séries em alta" else if (kind == Kind.MOVIE) "Filmes em alta" else "Em alta esta semana",
                        trending.map { (t, m) -> PosterItem(m.name, t.poster.ifEmpty { m.icon }, m.kind, rating = t.rating, media = m) }, Kind.MOVIE)
                }
                val favs = Prefs.favorites().filter { it.kind != Kind.LIVE && (kind == null || it.kind == kind) }.map { PosterAdapter.from(it) }
                if (favs.isNotEmpty()) addRow("Meus favoritos", favs, Kind.MOVIE)
                if (kind == null) {
                    val favLive = Prefs.favorites(Kind.LIVE).map { PosterAdapter.from(it) }
                    if (favLive.isNotEmpty()) addRow("Canais favoritos", favLive, Kind.LIVE)
                }
                for ((c, items) in rowItems) {
                    if (items.isEmpty()) continue
                    addRow(c.name, items.take(30).map { PosterAdapter.from(it) }, c.kind, c)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                act.handleError(e)
            }
            loading.visibility = View.GONE
            loaded = true
            if (column.childCount == 0) {
                column.addView(TextView(act).apply {
                    text = "Nenhum destaque disponível."
                    setTextColor(Ui.text2)
                    setPadding(0, Ui.dp(act, 40), 0, 0)
                    gravity = Gravity.CENTER
                })
            }
            startRotation()
        }
    }

    private val priority = listOf("lanc", "estreia", "cinema", "2026", "2025", "novidade", "recente", "top", "netflix", "4k")

    private fun pick(list: List<Category>, n: Int): List<Category> {
        val usable = list.filter { !it.locked && it.special == null }
        return usable.sortedBy { c ->
            val name = Repo.normalize(c.name)
            val idx = priority.indexOfFirst { name.contains(it) }
            if (idx < 0) 100 + usable.indexOf(c) else idx
        }.take(n)
    }

    private fun interleave(list: List<Category>): List<Category> {
        val movies = list.filter { it.kind == Kind.MOVIE }
        val series = list.filter { it.kind == Kind.SERIES }
        val out = mutableListOf<Category>()
        for (i in 0 until maxOf(movies.size, series.size)) {
            movies.getOrNull(i)?.let { out.add(it) }
            series.getOrNull(i)?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------------ mosaico

    private suspend fun buildFeatures(trending: List<Pair<Tmdb.Trend, Media>>, rows: List<Pair<Category, List<Media>>>): List<Feature> {
        val out = mutableListOf<Feature>()
        if (kind == null) {
            PanelConfig.current.banners.forEach { b -> out.add(Feature(b.title, b.subtitle, b.image, b.image, null, b)) }
        }
        trending.forEach { (t, m) ->
            out.add(Feature(m.name, listOf(t.year, if (t.rating.isNotEmpty()) "★ ${t.rating}" else "").filter { it.isNotEmpty() }.joinToString("  •  "),
                t.backdrop.ifEmpty { m.icon }, t.poster.ifEmpty { m.icon }, m, null))
        }
        val seen = out.mapNotNull { it.media?.key }.toMutableSet()
        rows.flatMap { it.second.take(4) }.filter { it.icon.isNotBlank() && seen.add(it.key) }.take(8).forEach { m ->
            out.add(Feature(m.name, listOf(m.year, if (m.rating.isNotEmpty()) "★ ${m.rating}" else "").filter { it.isNotEmpty() }.joinToString("  •  "),
                m.icon, m.icon, m, null))
        }
        // fundos em alta resolução do TMDB para os primeiros destaques
        if (Tmdb.enabled) coroutineScope {
            out.take(6).mapIndexed { i, f ->
                async {
                    val m = f.media ?: return@async null
                    if (f.image != m.icon) return@async null
                    val info = runCatching { Tmdb.info(m) }.getOrNull() ?: return@async null
                    if (info.backdrop.isBlank()) null else i to f.copy(image = info.backdrop, subtitle = f.subtitle.ifBlank { info.genres })
                }
            }.mapNotNull { it.await() }.forEach { (i, f) -> out[i] = f }
        }
        return out.take(10)
    }

    private fun mosaic(features: List<Feature>): View {
        val gap = Ui.dp(act, 10)
        val r = Ui.radius(act) * 1.2f
        val root = LinearLayout(act).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(Ui.dp(act, 4), Ui.dp(act, 6), Ui.dp(act, 4), Ui.dp(act, 8))
        }
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }

        // destaque grande
        val heroFrame = AspectFrame(act).apply { ratio = if (portrait) 0.56f else 0.5f }
        heroFrame.isFocusable = true
        heroFrame.isClickable = true
        heroFrame.background = Ui.rounded(Ui.surface, r)
        Ui.clipRounded(heroFrame, r)
        val heroImg = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroFrame.addView(heroImg, FrameLayout.LayoutParams(-1, -1))
        heroFrame.addView(View(act).apply { setBackgroundResource(R.drawable.shade_left) }, FrameLayout.LayoutParams(-1, -1))
        heroFrame.addView(View(act).apply { setBackgroundResource(R.drawable.shade_bottom) }, FrameLayout.LayoutParams(-1, -1))
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(act, 20), 0, Ui.dp(act, 20), Ui.dp(act, 18))
        }
        val heroTitle = TextView(act).apply {
            textSize = if (portrait) 22f else 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val heroSub = TextView(act).apply { textSize = 14f; setTextColor(Ui.withAlpha(Color.WHITE, 220)); maxLines = 1 }
        val heroCta = TextView(act).apply {
            text = "▶  Assistir"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = Ui.rounded(Color.WHITE, Ui.dp(act, 30).toFloat())
            setPadding(Ui.dp(act, 16), Ui.dp(act, 7), Ui.dp(act, 16), Ui.dp(act, 7))
        }
        texts.addView(heroTitle)
        texts.addView(heroSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(act, 4) })
        texts.addView(heroCta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(act, 12) })
        heroFrame.addView(texts, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        heroFrame.addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(act, 16), Ui.dp(act, 22))
        })
        heroFrame.foreground = Ui.posterFrame(act)
        Ui.focusZoom(heroFrame, 1.02f)

        val heroList = features
        var index = 0
        fun showHero(i: Int, animate: Boolean) {
            val f = heroList[i]
            if (animate) heroImg.animate().alpha(0.2f).setDuration(180).withEndAction {
                heroImg.load(f.image.ifBlank { null }) { crossfade(350); error(R.drawable.ic_movie) }
                heroImg.animate().alpha(1f).setDuration(260).start()
            }.start() else heroImg.load(f.image.ifBlank { null }) { error(R.drawable.ic_movie) }
            heroTitle.text = f.title
            heroSub.text = f.subtitle
            heroSub.visibility = if (f.subtitle.isBlank()) View.GONE else View.VISIBLE
            heroCta.text = if (f.banner != null && f.media == null) "Ver mais" else "▶  Assistir"
            dots.removeAllViews()
            heroList.indices.take(8).forEach { d ->
                dots.addView(View(act).apply {
                    background = Ui.rounded(if (d == i) Color.WHITE else Ui.withAlpha(Color.WHITE, 90), Ui.dp(act, 4).toFloat())
                }, LinearLayout.LayoutParams(Ui.dp(act, if (d == i) 18 else 7), Ui.dp(act, 7)).apply { marginStart = Ui.dp(act, 4) })
            }
        }
        showHero(0, false)
        heroFrame.setOnClickListener { openFeature(heroList[index]) }
        hero = heroFrame
        rotate = {
            index = (index + 1) % heroList.size
            showHero(index, true)
        }

        left.addView(heroFrame, LinearLayout.LayoutParams(-1, -2))
        left.addView(tilesRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })

        if (portrait) {
            root.addView(left, LinearLayout.LayoutParams(-1, -2))
        } else {
            root.addView(left, LinearLayout.LayoutParams(0, -2, 2f))
            // dois cartazes altos à direita
            heroList.drop(1).take(2).forEach { f ->
                root.addView(tallPoster(f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.92f).apply { marginStart = gap })
            }
        }
        return root
    }

    private var rotate: (() -> Unit)? = null

    private fun startRotation() {
        rotateJob?.cancel()
        val h = hero ?: return
        rotateJob = act.launch {
            while (isActive) {
                delay(7000)
                if (!h.hasFocus() && h.isShown) rotate?.invoke()
            }
        }
    }

    private fun tallPoster(f: Feature): View {
        val r = Ui.radius(act) * 1.2f
        val frame = FrameLayout(act).apply {
            isFocusable = true
            isClickable = true
            background = Ui.rounded(Ui.surface, r)
            foreground = Ui.posterFrame(act)
        }
        Ui.clipRounded(frame, r)
        frame.addView(ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(f.poster.ifBlank { f.image }.ifBlank { null }) { error(R.drawable.ic_movie) }
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(act).apply { setBackgroundResource(R.drawable.shade_bottom) }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(TextView(act).apply {
            text = f.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), Ui.dp(act, 10))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        Ui.focusZoom(frame, 1.05f)
        frame.setOnClickListener { openFeature(f) }
        return frame
    }

    private data class Tile(val label: String, val icon: Int, val c1: Int, val c2: Int, val action: () -> Unit)

    private fun tiles(): List<Tile> {
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        return when (kind) {
            null -> listOfNotNull(
                Tile("Ao vivo", R.drawable.ic_tv, 0xFF1FA2A6.toInt(), 0xFF0E5E7A.toInt()) { act.goToTab("tv") },
                Tile("Filmes", R.drawable.ic_cinema, 0xFF5B6CF0.toInt(), 0xFF2E3AA8.toInt()) { act.goToTab("movies") },
                Tile("Séries", R.drawable.ic_list, 0xFF9C4DE0.toInt(), 0xFF5A1FA0.toInt()) { act.goToTab("series") },
                if (PanelConfig.current.tabs.any { it.id == "kids" }) Tile("Kids", R.drawable.ic_kids, 0xFFF0567A.toInt(), 0xFFB0213F.toInt()) { act.goToTab("kids") }
                else Tile("Favoritos", R.drawable.ic_heart, 0xFFF0567A.toInt(), 0xFFB0213F.toInt()) { act.goToTab("favorites") },
            )
            else -> {
                val tab = if (kind == Kind.SERIES) "series" else "movies"
                listOf(
                    Tile("Gênero", R.drawable.ic_genre, 0xFF1FA2A6.toInt(), 0xFF0E5E7A.toInt()) { chooseGenre(tab) },
                    Tile(year, R.drawable.ic_calendar, 0xFF5B6CF0.toInt(), 0xFF2E3AA8.toInt()) {
                        act.openCategory(tab, Category("year", "Lançados em $year", kind, special = "year:$year"))
                    },
                    Tile(if (kind == Kind.SERIES) "Em alta" else "No cinema", if (kind == Kind.SERIES) R.drawable.ic_fire else R.drawable.ic_cinema,
                        0xFF9C4DE0.toInt(), 0xFF5A1FA0.toInt()) { openCinema(tab) },
                    Tile("Todos", R.drawable.ic_apps, 0xFFF0567A.toInt(), 0xFFB0213F.toInt()) {
                        act.openCategory(tab, Category("new", "Adicionados recentemente", kind, special = "new"))
                    },
                )
            }
        }
    }

    private fun tilesRow(): View {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val list = tiles()
        list.forEachIndexed { i, t ->
            val r = Ui.radius(act).toFloat()
            val box = LinearLayout(act).apply {
                orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                val p = Ui.dp(act, if (portrait) 8 else 12)
                setPadding(p, p, p, p)
                background = android.graphics.drawable.StateListDrawable().apply {
                    val normal = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.withAlpha(t.c1, 225), Ui.withAlpha(t.c2, 225))).apply { cornerRadius = r }
                    val focused = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.c1, t.c2)).apply {
                        cornerRadius = r
                        setStroke(Ui.dp(act, 3), Color.WHITE)
                    }
                    addState(intArrayOf(android.R.attr.state_focused), focused)
                    addState(intArrayOf(android.R.attr.state_pressed), focused)
                    addState(intArrayOf(), normal)
                }
                setOnClickListener { t.action() }
            }
            box.addView(ImageView(act).apply {
                setImageResource(t.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(Ui.dp(act, 24), Ui.dp(act, 24)))
            box.addView(TextView(act).apply {
                text = t.label
                textSize = if (portrait) 12f else 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                setPadding(if (portrait) 0 else Ui.dp(act, 10), if (portrait) Ui.dp(act, 4) else 0, 0, 0)
            })
            Ui.focusZoom(box, 1.06f)
            row.addView(box, LinearLayout.LayoutParams(0, Ui.dp(act, if (portrait) 64 else 58), 1f).apply {
                if (i > 0) marginStart = Ui.dp(act, 10)
            })
        }
        return row
    }

    private fun chooseGenre(tab: String) {
        val k = kind ?: return
        act.launch {
            val cats = runCatching { Repo.categories(k) }.getOrDefault(emptyList()).filter { it.special == null }
            if (cats.isEmpty()) return@launch
            Dialogs.choose(act, "Gênero / categoria", cats.map { (if (it.locked) "🔒 " else "") + it.name }) { i ->
                act.openCategory(tab, cats[i])
            }
        }
    }

    private fun openCinema(tab: String) {
        val k = kind ?: return
        act.launch {
            val cats = runCatching { Repo.categories(k) }.getOrDefault(emptyList())
            val keys = listOf("cinema", "lanc", "estreia", "novidade", "recente")
            val c = cats.firstOrNull { c -> !c.locked && keys.any { Repo.normalize(c.name).contains(it) } }
            act.openCategory(tab, c ?: Category("new", "Adicionados recentemente", k, special = "new"))
        }
    }

    private fun openFeature(f: Feature) {
        val m = f.media
        if (m != null) {
            open(PosterItem(m.name, m.icon, m.kind, media = m))
            return
        }
        f.banner?.let { act.bannerAction(it) }
    }

    // ------------------------------------------------------------------ fileiras

    private fun title(text: String, category: Category?): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 2))
        }
        row.addView(TextView(act).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (category != null && category.kind != Kind.LIVE) {
            row.addView(TextView(act).apply {
                this.text = "Ver tudo ›"
                textSize = 13f
                isFocusable = true
                isClickable = true
                setTextColor(Ui.textColors(Ui.text2, Ui.focusText))
                background = Ui.itemBg(act)
                setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4))
                setOnClickListener { act.openCategory(if (category.kind == Kind.SERIES) "series" else "movies", category) }
            })
        }
        return row
    }

    private fun addRow(name: String, items: List<PosterItem>, rowKind: Kind, category: Category? = null): Pair<View, PosterAdapter> {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        box.addView(title(name, category))
        val adapter = PosterAdapter({ _, item -> open(item) }, onLong = { _, item -> toggleFav(item) },
            fixedWidthDp = if (rowKind == Kind.LIVE) 150 else if (portrait) 108 else 122)
        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 24), Ui.dp(act, 8))
            isHorizontalScrollBarEnabled = false
        }
        adapter.submit(items)
        rowAdapters.add(adapter)
        box.addView(rv, LinearLayout.LayoutParams(-1, -2))
        column.addView(box, LinearLayout.LayoutParams(-1, -2))
        if (hero == null && firstFocusable == null && items.isNotEmpty()) rv.post { firstFocusable = rv.getChildAt(0) }
        Ui.fadeIn(box)
        return box to adapter
    }

    private fun toggleFav(item: PosterItem) {
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
