package com.novatv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Article
import com.novatv.app.data.Espn
import com.novatv.app.data.Game
import com.novatv.app.data.Games
import com.novatv.app.data.League
import com.novatv.app.data.Match
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import com.novatv.app.data.StandingGroup
import com.novatv.app.data.Team
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// =====================================================================================
//  Peças comuns
// =====================================================================================

/** Adaptador simples: cria a visão uma vez e preenche a cada item. */
class SimpleAdapter<T>(
    private val create: (ViewGroup) -> View,
    private val bind: (View, T) -> Unit,
    private val click: (T) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.VH>() {
    var items: List<T> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<T>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = create(parent)
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) click(items[p])
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) = bind(h.itemView, items[position])
    override fun getItemCount() = items.size
}

/** Estilo esportivo (verde gramado + destaque limão). */
object SportStyle {
    val green get() = Sections.sports.palette[0]
    val accent get() = Sections.sports.accent
    const val LIVE = 0xFFFF3B30.toInt()

    fun card(act: MainActivity): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(225, 6, 34, 18), Color.argb(210, 10, 64, 34)),
    ).apply {
        cornerRadius = Ui.radius(act) * 1.2f
        setStroke(Ui.dp(act, 1), Color.argb(70, 182, 255, 59))
    }

    /**
     * Cartão focável: FrameLayout (borda de foco funciona desde o Android 5) com uma
     * coluna dentro. Retorna (cartão, coluna).
     */
    fun shell(act: MainActivity, widthPx: Int, heightPx: Int = -2, pad: Int = 0): Pair<FrameLayout, LinearLayout> {
        val frame = FrameLayout(act).apply {
            isFocusable = true
            isClickable = true
            background = card(act)
            foreground = Ui.posterFrame(act, accent)
            layoutParams = RecyclerView.LayoutParams(widthPx, heightPx).apply { setMargins(Ui.dp(act, 5), Ui.dp(act, 5), Ui.dp(act, 5), Ui.dp(act, 5)) }
        }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, if (heightPx == -2) -2 else -1))
        return frame to col
    }

    fun sectionTitle(act: MainActivity, text: String, action: String? = null, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(act, 6), Ui.dp(act, 14), Ui.dp(act, 6), Ui.dp(act, 6))
        }
        row.addView(View(act).apply { background = Ui.rounded(accent, Ui.dp(act, 3).toFloat()) }, LinearLayout.LayoutParams(Ui.dp(act, 5), Ui.dp(act, 20)))
        row.addView(TextView(act).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(Ui.dp(act, 10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onAction != null) {
            row.addView(TextView(act).apply {
                this.text = "$action ›"
                textSize = 13f
                isFocusable = true
                isClickable = true
                setTextColor(Ui.textColors(Ui.withAlpha(Color.WHITE, 200), Color.BLACK))
                background = Ui.itemBg(act, focusColor = accent)
                setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4))
                setOnClickListener { onAction() }
            })
        }
        return row
    }

    /** Barra de filtros em pílulas (rola na horizontal). */
    class Chips(private val act: MainActivity, private val onPick: (String) -> Unit) {
        private val box = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val view: View = SafeHorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 6))
            addView(box)
        }
        var selected: String = ""
            private set

        fun set(items: List<Pair<String, String>>, selectedKey: String, dot: Set<String> = emptySet()) {
            val hadFocus = box.findFocus() != null
            selected = selectedKey
            box.removeAllViews()
            items.forEach { (key, label) ->
                val sel = key == selectedKey
                val tv = TextView(act).apply {
                    text = if (key in dot) "●  $label" else label
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    isFocusable = true
                    isClickable = true
                    isSelected = sel
                    setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
                    val r = Ui.dp(act, 40).toFloat()
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(accent, r))
                        addState(intArrayOf(android.R.attr.state_selected), Ui.rounded(green, r))
                        addState(intArrayOf(), Ui.rounded(Ui.withAlpha(Color.BLACK, 110), r, Ui.withAlpha(Color.WHITE, 40), Ui.dp(act, 1)))
                    }
                    setTextColor(Ui.textColors(if (key in dot && !sel) LIVE else Color.WHITE, Color.BLACK))
                    setOnClickListener { onPick(key) }
                }
                Ui.focusZoom(tv, 1.06f)
                box.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(act, 8) })
            }
            if (hadFocus) box.getChildAt(items.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0))?.requestFocus()
        }

        fun focus(): Boolean = box.getChildAt(items().coerceAtLeast(0))?.requestFocus() ?: false
        private fun items(): Int = (0 until box.childCount).firstOrNull { box.getChildAt(it).isSelected } ?: 0
    }
}

