package com.novatv.app.ui

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Category
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Página base das abas. */
abstract class Page(val act: MainActivity, val id: String) {
    abstract val view: View
    open fun onShow() {}
    open fun onHide() {}
    open fun onDestroy() {}
    open fun onBack(): Boolean = false
    open fun focusContent(): Boolean = false
    open fun reload() {}
    protected val portrait: Boolean get() = act.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    protected fun header(tv: TextView?) {
        tv ?: return
        tv.background = Ui.rounded(Ui.primary, Ui.radius(act) * 0.8f)
        tv.setTextColor(Color.WHITE)
    }
}

/**
 * TV ao vivo estilo "box": categorias | lista de canais | prévia + EPG.
 * mode = "tv" (todas as categorias + favoritos/recentes) ou "sports" (só esportes).
 */
class LivePage(act: MainActivity, id: String, private val mode: String) : Page(act, id) {

    override val view: View = LayoutInflater.from(act).inflate(R.layout.page_live, act.pagesContainer, false)

    private val catList: RecyclerView = view.findViewById(R.id.catList)
    private val chList: RecyclerView = view.findViewById(R.id.chList)
    private val chHeader: TextView = view.findViewById(R.id.chHeader)
    private val chLoading: ProgressBar = view.findViewById(R.id.chLoading)
    private val chEmpty: TextView = view.findViewById(R.id.chEmpty)
    private val previewBox: AspectFrame = view.findViewById(R.id.previewBox)
    private val playerView: PlayerView = view.findViewById(R.id.preview)
    private val previewLogo: ImageView = view.findViewById(R.id.previewLogo)
    private val previewMsg: TextView = view.findViewById(R.id.previewMsg)
    private val nowName: TextView = view.findViewById(R.id.nowName)
    private val nowEpg: TextView = view.findViewById(R.id.nowEpg)
    private val nextEpg: TextView = view.findViewById(R.id.nextEpg)
    private val epgProgress: ProgressBar = view.findViewById(R.id.epgProgress)
    private val btnFav: ImageView = view.findViewById(R.id.btnFav)

    private val cats = CategoryAdapter(portrait, ::onCategory, onRight = { focusChannels() })
    private val channels = ChannelAdapter(::onChannel, ::onChannelLong, onLeft = { focusCategories() })

