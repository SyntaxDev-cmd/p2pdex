package com.novatv.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Game
import com.novatv.app.data.Games
import com.novatv.app.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Aba JOGOS: jogos do dia (API do painel) com horário, times, campeonato e canal.
 * Tocar no jogo abre direto o canal que está transmitindo. "Canais esportivos" mostra
 * a lista ao vivo de esportes.
 */
class GamesPage(act: MainActivity, id: String) : Page(act, id) {

    private val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
    }
    override val view: View = root

    private val chipsBox = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
    private val content = FrameLayout(act)
    private val grid = RecyclerView(act)
    private val loading = ProgressBar(act)
    private val empty = TextView(act)
    private val adapter = GameAdapter()
    private var all: List<Game> = emptyList()
    private var filter: String = "today"
    private var sportsPage: LivePage? = null
    private var showingChannels = false
    private var job: Job? = null
    private var tick: Job? = null
    private var loaded = false

    init {
        val pad = Ui.dp(act, if (portrait) 10 else 22)
        root.setPadding(pad, Ui.dp(act, 2), pad, 0)
        root.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 8))
            addView(chipsBox)
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val span = if (portrait) 1 else maxOf(2, (act.resources.displayMetrics.widthPixels / Ui.dp(act, 330)))
        grid.layoutManager = GridLayoutManager(act, span)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.setPadding(0, 0, 0, Ui.dp(act, 24))
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        content.addView(loading, FrameLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40), Gravity.CENTER))
        empty.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Ui.text2)
            visibility = View.GONE
            setPadding(Ui.dp(act, 24), 0, Ui.dp(act, 24), 0)
        }
        content.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    override fun onShow() {
        if (showingChannels) sportsPage?.onShow()
        if (!loaded) load(false)
        tick?.cancel()
        // atualiza "AO VIVO" / contagem a cada minuto e recarrega a lista a cada 5
        tick = act.launch {
            var n = 0
            while (isActive) {
                delay(60_000)
                n++
                if (n % 5 == 0) load(true) else adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onHide() {
        tick?.cancel()
        sportsPage?.onHide()
    }

    override fun onDestroy() {
        sportsPage?.onDestroy()
    }

    override fun reload() {
        loaded = false
        load(true)
        sportsPage?.reload()
    }

    override fun focusContent(): Boolean =
        if (showingChannels) sportsPage?.focusContent() == true else grid.getChildAt(0)?.requestFocus() ?: chipsBox.getChildAt(0)?.requestFocus() ?: false

    /** Usado pela tecla GUIDE quando estiver nos canais esportivos. */
    fun livePage(): LivePage? = if (showingChannels) sportsPage else null

    private fun load(force: Boolean) {
        job?.cancel()
        job = act.launch {
            if (all.isEmpty()) loading.visibility = View.VISIBLE
            all = runCatching { Games.load(force) }.getOrDefault(all)
            loading.visibility = View.GONE
            loaded = true
            buildChips()
            apply()
        }
    }

    private fun buildChips() {
        chipsBox.removeAllViews()
        val liveCount = all.count { it.liveStatus() == "live" }
        val leagues = all.map { it.league }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(10).map { it.key }
        chip("⚽  Jogos de hoje (${all.size})", "today")
        if (liveCount > 0) chip("●  Ao vivo agora ($liveCount)", "live", Color.parseColor("#FF3B30"))
        leagues.forEach { chip(it, "league:$it") }
        chip("📺  Canais esportivos", "channels")
    }

    private fun chip(label: String, key: String, dot: Int = 0) {
        val selected = (key == "channels" && showingChannels) || (!showingChannels && key == filter)
        val tv = TextView(act).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            isSelected = selected
            setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
            val r = Ui.dp(act, 40).toFloat()
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.accent, r))
                addState(intArrayOf(android.R.attr.state_selected), Ui.rounded(Ui.primary, r))
                addState(intArrayOf(), Ui.rounded(Ui.surface, r, Ui.withAlpha(Color.WHITE, 40), Ui.dp(act, 1)))
            }
            setTextColor(Ui.textColors(if (dot != 0 && !selected) dot else Ui.text, Ui.focusText))
            setOnClickListener { select(key) }
        }
        Ui.focusZoom(tv, 1.06f)
        chipsBox.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(act, 8) })
    }

    private fun select(key: String) {
        if (key == "channels") {
            showingChannels = true
            val page = sportsPage ?: LivePage(act, "sports", "sports").also {
                sportsPage = it
                content.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            }
            grid.visibility = View.GONE
            empty.visibility = View.GONE
            page.view.visibility = View.VISIBLE
            page.onShow()
        } else {
            showingChannels = false
            sportsPage?.let { it.onHide(); it.view.visibility = View.GONE }
            grid.visibility = View.VISIBLE
            filter = key
            apply()
        }
        buildChips()
    }

    private fun apply() {
        if (showingChannels) return
        val list = when {
            filter == "live" -> all.filter { it.liveStatus() == "live" }
            filter.startsWith("league:") -> all.filter { it.league == filter.removePrefix("league:") }
            else -> all
        }
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (all.isEmpty()) "Nenhum jogo informado para hoje.\nVeja os canais esportivos ao vivo." else "Nenhum jogo neste filtro."
        if (all.isEmpty() && loaded && !showingChannels && sportsPage == null) select("channels")
    }

    /** Toca no jogo: encontra o canal na lista do cliente e abre. */
    private fun openGame(g: Game) {
        act.launch {
            val found = runCatching { Games.matchChannels(g) }.getOrDefault(emptyList())
            when {
                found.isEmpty() -> Dialogs.show(act, g.title,
                    if (g.channels.isEmpty()) "O canal deste jogo não foi informado." else "Transmissão: ${g.channels.joinToString(", ")}\n\nNão encontramos esse canal na sua lista.",
                    listOf(Dialogs.Btn("Fechar"), Dialogs.Btn("Ver canais esportivos", true) { select("channels") }))
                found.size == 1 -> play(found, 0)
                else -> Dialogs.choose(act, "Assistir ${g.home} x ${g.away}", found.map { it.name }) { play(found, it) }
            }
        }
    }

    private fun play(list: List<com.novatv.app.data.Media>, index: Int) {
        Prefs.addRecent(list[index])
        PlayQueue.live(list, index)
        act.openPlayer()
    }

    // ------------------------------------------------------------------ cartões

    private inner class GameAdapter : RecyclerView.Adapter<GameAdapter.VH>() {
        var items: List<Game> = emptyList()
            private set

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Game>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val body: View = v.findViewById(R.id.body)
            val time: TextView = v.findViewById(R.id.time)
            val status: TextView = v.findViewById(R.id.status)
            val league: TextView = v.findViewById(R.id.league)
            val home: TextView = v.findViewById(R.id.home)
            val away: TextView = v.findViewById(R.id.away)
            val homeLogo: ImageView = v.findViewById(R.id.homeLogo)
            val awayLogo: ImageView = v.findViewById(R.id.awayLogo)
            val versus: TextView = v.findViewById(R.id.versus)
            val channels: TextView = v.findViewById(R.id.channels)
            val focusFrame: View = v.findViewById(R.id.focusFrame)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(act).inflate(R.layout.item_game, parent, false)
            val h = VH(v)
            val r = Ui.radius(act) * 1.2f
            h.body.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Ui.withAlpha(Ui.surfaceSolid, 210), Ui.withAlpha(Ui.primary, 70))).apply {
                cornerRadius = r
                setStroke(Ui.dp(act, 1), Ui.withAlpha(Color.WHITE, 28))
            }
            h.focusFrame.background = Ui.posterFrame(act)
            h.time.setTextColor(Ui.text)
            h.league.setTextColor(Ui.text2)
            h.home.setTextColor(Ui.text)
            h.away.setTextColor(Ui.text)
            h.versus.setTextColor(Ui.withAlpha(Ui.text, 150))
            h.channels.setTextColor(Ui.text)
            Ui.focusZoom(v, 1.04f)
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) openGame(items[p])
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val g = items[position]
            h.time.text = g.time.ifBlank { "--:--" }
            h.league.text = g.league
            h.home.text = g.home.ifBlank { g.title }
            h.away.text = g.away
            h.versus.text = if (g.score.isNotBlank()) g.score else "X"
            h.homeLogo.load(g.homeLogo.ifBlank { null }) { error(R.drawable.ic_star); placeholder(R.drawable.ic_star) }
            h.awayLogo.load(g.awayLogo.ifBlank { null }) { error(R.drawable.ic_star); placeholder(R.drawable.ic_star) }
            h.homeLogo.visibility = if (g.away.isBlank()) View.GONE else View.VISIBLE
            h.awayLogo.visibility = h.homeLogo.visibility
            h.channels.text = if (g.channels.isEmpty()) "Canal não informado" else "📺  " + g.channels.joinToString("  •  ")
            val now = System.currentTimeMillis() / 1000
            when (g.liveStatus(now)) {
                "live" -> badge(h.status, "AO VIVO", Color.parseColor("#FF3B30"))
                "ended" -> badge(h.status, "ENCERRADO", Color.parseColor("#55000000"))
                "soon" -> {
                    val mins = ((g.ts - now) / 60).toInt()
                    if (mins in 1..180) badge(h.status, if (mins < 60) "EM $mins MIN" else "EM ${mins / 60}H${"%02d".format(mins % 60)}", Ui.withAlpha(Ui.primary, 200))
                    else h.status.visibility = View.GONE
                }
                else -> h.status.visibility = View.GONE
            }
            h.itemView.alpha = if (g.liveStatus(now) == "ended") 0.6f else 1f
        }

        private fun badge(tv: TextView, text: String, color: Int) {
            tv.text = text
            tv.setTextColor(Color.WHITE)
            tv.background = Ui.rounded(color, Ui.dp(act, 6).toFloat())
            tv.visibility = View.VISIBLE
        }

        override fun getItemCount() = items.size
    }
}