// =====================================================================================
//  Cartão de jogo
// =====================================================================================

object MatchCard {
    private class Holder(
        val league: TextView, val leagueLogo: ImageView, val badge: TextView,
        val homeLogo: ImageView, val home: TextView, val homeScore: TextView,
        val awayLogo: ImageView, val away: TextView, val awayScore: TextView,
        val info: TextView,
    )

    fun create(act: MainActivity, widthPx: Int = -1): View {
        val (outer, card) = SportStyle.shell(act, widthPx)
        card.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val leagueLogo = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        top.addView(leagueLogo, LinearLayout.LayoutParams(Ui.dp(act, 18), Ui.dp(act, 18)))
        val league = TextView(act).apply {
            textSize = 12f
            setTextColor(Color.argb(210, 220, 255, 220))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0)
        }
        top.addView(league, LinearLayout.LayoutParams(0, -2, 1f))
        val badge = TextView(act).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(Ui.dp(act, 8), Ui.dp(act, 3), Ui.dp(act, 8), Ui.dp(act, 3))
        }
        top.addView(badge)
        card.addView(top)

        fun teamRow(): Triple<ImageView, TextView, TextView> {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(act, 8), 0, 0) }
            val logo = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            row.addView(logo, LinearLayout.LayoutParams(Ui.dp(act, 30), Ui.dp(act, 30)))
            val name = TextView(act).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 8), 0)
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val score = TextView(act).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.END
            }
            row.addView(score)
            card.addView(row)
            return Triple(logo, name, score)
        }
        val (hl, hn, hs) = teamRow()
        val (al, an, asc) = teamRow()
        val info = TextView(act).apply {
            textSize = 12f
            setTextColor(Color.argb(200, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(act, 8), 0, 0)
        }
        card.addView(info)
        outer.tag = Holder(league, leagueLogo, badge, hl, hn, hs, al, an, asc, info)
        Ui.focusZoom(outer, 1.04f)
        return outer
    }

    fun bind(act: MainActivity, v: View, m: Match, channels: List<String> = emptyList()) {
        val h = v.tag as? Holder ?: return
        h.league.text = m.league.name
        h.leagueLogo.load(m.leagueLogo.ifBlank { null }) { error(R.drawable.ic_ball) }
        fun team(t: Team, logo: ImageView, name: TextView, score: TextView, lost: Boolean) {
            logo.load(t.logo.ifBlank { null }) { error(R.drawable.ic_ball); placeholder(R.drawable.ic_ball) }
            name.text = t.short
            score.text = if (m.state == "pre") "" else t.score
            val a = if (m.finished && lost) 150 else 255
            name.setTextColor(Color.argb(a, 255, 255, 255))
            score.setTextColor(Color.argb(a, 255, 255, 255))
        }
        val homeLost = m.finished && m.away.winner
        val awayLost = m.finished && m.home.winner
        team(m.home, h.homeLogo, h.home, h.homeScore, homeLost)
        team(m.away, h.awayLogo, h.away, h.awayScore, awayLost)
        val label = m.statusLabel()
        h.badge.animate().cancel()
        h.badge.alpha = 1f
        when {
            m.live -> {
                h.badge.text = "●  AO VIVO  $label".trim()
                h.badge.background = Ui.rounded(SportStyle.LIVE, Ui.dp(act, 6).toFloat())
                pulse(h.badge)
            }
            m.finished -> {
                h.badge.text = label.uppercase()
                h.badge.background = Ui.rounded(Color.argb(120, 0, 0, 0), Ui.dp(act, 6).toFloat())
            }
            else -> {
                h.badge.text = label
                h.badge.background = Ui.rounded(Ui.withAlpha(SportStyle.green, 220), Ui.dp(act, 6).toFloat())
            }
        }
        val ch = (channels + m.broadcasts).distinct()
        h.info.text = when {
            ch.isNotEmpty() -> "📺  " + ch.take(4).joinToString("  •  ")
            m.venue.isNotBlank() -> "🏟  " + m.venue
            else -> ""
        }
        h.info.visibility = if (h.info.text.isNullOrBlank()) View.GONE else View.VISIBLE
        v.alpha = if (m.finished) 0.8f else 1f
    }

    private fun pulse(v: View) {
        v.animate().alpha(0.55f).setDuration(700).withEndAction {
            if (v.isAttachedToWindow) v.animate().alpha(1f).setDuration(700).withEndAction { if (v.isAttachedToWindow) pulse(v) }.start()
        }.start()
    }
}