    private var player: ExoPlayer? = null
    private var playing: Media? = null
    private var currentCat: Category? = null
    private var loaded = false
    private var loadJob: Job? = null
    private var epgJob: Job? = null
    private var retries = 0
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        val r = Ui.radius(act)
        view.findViewById<View>(R.id.catPanel)?.background = Ui.panel(act)
        view.findViewById<View>(R.id.chPanel).background = Ui.panel(act)
        view.findViewById<View>(R.id.infoBox).background = Ui.panel(act)
        header(view.findViewById(R.id.catHeader))
        header(chHeader)
        view.findViewById<TextView>(R.id.chHint)?.setTextColor(Ui.text2)
        nowName.setTextColor(Ui.text)
        nowEpg.setTextColor(Ui.text2)
        nextEpg.setTextColor(Ui.text2)
        chEmpty.setTextColor(Ui.text2)
        epgProgress.progressTintList = ColorStateList.valueOf(Ui.primary)
        Ui.clipRounded(previewBox, r)
        previewBox.foreground = Ui.posterFrame(act)
        previewBox.setOnClickListener { openFull() }
        Ui.focusZoom(previewBox, 1.02f)
        val roundBg = { android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Ui.withAlpha(Color.BLACK, 150))
        } }
        view.findViewById<ImageView>(R.id.btnFull).apply {
            background = roundBg()
            setOnClickListener { openFull() }
        }
        btnFav.background = roundBg()
        view.findViewById<TextView>(R.id.btnGuide).apply {
            background = Ui.button(act, false)
            setTextColor(Ui.textColors(Ui.text, Ui.focusText))
            Ui.focusZoom(this, 1.04f)
            setOnClickListener { openGuide() }
        }
        btnFav.setOnClickListener { playing?.let { m -> toggleFav(m) } }
        if (Ui.isTv) view.findViewById<TextView>(R.id.chHint)?.text = "OK: assistir  •  Segure OK: favoritar"
        else view.findViewById<TextView>(R.id.chHint)?.text = "Toque: assistir  •  Toque e segure: favoritar"

        catList.layoutManager = LinearLayoutManager(act, if (portrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        catList.adapter = cats
        catList.itemAnimator = null
        chList.layoutManager = LinearLayoutManager(act)
        chList.adapter = channels
        chList.itemAnimator = null
        chList.setHasFixedSize(true)
        chList.setItemViewCacheSize(20)
        if (mode == "sports") {
            chHeader.text = "Canais esportivos"
            chHeader.background = Ui.rounded(Sections.sports.palette[0], Ui.radius(act) * 0.8f)
            view.findViewById<TextView>(R.id.catHeader)?.background = Ui.rounded(Sections.sports.palette[0], Ui.radius(act) * 0.8f)
        }
    }

    override fun onShow() {
        if (!loaded) load()
    }

    override fun onHide() {
        releasePlayer()
    }

    override fun onDestroy() {
        releasePlayer()
    }

    override fun reload() {
        loaded = false
        load()
    }

    override fun focusContent(): Boolean = focusChannels() || catList.requestFocus()

    private fun load() {
        loadJob?.cancel()
        loadJob = act.launch {
            showLoading(true)
            try {
                val list = mutableListOf<Category>()
                if (mode == "tv") {
                    list.add(Category("fav", "Favoritos", Kind.LIVE, special = "fav"))
                    list.add(Category("recent", "Assistidos recentemente", Kind.LIVE, special = "recent"))
                    list.addAll(Repo.categories(Kind.LIVE))
                } else {
                    list.addAll(Repo.filtered(Kind.LIVE, Repo.config.sportsKeywords))
                }
                val last = Prefs.lastCategory(id)
                var sel = list.indexOfFirst { it.key == last && !it.locked }
                if (sel < 0) sel = list.indexOfFirst { it.special == null && !it.locked }
                if (sel < 0 && list.isNotEmpty()) sel = 0
                cats.submit(list, sel)
                loaded = true
                if (sel >= 0) {
                    catList.scrollToPosition(sel)
                    openCategory(list[sel])
                } else {
                    showLoading(false)
                    showEmpty(if (mode == "sports") "Nenhuma categoria de esportes encontrada." else "Nenhuma categoria encontrada.")
                }
            } catch (e: Exception) {
                showLoading(false)
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
                openCategory(c)
            }
            return
        }
        cats.select(pos)
        openCategory(c)
    }

    private fun openCategory(c: Category) {
        currentCat = c
        if (c.special == null) Prefs.setLastCategory(id, c.key)
        chHeader.text = c.name
        loadJob?.cancel()
        loadJob = act.launch {
            showLoading(true)
            try {
                val items = Repo.items(c)
                channels.submit(items)
                channels.setPlaying(playing?.key)
                chList.scrollToPosition(0)
                showLoading(false)
                if (items.isEmpty()) showEmpty(if (c.special == "fav") "Segure OK em um canal para favoritar." else "Nenhum canal nesta categoria.")
                else chEmpty.visibility = View.GONE
            } catch (e: Exception) {
                showLoading(false)
                act.handleError(e)
                showEmpty("Erro ao carregar canais.")
            }
        }
    }

    private fun showLoading(on: Boolean) {
        chLoading.visibility = if (on) View.VISIBLE else View.GONE
        if (on) chEmpty.visibility = View.GONE
    }

    private fun showEmpty(msg: String) {
        chEmpty.text = msg
        chEmpty.visibility = View.VISIBLE
    }

    private fun onChannel(pos: Int, m: Media) {
        if (playing?.key == m.key && player != null) {
            openFull()
            return
        }
        play(m)
    }

    private fun onChannelLong(pos: Int, m: Media) = toggleFav(m)

    private fun toggleFav(m: Media) {
        val added = Prefs.toggleFavorite(m)
        Dialogs.toast(act, if (added) "♥ Adicionado aos favoritos" else "Removido dos favoritos")
        channels.refreshFavs()
        channels.notifyDataSetChanged()
        updateFavButton()
        if (currentCat?.special == "fav") openCategory(currentCat!!)
    }

    private fun updateFavButton() {
        val m = playing
        btnFav.visibility = if (m == null) View.GONE else View.VISIBLE
        val fav = m != null && Prefs.isFavorite(m)
        btnFav.imageTintList = ColorStateList.valueOf(if (fav) Ui.fav else Color.WHITE)
        btnFav.alpha = if (fav) 1f else 0.85f
    }

    private fun play(m: Media) {
        playing = m
        retries = 0
        Prefs.addRecent(m)
        channels.setPlaying(m.key)
        updateFavButton()
        nowName.text = m.name
        nowEpg.text = ""
        nextEpg.text = ""
        epgProgress.visibility = View.GONE
        previewMsg.visibility = View.GONE
        previewLogo.visibility = View.VISIBLE
        previewLogo.load(m.icon.ifEmpty { null }) { error(R.drawable.ic_tv) }
        val p = player ?: Players.create(act, preview = true).also {
            player = it
            playerView.player = it
            it.addListener(listener)
        }
        val url = Repo.provider?.streamUrl(m) ?: return
        p.setMediaItem(Players.item(url))
        p.prepare()
        p.playWhenReady = true
        loadEpg(m)
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            previewLogo.visibility = View.GONE
            previewMsg.visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            val m = playing ?: return
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (retries < 2) {
                retries++
                if (retries == 2) Repo.provider?.failover()
                act.launch {
                    delay(800L * retries)
                    if (playing?.key == m.key && player === p) {
                        p.setMediaItem(Players.item(Repo.provider?.streamUrl(m).orEmpty()))
                        p.prepare()
                    }
                }
                return
            }
            previewMsg.text = "Canal indisponível no momento"
            previewMsg.visibility = View.VISIBLE
        }
    }

    private fun loadEpg(m: Media) {
        epgJob?.cancel()
        epgJob = act.launch {
            // atualiza a cada minuto enquanto o canal estiver na prévia
            while (isActive && playing?.key == m.key) {
                val list = runCatching { Repo.epg(m) }.getOrDefault(emptyList())
                if (playing?.key != m.key) return@launch
                val t = System.currentTimeMillis()
                val now = list.firstOrNull { it.start <= t && it.end > t } ?: list.firstOrNull()
                if (now == null) {
                    nowEpg.text = "Sem guia de programação"
                    epgProgress.visibility = View.GONE
                    nextEpg.text = ""
                } else {
                    nowEpg.text = "${timeFmt.format(Date(now.start))} – ${timeFmt.format(Date(now.end))}  ${now.title}"
                    epgProgress.progress = now.progress()
                    epgProgress.visibility = View.VISIBLE
                    val next = list.getOrNull(list.indexOf(now) + 1)
                    nextEpg.text = next?.let { "A seguir: ${timeFmt.format(Date(it.start))}  ${it.title}" }.orEmpty()
                }
                delay(60_000)
            }
        }
    }

    private fun openFull() {
        val m = playing ?: channels.items.firstOrNull() ?: return
        val list = channels.items.ifEmpty { listOf(m) }
        val idx = list.indexOfFirst { it.key == m.key }.coerceAtLeast(0)
        PlayQueue.live(list, idx)
        releasePlayer()
        act.openPlayer()
    }

    /** Abre o guia de programação com os canais da categoria atual. */
    fun openGuide() {
        val list = channels.items
        if (list.isEmpty()) {
            Dialogs.toast(act, "Escolha uma categoria com canais")
            return
        }
        GuideActivity.open(act, list, list.indexOfFirst { it.key == playing?.key }.coerceAtLeast(0))
    }

    /** Chamado ao voltar do player: retoma a prévia no canal que estava tocando. */
    fun resumeFrom(key: String?) {
        val m = channels.items.firstOrNull { it.key == key } ?: playing ?: return
        play(m)
        val idx = channels.items.indexOf(m)
        if (idx >= 0) chList.scrollToPosition(idx)
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    private fun focusChannels(): Boolean {
        val lm = chList.layoutManager as? LinearLayoutManager ?: return false
        if (channels.itemCount == 0) return false
        val target = channels.items.indexOfFirst { it.key == playing?.key }.takeIf { it >= 0 }
            ?: lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        val v = lm.findViewByPosition(target)
        if (v != null) return v.requestFocus()
        chList.scrollToPosition(target)
        chList.post { lm.findViewByPosition(target)?.requestFocus() }
        return true
    }

    private fun focusCategories(): Boolean {
        if (portrait) return false
        val lm = catList.layoutManager as? LinearLayoutManager ?: return false
        val target = cats.selected.coerceAtLeast(0)
        val v = lm.findViewByPosition(target)
        if (v != null) return v.requestFocus()
        catList.scrollToPosition(target)
        catList.post { lm.findViewByPosition(target)?.requestFocus() }
        return true
    }
}
