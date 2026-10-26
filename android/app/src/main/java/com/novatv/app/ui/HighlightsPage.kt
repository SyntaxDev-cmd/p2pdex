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
import kotlinx.coroutines.withTimeoutOrNull
import com.novatv.app.data.AuthException
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
    private val scroll = SafeScrollView(act).apply {
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
        rowAdapters.forEach { it.refreshFavs(); it.refreshAll() }
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
        resumeRow = null
        heroFeatures.clear()
        heroIndex = 0
        rotate = null
        heroStreamSlot = null
        loading.visibility = View.GONE
        val skeleton = skeleton()
        column.addView(skeleton)
        job = act.launch {
            var error: Exception? = null
            fun keep(e: Throwable) {
                // só repassa o cancelamento se a própria tela foi cancelada (troca de aba, recarregar)
                if (e is kotlinx.coroutines.CancellationException && !isActive) throw e
                if (error == null && e is Exception) error = e
            }
            // 1) categorias e fileiras, com tempo máximo: nada de ficar carregando para sempre
            val rowItems = try {
                val catsByKind = coroutineScope {
                    kinds().map { k ->
                        async {
                            try {
                                withTimeoutOrNull(STEP_TIMEOUT) { pick(Repo.categories(k), if (kind == null) 3 else 6) } ?: emptyList()
                            } catch (e: Exception) {
                                keep(e); emptyList<Category>()
                            }
                        }
                    }.map { it.await() }
                }
                val rows = catsByKind.flatten().let { if (kind == null) interleave(it) else it }
                coroutineScope {
                    rows.map { c ->
                        c to async {
                            try {
                                withTimeoutOrNull(STEP_TIMEOUT) { Repo.items(c) } ?: emptyList()
                            } catch (e: Exception) {
                                keep(e); emptyList<Media>()
                            }
                        }
                    }.map { (c, d) -> c to d.await().sortedByDescending { it.added } }
                }
            } catch (e: Exception) {
                keep(e); emptyList<Pair<Category, List<Media>>>()
            }

            // 2) mostra já: mosaico com o que chegou + fileiras
            column.removeView(skeleton)
            if (PanelConfig.current.homeMosaic) {
                val features = baseFeatures(rowItems)
                if (features.isNotEmpty()) column.addView(mosaic(features))
            }
            // fileira de streamings (Netflix, Globoplay...) logo abaixo do destaque
            val slot = heroStreamSlot
            val streamBox = slot ?: LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; clipChildren = false; clipToPadding = false }
                .also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            if (slot != null) slot.addView(streamPlaceholder())
            launch { runCatching { fillStreamings(streamBox, compact = slot != null) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
            val resume = addRow("Continuar assistindo", resumeItems(), Kind.MOVIE)
            resumeRow = resume
            if (resume.second.itemCount == 0) resume.first.visibility = View.GONE
            val favs = Prefs.favorites().filter { it.kind != Kind.LIVE && (kind == null || it.kind == kind) }.map { PosterAdapter.from(it) }
            if (favs.isNotEmpty()) addRow("Meus favoritos", favs, Kind.MOVIE)
            if (kind == null) {
                val favLive = Prefs.favorites(Kind.LIVE).map { PosterAdapter.from(it) }
                if (favLive.isNotEmpty()) addRow("Canais favoritos", favLive, Kind.LIVE)
            }
            var rowsShown = 0
            val repeated = rowItems.filter { it.second.isNotEmpty() }.groupingBy { Repo.normalize(it.first.name).trim() }.eachCount()
            for ((c, items) in rowItems) {
                if (items.isEmpty()) continue
                // "Lançamentos" de filmes e de séries: deixa claro qual é qual
                val label = if ((repeated[Repo.normalize(c.name).trim()] ?: 0) > 1) c.name + if (c.kind == Kind.SERIES) " · Séries" else " · Filmes" else c.name
                addRow(label, items.take(30).map { PosterAdapter.from(it) }, c.kind, c)
                rowsShown++
            }
            loaded = true
            if (rowsShown == 0 && hero == null) {
                val e = error
                if (e is AuthException) act.handleError(e)
                showEmpty(if (e != null) "Não foi possível carregar o conteúdo agora." else "Nenhum destaque disponível.")
            }
            startRotation()

            // 3) extras do TMDB em segundo plano (fundos em alta e "Em alta"), sem travar a tela
            if (Tmdb.enabled) {
                launch { runCatching { enrichHero() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
                launch { runCatching { addTrending() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
            }
        }
    }

    /** Serviço de streaming: logo (opcional), cores e palavras que aparecem no nome das categorias. */
    private data class Streaming(val label: String, val logo: String, val bg: Int, val fg: Int, val keys: List<String>)

    private fun color(hex: String, def: Int): Int = runCatching { Color.parseColor(hex) }.getOrDefault(def)

    /** Lista do painel (Streamings no admin); sem painel novo, usa a lista padrão. */
    private fun streamingList(): List<Streaming> {
        val cfg = PanelConfig.current
        if (!cfg.streamingsEnabled) return emptyList()
        cfg.streamings?.let { list ->
            return list.map { Streaming(it.name, it.logo, color(it.color, Color.WHITE), color(it.textColor, Color.BLACK), it.keywords.ifEmpty { listOf(it.name) }) }
        }
        return listOf(
            Streaming("Netflix", "", Color.BLACK, 0xFFE50914.toInt(), listOf("netflix")),
            Streaming("Globoplay", "", 0xFFFF3D2E.toInt(), Color.WHITE, listOf("globoplay", "globo play")),
            Streaming("Prime Video", "", 0xFF1A98FF.toInt(), Color.WHITE, listOf("prime video", "amazon")),
            Streaming("Disney+", "", 0xFF0B1E5B.toInt(), Color.WHITE, listOf("disney")),
            Streaming("HBO Max", "", Color.WHITE, Color.BLACK, listOf("hbo", "max")),
            Streaming("Paramount+", "", 0xFF0064FF.toInt(), Color.WHITE, listOf("paramount")),
            Streaming("Crunchyroll", "", 0xFFF47521.toInt(), Color.WHITE, listOf("crunchyroll")),
            Streaming("Apple TV+", "", 0xFF111111.toInt(), Color.WHITE, listOf("apple")),
            Streaming("Star+", "", 0xFF2A1A5E.toInt(), Color.WHITE, listOf("star+", "star plus")),
            Streaming("Discovery+", "", 0xFF0A5BFF.toInt(), Color.WHITE, listOf("discovery")),
        )
    }

    /** Mostra só os streamings que existem na lista do cliente (categorias com o nome do serviço). */
    /** Círculos "fantasma" enquanto as categorias chegam (o espaço já fica reservado). */
    private fun streamPlaceholder(): View {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(act, 4), Ui.dp(act, 4), 0, Ui.dp(act, 4)) }
        val size = Ui.dp(act, 62)
        repeat(6) {
            row.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.withAlpha(Color.WHITE, 22)) } },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(act, 14) })
        }
        return row
    }

    private suspend fun fillStreamings(box: LinearLayout, compact: Boolean = false) {
        val services = streamingList()
        if (services.isEmpty()) return
        val cats = coroutineScope {
            kinds().map { k -> async { withTimeoutOrNull(STEP_TIMEOUT) { runCatching { Repo.categories(k) }.getOrNull() }.orEmpty() } }
                .flatMap { it.await() }
        }.filter { !it.locked && it.special == null }
        // todos aparecem: com categorias próprias abre a categoria; sem elas, o app monta a lista pelo TMDB
        val found = services.map { st -> st to cats.filter { c -> Repo.matches(c.name, st.keys) } }
        box.removeAllViews()
        if (!compact) box.addView(title(PanelConfig.current.streamingsTitle, null))
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            if (compact) setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 4))
            else setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 24), Ui.dp(act, 12))
        }
        val size = Ui.dp(act, if (compact) 62 else if (portrait) 66 else 78)
        found.forEach { (st, list) -> row.addView(streamingCircle(st, list, size), LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(act, if (compact) 14 else if (portrait) 14 else 18) }) }
        box.addView(SafeHorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2))
        Ui.fadeIn(box)
    }

    /** Círculo com a logo (ou o nome escrito, se o painel não tiver logo). */
    private fun streamingCircle(st: Streaming, list: List<Category>, size: Int): View {
        val frame = FrameLayout(act).apply {
            isFocusable = true
            isClickable = true
            contentDescription = st.label
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(st.bg) }
            foreground = android.graphics.drawable.StateListDrawable().apply {
                fun ring(c: Int, w: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(w, c) }
                addState(intArrayOf(android.R.attr.state_focused), ring(Color.WHITE, Ui.dp(act, 3)))
                addState(intArrayOf(android.R.attr.state_pressed), ring(Color.WHITE, Ui.dp(act, 3)))
                addState(intArrayOf(), ring(Ui.withAlpha(Color.WHITE, 45), Ui.dp(act, 1)))
            }
            elevation = Ui.dp(act, 5).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            setOnClickListener { openStreaming(st.label, list) }
        }
        val name = TextView(act).apply {
            text = st.label
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = if (st.label.length > 9) 10f else if (st.label.length > 6) 11.5f else 13f
            setTextColor(st.fg)
            maxLines = 2
            setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0)
        }
        frame.addView(name, FrameLayout.LayoutParams(-1, -1))
        if (st.logo.isNotBlank()) {
            val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE }
            frame.addView(img, FrameLayout.LayoutParams(-1, -1))
            img.load(st.logo) {
                size(size, size)
                crossfade(200)
                listener(onSuccess = { _, _ -> img.visibility = View.VISIBLE; name.visibility = View.GONE })
            }
        }
        Ui.focusZoom(frame, 1.12f, bouncy = true)
        return frame
    }

    private fun openStreaming(name: String, list: List<Category>) {
        fun open(c: Category) = act.openCategory(if (c.kind == Kind.SERIES) "series" else "movies", c)
        if (list.size == 1) { open(list[0]); return }
        if (list.size > 1) {
            Dialogs.choose(act, name, list.map { (if (it.kind == Kind.SERIES) "Séries · " else "Filmes · ") + it.name }) { open(list[it]) }
            return
        }
        // sem categoria com o nome do serviço: lista o que está nele (TMDB) e existe no catálogo do cliente
        val ids = Tmdb.providerIds(name)
        if (!Tmdb.enabled || ids.isEmpty()) {
            Dialogs.toast(act, "Nenhum conteúdo de $name na sua lista")
            return
        }
        val ks = kinds()
        fun virtual(k: Kind) = Category("stream:$ids", "$name · " + if (k == Kind.SERIES) "Séries" else "Filmes", k, special = "stream:$ids")
        if (ks.size == 1) open(virtual(ks[0]))
        else Dialogs.choose(act, name, listOf("Filmes", "Séries")) { open(virtual(if (it == 1) Kind.SERIES else Kind.MOVIE)) }
    }

    private fun showEmpty(msg: String) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(act, 60), 0, Ui.dp(act, 20))
        }
        box.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_movie)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.withAlpha(Ui.text, 120))
        }, LinearLayout.LayoutParams(Ui.dp(act, 56), Ui.dp(act, 56)))
        box.addView(TextView(act).apply {
            text = msg
            textSize = 16f
            setTextColor(Ui.text2)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 18))
        })
        val retry = TextView(act).apply {
            text = "Tentar novamente"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = Ui.button(act, true)
            setTextColor(Ui.textColors(Color.WHITE, Ui.focusText))
            setPadding(Ui.dp(act, 28), Ui.dp(act, 12), Ui.dp(act, 28), Ui.dp(act, 12))
            setOnClickListener { reload() }
        }
        Ui.focusZoom(retry, 1.05f)
        box.addView(retry, LinearLayout.LayoutParams(-2, -2))
        firstFocusable = retry
        column.addView(box, LinearLayout.LayoutParams(-1, -2))
        Ui.fadeIn(box)
    }

    /** Esqueleto animado enquanto o conteúdo chega (no lugar da bolinha girando). */
    private fun skeleton(): View {
        val r = Ui.radius(act) * 1.2f
        val shade = Ui.withAlpha(Color.WHITE, 22)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(act, 4), Ui.dp(act, 6), Ui.dp(act, 4), 0)
        }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val heroBox = AspectFrame(act).apply { ratio = if (portrait) 0.56f else 0.5f; background = Ui.rounded(shade, r) }
        top.addView(heroBox, LinearLayout.LayoutParams(0, -2, 2f))
        if (!portrait) repeat(2) {
            top.addView(View(act).apply { background = Ui.rounded(shade, r) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.92f).apply { marginStart = Ui.dp(act, 10) })
        }
        box.addView(top, LinearLayout.LayoutParams(-1, -2))
        repeat(2) {
            box.addView(View(act).apply { background = Ui.rounded(shade, Ui.dp(act, 6).toFloat()) },
                LinearLayout.LayoutParams(Ui.dp(act, 180), Ui.dp(act, 18)).apply { topMargin = Ui.dp(act, 22); bottomMargin = Ui.dp(act, 10) })
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(if (portrait) 3 else 7) { i ->
                row.addView(View(act).apply { background = Ui.rounded(shade, Ui.radius(act).toFloat()) },
                    LinearLayout.LayoutParams(Ui.dp(act, if (portrait) 108 else 122), Ui.dp(act, if (portrait) 160 else 180)).apply { if (i > 0) marginStart = Ui.dp(act, 10) })
            }
            box.addView(row)
        }
        android.animation.ObjectAnimator.ofFloat(box, View.ALPHA, 1f, 0.45f).apply {
            duration = 750
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = start()
                override fun onViewDetachedFromWindow(v: View) = cancel()
            })
        }
        return box
    }

    /** Fundos em alta resolução do TMDB para os primeiros destaques (troca na próxima volta). */
    private suspend fun enrichHero() {
        val snapshot = heroFeatures.take(6).toList()
        val updates = coroutineScope {
            snapshot.map { f ->
                async {
                    val m = f.media ?: return@async null
                    if (f.image != m.icon) return@async null
                    val info = withTimeoutOrNull(15_000) { runCatching { Tmdb.info(m) }.getOrNull() } ?: return@async null
                    if (info.backdrop.isBlank()) null else f to f.copy(image = info.backdrop, subtitle = f.subtitle.ifBlank { info.genres })
                }
            }.mapNotNull { it.await() }
        }
        updates.forEach { (old, new) ->
            val i = heroFeatures.indexOf(old)
            if (i >= 0) heroFeatures[i] = new
        }
        if (updates.isNotEmpty() && hero?.hasFocus() != true) showHero?.invoke(heroIndex, false)
    }

    /** "Em alta esta semana" (TMDB x catálogo do cliente): entra depois, sem segurar a tela. */
    private suspend fun addTrending() {
        val trending = withTimeoutOrNull(TRENDING_TIMEOUT) {
            coroutineScope {
                kinds().map { k -> async { runCatching { Tmdb.trendingInCatalog(k == Kind.SERIES) }.getOrDefault(emptyList()) } }.flatMap { it.await() }
            }
        }.orEmpty()
        if (trending.isEmpty()) return
        val title = if (kind == Kind.SERIES) "Séries em alta" else if (kind == Kind.MOVIE) "Filmes em alta" else "Em alta esta semana"
        val (box, _) = addRow(title, trending.map { (t, m) -> PosterItem(m.name, t.poster.ifEmpty { m.icon }, m.kind, rating = t.rating, media = m) }, Kind.MOVIE)
        // posiciona logo depois de "Continuar assistindo"
        val anchor = resumeRow?.first?.let { column.indexOfChild(it) } ?: -1
        if (anchor >= 0) {
            column.removeView(box)
            column.addView(box, anchor + 1)
        }
        if (heroFeatures.isNotEmpty()) {
            val seen = heroFeatures.mapNotNull { it.media?.key }.toHashSet()
            val extra = trending.filter { seen.add(it.second.key) }.take(4).map { (t, m) ->
                Feature(m.name, listOf(t.year, if (t.rating.isNotEmpty()) "★ ${t.rating}" else "").filter { it.isNotEmpty() }.joinToString("  •  "),
                    t.backdrop.ifEmpty { m.icon }, t.poster.ifEmpty { m.icon }, m, null)
            }
            val at = PanelConfig.current.banners.size.coerceAtMost(heroFeatures.size)
            heroFeatures.addAll(at, extra)
            while (heroFeatures.size > 12) heroFeatures.removeAt(heroFeatures.size - 1)
            if (heroIndex >= heroFeatures.size) heroIndex = 0
            if (hero?.hasFocus() != true) showHero?.invoke(heroIndex, false)
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

    private fun baseFeatures(rows: List<Pair<Category, List<Media>>>): List<Feature> {
        val out = mutableListOf<Feature>()
        if (kind == null) {
            PanelConfig.current.banners.forEach { b -> out.add(Feature(b.title, b.subtitle, b.image, b.image, null, b)) }
        }
        val seen = HashSet<String>()
        rows.flatMap { it.second.take(4) }.filter { it.icon.isNotBlank() && seen.add(it.key) }.take(8).forEach { m ->
            out.add(Feature(m.name, listOf(m.year, if (m.rating.isNotEmpty()) "★ ${m.rating}" else "").filter { it.isNotEmpty() }.joinToString("  •  "),
                m.icon, m.icon, m, null))
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

        heroFeatures.clear()
        heroFeatures.addAll(features)
        heroIndex = 0
        val heroList = heroFeatures
        fun renderHero(i: Int, animate: Boolean) {
            val f = heroList.getOrNull(i) ?: return
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
        renderHero(0, false)
        showHero = { i, anim -> renderHero(i, anim) }
        heroFrame.setOnClickListener { heroList.getOrNull(heroIndex)?.let { openFeature(it) } }
        hero = heroFrame
        rotate = {
            if (heroList.isNotEmpty()) {
                heroIndex = (heroIndex + 1) % heroList.size
                renderHero(heroIndex, true)
            }
        }

        left.addView(heroFrame, LinearLayout.LayoutParams(-1, -2))
        if (kind == null && streamingList().isNotEmpty()) {
            // tela inicial: streamings no lugar dos atalhos
            val slot = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            heroStreamSlot = slot
            left.addView(slot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        } else {
            left.addView(tilesRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }

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
    private var showHero: ((Int, Boolean) -> Unit)? = null
    private var heroStreamSlot: LinearLayout? = null
    private val heroFeatures = mutableListOf<Feature>()
    private var heroIndex = 0

    private companion object {
        const val STEP_TIMEOUT = 25_000L
        const val TRENDING_TIMEOUT = 60_000L
    }

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
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val list = tiles()
        list.forEachIndexed { i, t ->
            val r = Ui.radius(act).toFloat()
            // ícone em cima e texto embaixo: cabe inteiro em qualquer largura (nada cortado)
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                val p = Ui.dp(act, 6)
                setPadding(p, p, p, p)
                background = android.graphics.drawable.StateListDrawable().apply {
                    val normal = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.withAlpha(t.c1, 215), Ui.withAlpha(t.c2, 215))).apply {
                        cornerRadius = r
                        setStroke(Ui.dp(act, 1), Ui.withAlpha(Color.WHITE, 40))
                    }
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
            }, LinearLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22)))
            box.addView(TextView(act).apply {
                text = t.label
                textSize = if (portrait) 12f else 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (android.os.Build.VERSION.SDK_INT >= 23) hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                setPadding(0, Ui.dp(act, 4), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            Ui.focusZoom(box, 1.06f)
            row.addView(box, LinearLayout.LayoutParams(0, Ui.dp(act, 62), 1f).apply {
                if (i > 0) marginStart = Ui.dp(act, if (portrait) 8 else 10)
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
        rowAdapters.forEach { it.refreshFavs(); it.refreshAll() }
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