/** Liga o jogo da ESPN aos canais da lista do cliente (usando os canais do "Jogos do dia" do painel). */
object MatchChannels {
    @Volatile
    private var panelGames: Pair<Long, List<Game>>? = null

    private suspend fun games(): List<Game> {
        panelGames?.let { if (System.currentTimeMillis() - it.first < 3 * 60_000) return it.second }
        val list = runCatching { Games.load() }.getOrDefault(emptyList())
        panelGames = System.currentTimeMillis() to list
        return list
    }

    private fun same(a: String, b: String): Boolean {
        val x = Games.compact(a)
        val y = Games.compact(b)
        if (x.length < 3 || y.length < 3) return false
        return x.contains(y) || y.contains(x) || x.take(5) == y.take(5)
    }

    /** Nomes de canais citados para o jogo (painel + ESPN). */
    suspend fun names(m: Match): List<String> {
        val g = games().firstOrNull { (same(it.home, m.home.name) && same(it.away, m.away.name)) || (same(it.home, m.away.name) && same(it.away, m.home.name)) }
        return ((g?.channels ?: emptyList()) + m.broadcasts).distinct()
    }

    suspend fun find(m: Match): List<Media> {
        val names = names(m)
        if (names.isEmpty()) return emptyList()
        val g = Game(m.id, "", m.start / 1000, m.home.name, m.away.name, "${m.home.name} x ${m.away.name}", m.home.logo, m.away.logo,
            m.league.name, names, "", "")
        return runCatching { Games.matchChannels(g) }.getOrDefault(emptyList())
    }

    /** Toca no jogo: abre o canal (ou pergunta qual), senão mostra os detalhes. */
    fun open(act: MainActivity, m: Match) {
        act.launch {
            val found = find(m)
            when {
                found.size == 1 -> play(act, found, 0)
                found.size > 1 -> Dialogs.choose(act, "${m.home.short} x ${m.away.short}", found.map { it.name }) { play(act, found, it) }
                else -> {
                    val names = names(m)
                    val msg = buildString {
                        append(m.league.name).append("\n")
                        append(if (m.state == "pre") "Início: ${m.timeLabel()}" else "${m.home.short} ${m.home.score} x ${m.away.score} ${m.away.short} · ${m.statusLabel()}")
                        if (m.venue.isNotBlank()) append("\nEstádio: ").append(m.venue)
                        append("\n\n")
                        append(if (names.isEmpty()) "A transmissão deste jogo ainda não foi informada." else "Transmissão: ${names.joinToString(", ")}\nNão encontramos esse canal na sua lista.")
                    }
                    Dialogs.show(act, "${m.home.short} x ${m.away.short}", msg, listOf(
                        Dialogs.Btn("Fechar"),
                        Dialogs.Btn("Canais esportivos", true) { act.goToTab("channels") },
                    ))
                }
            }
        }
    }

    private fun play(act: MainActivity, list: List<Media>, index: Int) {
        Prefs.addRecent(list[index])
        PlayQueue.live(list, index)
        act.openPlayer()
    }
}

// =====================================================================================
//  AO VIVO e JOGOS
// =====================================================================================

/** mode = "live" (só ao vivo, todas as ligas) ou "games" (agenda por dia e liga). */
class ScoresPage(act: MainActivity, id: String, private val mode: String) : Page(act, id) {
    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, if (portrait) 10 else 22)
        setPadding(pad, Ui.dp(act, 2), pad, 0)
        clipChildren = false
    }
    override val view: View = root
    private val days = SportStyle.Chips(act) { selectDay(it.toInt()) }
    private val leagues = SportStyle.Chips(act) { selectLeague(it) }
    private val status = TextView(act).apply { textSize = 12f; setTextColor(Ui.withAlpha(Color.WHITE, 170)); setPadding(Ui.dp(act, 4), 0, 0, 0) }
    private val grid = RecyclerView(act)
    private val loading = ProgressBar(act)
    private val empty = TextView(act)
    private var channelNames = HashMap<String, List<String>>()
    private val adapter = SimpleAdapter<Match>({ MatchCard.create(act) }, { v, m -> MatchCard.bind(act, v, m, channelNames[m.key].orEmpty()) }, { MatchChannels.open(act, it) })
    private var all: List<Match> = emptyList()
    private var day = 0
    private var league = "all"
    private var job: Job? = null
    private var tick: Job? = null
    private var loaded = false

    init {
        root.addView(status, LinearLayout.LayoutParams(-2, -2))
        if (mode == "games") root.addView(days.view, LinearLayout.LayoutParams(-1, -2))
        root.addView(leagues.view, LinearLayout.LayoutParams(-1, -2))
        val content = FrameLayout(act)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val span = if (portrait) 1 else maxOf(2, act.resources.displayMetrics.widthPixels / Ui.dp(act, 320))
        grid.layoutManager = GridLayoutManager(act, span)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.setPadding(0, 0, 0, Ui.dp(act, 24))
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        content.addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40), Gravity.CENTER))
        empty.apply { gravity = Gravity.CENTER; textSize = 15f; setTextColor(Ui.text2); visibility = View.GONE; setPadding(Ui.dp(act, 24), 0, Ui.dp(act, 24), 0) }
        content.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        buildDays()
    }

    private fun buildDays() {
        val fmt = SimpleDateFormat("EEE dd/MM", Locale("pt", "BR"))
        val list = (-2..5).map { off ->
            val label = when (off) {
                -1 -> "Ontem"
                0 -> "Hoje"
                1 -> "Amanhã"
                else -> fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, off) }.time).replaceFirstChar { it.uppercase() }
            }
            off.toString() to label
        }
        days.set(list, day.toString())
    }

    override fun onShow() {
        if (!loaded) load(false)
        tick?.cancel()
        tick = act.launch {
            while (isActive) {
                delay(30_000)
                // placar ao vivo: atualiza sozinho
                if (day == 0) load(true) else adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    override fun onHide() {
        tick?.cancel()
    }

    override fun reload() {
        loaded = false
        load(true)
    }

    override fun focusContent(): Boolean = grid.getChildAt(0)?.requestFocus() ?: leagues.focus()

    private fun selectDay(d: Int) {
        if (d == day) return
        day = d
        league = "all"
        buildDays()
        load(false)
    }

    private fun selectLeague(key: String) {
        league = key
        apply()
    }

    private fun load(silent: Boolean) {
        job?.cancel()
        job = act.launch {
            if (!silent || all.isEmpty()) {
                loading.visibility = View.VISIBLE
                empty.visibility = View.GONE
                if (!silent) adapter.submit(emptyList())
            }
            val list = runCatching { Espn.day(if (mode == "live") 0 else day) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                all
            }
            all = if (mode == "live") list.filter { it.live } else list
            loading.visibility = View.GONE
            loaded = true
            apply()
            // canais de cada jogo (painel), sem segurar a lista
            launch {
                val map = HashMap<String, List<String>>()
                all.forEach { m -> map[m.key] = runCatching { MatchChannels.names(m) }.getOrDefault(emptyList()) }
                channelNames = map
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    private fun apply() {
        val byLeague = all.groupBy { it.league.code }
        val chips = mutableListOf("all" to "Todos (${all.size})")
        Espn.leagues.forEach { lg -> byLeague[lg.code]?.let { chips.add(lg.code to "${lg.name} (${it.size})") } }
        if (league != "all" && byLeague[league] == null) league = "all"
        leagues.set(chips, league, dot = byLeague.filterValues { l -> l.any { it.live } }.keys)
        val list = if (league == "all") all else all.filter { it.league.code == league }
        adapter.submit(list)
        val live = all.count { it.live }
        status.text = if (mode == "live") "●  $live jogos ao vivo agora · placar atualiza sozinho"
        else "⚽  ${all.size} jogos · ${if (live > 0) "$live ao vivo · " else ""}dados da ESPN"
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (mode == "live") "Nenhum jogo ao vivo agora.\nVeja a agenda na aba JOGOS ou os canais esportivos."
        else "Nenhum jogo nas ligas acompanhadas neste dia."
    }
}

// =====================================================================================
//  NOTÍCIAS
// =====================================================================================

object NewsCard {
    private class Holder(val img: ImageView, val title: TextView, val meta: TextView)

    fun create(act: MainActivity, widthPx: Int = -1): View {
        val (outer, card) = SportStyle.shell(act, widthPx)
        Ui.clipRounded(outer, Ui.radius(act) * 1.2f)
        val img = AspectFrame(act).apply { ratio = 9f / 16f }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        img.addView(iv, FrameLayout.LayoutParams(-1, -1))
        card.addView(img, LinearLayout.LayoutParams(-1, -2))
        val title = TextView(act).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), 0)
        }
        card.addView(title)
        val meta = TextView(act).apply {
            textSize = 12f
            setTextColor(SportStyle.accent)
            setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 12))
        }
        card.addView(meta)
        outer.tag = Holder(iv, title, meta)
        Ui.focusZoom(outer, 1.04f)
        return outer
    }

    fun bind(v: View, a: Article) {
        val h = v.tag as? Holder ?: return
        h.img.load(a.image.ifBlank { null }) { crossfade(200); error(R.drawable.ic_ball) }
        h.title.text = a.title
        h.meta.text = listOf(a.league, a.ago()).filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    /** Abre a notícia: foto, título, resumo e botão para a matéria completa. */
    fun open(act: MainActivity, a: Article) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        if (a.image.isNotBlank()) {
            val frame = AspectFrame(act).apply { ratio = 9f / 16f }
            frame.addView(ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(a.image) }, FrameLayout.LayoutParams(-1, -1))
            Ui.clipRounded(frame, Ui.radius(act))
            box.addView(frame, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(act, 12) })
        }
        box.addView(TextView(act).apply {
            text = a.description.ifBlank { a.title }
            textSize = 15f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.15f)
        })
        box.addView(TextView(act).apply {
            text = listOf("ESPN", a.league, a.ago()).filter { it.isNotBlank() }.joinToString("  ·  ")
            textSize = 12f
            setTextColor(SportStyle.accent)
            setPadding(0, Ui.dp(act, 10), 0, 0)
        })
        val buttons = mutableListOf(Dialogs.Btn("Fechar"))
        if (a.url.isNotBlank()) buttons.add(Dialogs.Btn("Ler matéria completa", true) {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)))
            } catch (e: Exception) {
                Dialogs.toast(act, "Nenhum navegador neste aparelho")
            }
        })
        Dialogs.show(act, a.title, null, buttons, content = android.widget.ScrollView(act).apply { addView(box) })
    }
}

class NewsPage(act: MainActivity, id: String) : Page(act, id) {
    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, if (portrait) 10 else 22)
        setPadding(pad, Ui.dp(act, 2), pad, 0)
    }
    override val view: View = root
    private val chips = SportStyle.Chips(act) { select(it) }
    private val grid = RecyclerView(act)
    private val loading = ProgressBar(act)
    private val empty = TextView(act)
    private val adapter = SimpleAdapter<Article>({ NewsCard.create(act) }, { v, a -> NewsCard.bind(v, a) }, { NewsCard.open(act, it) })
    private var key = "top"
    private var job: Job? = null
    private var loaded = false

    init {
        root.addView(chips.view, LinearLayout.LayoutParams(-1, -2))
        val content = FrameLayout(act)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val span = if (portrait) 1 else maxOf(2, act.resources.displayMetrics.widthPixels / Ui.dp(act, 300))
        grid.layoutManager = GridLayoutManager(act, span)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.setPadding(0, 0, 0, Ui.dp(act, 24))
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        content.addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40), Gravity.CENTER))
        empty.apply { gravity = Gravity.CENTER; textSize = 15f; setTextColor(Ui.text2); visibility = View.GONE; text = "Nenhuma notícia agora." }
        content.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        buildChips()
    }

    private fun buildChips() {
        chips.set(listOf("top" to "🔥  Destaques") + Espn.leagues.take(10).map { it.code to it.name }, key)
    }

    override fun onShow() {
        if (!loaded) load()
    }

    override fun reload() {
        loaded = false
        load()
    }

    override fun focusContent(): Boolean = grid.getChildAt(0)?.requestFocus() ?: chips.focus()

    private fun select(k: String) {
        if (k == key) return
        key = k
        buildChips()
        load()
    }

    private fun load() {
        job?.cancel()
        job = act.launch {
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
            adapter.submit(emptyList())
            val list = runCatching {
                if (key == "top") Espn.headlines(Espn.leagues.take(4)) else Espn.news(Espn.leagues.first { it.code == key })
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emptyList()
            }
            loaded = true
            loading.visibility = View.GONE
            adapter.submit(list.take(60))
            empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            grid.scrollToPosition(0)
        }
    }
}

// =====================================================================================
//  TABELA
// =====================================================================================

class StandingsPage(act: MainActivity, id: String) : Page(act, id) {
    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, if (portrait) 10 else 22)
        setPadding(pad, Ui.dp(act, 2), pad, 0)
    }
    override val view: View = root
    private val chips = SportStyle.Chips(act) { select(it) }
    private val table = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = SafeScrollView(act).apply { isFillViewport = true; addView(table, ViewGroup.LayoutParams(-1, -2)) }
    private val loading = ProgressBar(act)
    private var key = ""
    private var job: Job? = null
    private var loaded = false

    init {
        root.addView(chips.view, LinearLayout.LayoutParams(-1, -2))
        val content = FrameLayout(act)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        content.addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40), Gravity.CENTER))
        key = Espn.leagues.firstOrNull()?.code.orEmpty()
        buildChips()
    }

    private fun tableLeagues(): List<League> = Espn.leagues.filter { !it.code.contains("copa") && !it.code.contains("world") }

    private fun buildChips() = chips.set(tableLeagues().map { it.code to it.name }, key)

    override fun onShow() {
        if (!loaded) load()
    }

    override fun reload() {
        loaded = false
        load()
    }

    override fun focusContent(): Boolean = chips.focus()

    private fun select(k: String) {
        if (k == key) return
        key = k
        buildChips()
        load()
    }

    private fun load() {
        val lg = tableLeagues().firstOrNull { it.code == key } ?: tableLeagues().firstOrNull() ?: return
        job?.cancel()
        job = act.launch {
            loading.visibility = View.VISIBLE
            table.removeAllViews()
            val groups = runCatching { Espn.standings(lg) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emptyList()
            }
            loaded = true
            loading.visibility = View.GONE
            if (groups.isEmpty()) {
                table.addView(TextView(act).apply {
                    text = "Tabela indisponível para ${lg.name} agora."
                    textSize = 15f
                    setTextColor(Ui.text2)
                    gravity = Gravity.CENTER
                    setPadding(0, Ui.dp(act, 60), 0, 0)
                })
                return@launch
            }
            groups.forEach { g -> render(lg, g) }
            Ui.fadeIn(table)
        }
    }

    private fun render(lg: League, g: StandingGroup) {
        val multi = g.rows.size <= 8
        table.addView(SportStyle.sectionTitle(act, if (multi || g.name.isNotBlank()) "${lg.name} · ${g.name}".trimEnd(' ', '·') else lg.name))
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = SportStyle.card(act)
            setPadding(Ui.dp(act, 8), Ui.dp(act, 6), Ui.dp(act, 8), Ui.dp(act, 6))
        }
        val wide = !portrait
        val cols = if (wide) listOf("P", "J", "V", "E", "D", "GP", "GC", "SG") else listOf("P", "J", "V", "SG")
        box.addView(row(act, "#", "", "Time", cols, header = true, zone = 0))
        val n = g.rows.size
        g.rows.forEach { s ->
            val zone = when {
                multi -> if (s.rank <= 2) 1 else 0
                s.rank <= 4 -> 1
                s.rank <= 6 -> 2
                s.rank > n - 4 && n >= 16 -> 3
                else -> 0
            }
            val values = if (wide) listOf(s.points, s.played, s.wins, s.draws, s.losses, s.goalsFor, s.goalsAgainst, s.goalDiff)
            else listOf(s.points, s.played, s.wins, s.goalDiff)
            box.addView(row(act, s.rank.toString(), s.logo, s.team, values, header = false, zone = zone))
        }
        table.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(act, 8) })
        if (!multi) table.addView(TextView(act).apply {
            text = "■ Classificação para a fase principal   ■ Pré / outras copas   ■ Rebaixamento"
            textSize = 11f
            setTextColor(Ui.withAlpha(Color.WHITE, 160))
            setPadding(Ui.dp(act, 6), 0, 0, Ui.dp(act, 16))
        })
    }

    private fun row(act: MainActivity, pos: String, logo: String, name: String, values: List<String>, header: Boolean, zone: Int): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(act, 6), Ui.dp(act, if (header) 6 else 7), Ui.dp(act, 6), Ui.dp(act, if (header) 6 else 7))
            if (!header) {
                isFocusable = true
                background = Ui.itemBg(act, focusColor = Ui.withAlpha(SportStyle.accent, 90))
            }
        }
        val zoneColor = when (zone) {
            1 -> 0xFF2E7DFF.toInt()
            2 -> 0xFF19C463.toInt()
            3 -> 0xFFE53935.toInt()
            else -> Color.TRANSPARENT
        }
        r.addView(View(act).apply { background = Ui.rounded(zoneColor, Ui.dp(act, 2).toFloat()) }, LinearLayout.LayoutParams(Ui.dp(act, 4), Ui.dp(act, 22)))
        r.addView(TextView(act).apply {
            text = pos
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (header) SportStyle.accent else Color.WHITE)
        }, LinearLayout.LayoutParams(Ui.dp(act, 30), -2))
        if (header) r.addView(View(act), LinearLayout.LayoutParams(Ui.dp(act, 26), 1))
        else r.addView(ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(logo.ifBlank { null }) { error(R.drawable.ic_ball) } },
            LinearLayout.LayoutParams(Ui.dp(act, 24), Ui.dp(act, 24)).apply { marginEnd = Ui.dp(act, 2) })
        r.addView(TextView(act).apply {
            text = name
            textSize = 14f
            typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (header) SportStyle.accent else Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(Ui.dp(act, 8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        values.forEachIndexed { i, v ->
            r.addView(TextView(act).apply {
                text = v
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = if (header || i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (header) SportStyle.accent else if (i == 0) Color.WHITE else Ui.withAlpha(Color.WHITE, 200))
            }, LinearLayout.LayoutParams(Ui.dp(act, 38), -2))
        }
        return r
    }
}

// =====================================================================================
//  INÍCIO
// =====================================================================================

/** Tela inicial: ao vivo agora, próximos jogos, notícias, canais e a tabela do Brasileirão. */
class SportsHomePage(act: MainActivity, id: String) : Page(act, id) {
    private val column = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, if (portrait) 8 else 18)
        setPadding(pad, Ui.dp(act, 2), pad, Ui.dp(act, 30))
        clipChildren = false
        clipToPadding = false
    }
    private val scroll = SafeScrollView(act).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipChildren = false
        addView(column, ViewGroup.LayoutParams(-1, -2))
    }
    private val loading = ProgressBar(act)
    override val view: View = FrameLayout(act).apply {
        addView(scroll, FrameLayout.LayoutParams(-1, -1))
        addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 42), Ui.dp(act, 42), Gravity.CENTER))
    }
    private var job: Job? = null
    private var tick: Job? = null
    private var loaded = false
    private var first: View? = null
    private var matchAdapter: SimpleAdapter<Match>? = null

    override fun onShow() {
        if (!loaded) load()
        tick?.cancel()
        tick = act.launch {
            while (isActive) {
                delay(45_000)
                refreshMatches()
            }
        }
    }

    override fun onHide() {
        tick?.cancel()
    }

    override fun reload() {
        loaded = false
        load()
    }

    override fun focusContent(): Boolean = first?.requestFocus() ?: false

    private fun horizontal(): RecyclerView = RecyclerView(act).apply {
        layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
        itemAnimator = null
        clipToPadding = false
        clipChildren = false
        isHorizontalScrollBarEnabled = false
        setPadding(0, Ui.dp(act, 2), Ui.dp(act, 24), Ui.dp(act, 4))
    }

    private suspend fun matchesNow(): Pair<String, List<Match>> {
        val today = runCatching { Espn.day(0) }.getOrDefault(emptyList())
        val live = today.filter { it.live }
        if (live.isNotEmpty()) return "●  Ao vivo agora" to live
        val next = today.filter { it.state == "pre" }
        if (next.isNotEmpty()) return "Próximos jogos de hoje" to next
        val tomorrow = runCatching { Espn.day(1) }.getOrDefault(emptyList())
        return (if (tomorrow.isNotEmpty()) "Jogos de amanhã" else "Jogos de hoje") to tomorrow.ifEmpty { today }
    }

    private suspend fun refreshMatches() {
        val a = matchAdapter ?: return
        val (_, list) = matchesNow()
        if (list.isNotEmpty()) a.submit(list.take(20))
    }

    private fun load() {
        job?.cancel()
        column.removeAllViews()
        loading.visibility = View.VISIBLE
        first = null
        job = act.launch {
            // ---- jogos
            val (title, matches) = matchesNow()
            loading.visibility = View.GONE
            column.addView(SportStyle.sectionTitle(act, title, "Agenda completa") { act.goToTab("games") })
            val cardW = Ui.dp(act, if (portrait) 280 else 310)
            val ma = SimpleAdapter<Match>({ MatchCard.create(act, cardW) }, { v, m -> MatchCard.bind(act, v, m) }, { MatchChannels.open(act, it) })
            matchAdapter = ma
            val mr = horizontal().apply { adapter = ma }
            ma.submit(matches.take(20))
            if (matches.isEmpty()) column.addView(hint("Sem jogos das ligas acompanhadas agora."))
            else column.addView(mr, LinearLayout.LayoutParams(-1, -2))
            mr.post { if (first == null) first = mr.getChildAt(0) }

            // ---- notícias
            val newsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            column.addView(newsBox)
            // ---- canais
            val chBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            column.addView(chBox)
            // ---- tabela
            val tableBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            column.addView(tableBox)
            loaded = true

            launch {
                val news = runCatching { Espn.headlines(Espn.leagues.take(3)) }.getOrDefault(emptyList())
                if (news.isEmpty()) return@launch
                newsBox.addView(SportStyle.sectionTitle(act, "Últimas notícias", "Ver todas") { act.goToTab("news") })
                val w = Ui.dp(act, if (portrait) 250 else 280)
                val na = SimpleAdapter<Article>({ NewsCard.create(act, w) }, { v, a -> NewsCard.bind(v, a) }, { NewsCard.open(act, it) })
                newsBox.addView(horizontal().apply { adapter = na }, LinearLayout.LayoutParams(-1, -2))
                na.submit(news.take(15))
                Ui.fadeIn(newsBox)
            }
            launch {
                val chans = runCatching { Repo.liveIndex().map { it.second } }.getOrDefault(emptyList())
                val favs = Prefs.favorites(com.novatv.app.data.Kind.LIVE)
                val list = (favs + chans).distinctBy { it.key }.take(30)
                if (list.isEmpty()) return@launch
                chBox.addView(SportStyle.sectionTitle(act, if (favs.isNotEmpty()) "Seus canais" else "Canais esportivos", "Todos os canais") { act.goToTab("channels") })
                val ca = SimpleAdapter<Media>({ channelTile(it) }, { v, m -> bindChannel(v, m) }, { m ->
                    Prefs.addRecent(m)
                    PlayQueue.live(list, list.indexOf(m).coerceAtLeast(0))
                    act.openPlayer()
                })
                chBox.addView(horizontal().apply { adapter = ca }, LinearLayout.LayoutParams(-1, -2))
                ca.submit(list)
                Ui.fadeIn(chBox)
            }
            launch {
                val lg = Espn.leagues.firstOrNull { it.code == "bra.1" } ?: Espn.leagues.firstOrNull() ?: return@launch
                val groups = runCatching { Espn.standings(lg) }.getOrDefault(emptyList())
                val g = groups.firstOrNull() ?: return@launch
                tableBox.addView(SportStyle.sectionTitle(act, "Classificação · ${lg.name}", "Tabela completa") { act.goToTab("table") })
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                g.rows.take(if (portrait) 4 else 6).forEach { s ->
                    val (cell, inner) = SportStyle.shell(act, -1, -2, Ui.dp(act, 8))
                    inner.gravity = Gravity.CENTER
                    inner.apply {
                        addView(TextView(act).apply { text = "${s.rank}º"; textSize = 13f; setTextColor(SportStyle.accent); typeface = Typeface.DEFAULT_BOLD })
                        addView(ImageView(act).apply { load(s.logo.ifBlank { null }) { error(R.drawable.ic_ball) } }, LinearLayout.LayoutParams(Ui.dp(act, 36), Ui.dp(act, 36)).apply { topMargin = Ui.dp(act, 4) })
                        addView(TextView(act).apply { text = s.abbr.ifBlank { s.team }; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, Ui.dp(act, 4), 0, 0) })
                        addView(TextView(act).apply { text = "${s.points} pts"; textSize = 12f; setTextColor(Ui.withAlpha(Color.WHITE, 190)) })
                    }
                    cell.setOnClickListener { act.goToTab("table") }
                    Ui.focusZoom(cell, 1.06f)
                    row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(act, 8) })
                }
                tableBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(act, 5), Ui.dp(act, 4), Ui.dp(act, 5), 0) })
                Ui.fadeIn(tableBox)
            }
        }
    }

    private fun hint(text: String) = TextView(act).apply {
        this.text = text
        textSize = 14f
        setTextColor(Ui.text2)
        setPadding(Ui.dp(act, 8), Ui.dp(act, 8), 0, Ui.dp(act, 8))
    }

    private fun channelTile(parent: ViewGroup): View {
        val w = Ui.dp(act, 132)
        val (outer, box) = SportStyle.shell(act, w, Ui.dp(act, 112), Ui.dp(act, 8))
        box.gravity = Gravity.CENTER
        box.addView(ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; id = View.generateViewId(); tag = "logo" },
            LinearLayout.LayoutParams(-1, Ui.dp(act, 52)))
        box.addView(TextView(act).apply {
            tag = "name"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(act, 6), 0, 0)
        })
        Ui.focusZoom(outer, 1.06f)
        return outer
    }

    private fun bindChannel(v: View, m: Media) {
        (v.findViewWithTag<ImageView>("logo"))?.load(m.icon.ifBlank { null }) { error(R.drawable.ic_tv) }
        (v.findViewWithTag<TextView>("name"))?.text = m.name
    }
}
