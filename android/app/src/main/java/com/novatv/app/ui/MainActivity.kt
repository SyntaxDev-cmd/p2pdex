package com.novatv.app.ui

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.novatv.app.BuildConfig
import com.novatv.app.R
import com.novatv.app.data.AuthException
import com.novatv.app.data.Banner
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Prefs
import com.novatv.app.data.Profiles
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {

    lateinit var pagesContainer: FrameLayout
        private set
    private lateinit var tabsBox: LinearLayout
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var clock: TextView
    private lateinit var bellDot: View

    private val pages = HashMap<String, Page>()
    private val tabViews = LinkedHashMap<String, TextView>()
    private val bottomViews = LinkedHashMap<String, View>()
    private lateinit var backdrop: SectionBackdrop
    private var current: Page? = null
    private var currentTab = ""
    private var tabJob: Job? = null
    private var backAt = 0L
    private var fromPlayer = false
    private var firstStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.provider == null) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        applyBackground(findViewById(R.id.bg))
        pagesContainer = findViewById(R.id.pages)
        // fundo ilustrado das seções (Kids, Jogos, Anime), atrás do conteúdo
        backdrop = SectionBackdrop(this).apply { alpha = 0f }
        (findViewById<View>(R.id.bg).parent as FrameLayout).addView(backdrop, 2, FrameLayout.LayoutParams(-1, -1))
        tabsBox = findViewById(R.id.tabs)
        tabsScroll = findViewById(R.id.tabsScroll)
        clock = findViewById(R.id.clock)
        bellDot = findViewById(R.id.bellDot)

        setupTopBar()
        buildTabs()
        buildBottomBar()
        startClock()
        hideEmptyOptionalTabs()

        val cfg = PanelConfig.current
        val start = savedInstanceState?.getString("tab")
            ?: if (Profiles.current?.kids == true && tabViews.containsKey("kids")) "kids" else cfg.startTab
        switchTab(if (tabViews.containsKey(start)) start else tabViews.keys.firstOrNull() ?: "tv", focusTab = true)
        showPopups()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", currentTab)
    }

    // ------------------------------------------------------------------ topo

    private fun setupTopBar() {
        val cfg = PanelConfig.current
        val logo = findViewById<ImageView>(R.id.logo)
        val name = findViewById<TextView>(R.id.appName)
        name.text = cfg.appName
        name.setTextColor(Ui.text)
        // com logo cadastrada aparece só a logo (sem o nome ao lado)
        if (cfg.logo.isNotEmpty()) {
            name.visibility = View.INVISIBLE
            logo.layoutParams = logo.layoutParams.apply { height = Ui.dp(this@MainActivity, 40) }
            logo.load(cfg.logo) {
                listener(onError = { _, _ ->
                    logo.setImageResource(R.mipmap.ic_launcher)
                    name.visibility = View.VISIBLE
                })
            }
        } else {
            logo.setImageResource(R.mipmap.ic_launcher)
        }
        clock.setTextColor(Ui.text)
        val icons = listOf(R.id.btnSearch, R.id.btnRefresh, R.id.btnBell)
        icons.forEach { id ->
            val v = findViewById<ImageView>(id)
            v.background = Ui.iconButton(this)
            v.imageTintList = Ui.textColors(Ui.text, Ui.focusText)
            Ui.focusZoom(v, 1.12f)
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener { openSearch() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<View>(R.id.btnBell).setOnClickListener { showMessages() }
        findViewById<TextView>(R.id.btnAccount).apply {
            val prof = Profiles.current
            if (prof != null) {
                text = prof.emoji
                background = Avatar.drawable(this@MainActivity, prof, radiusDp = 10, focusRing = true)
            } else {
                text = "👤"
                background = Ui.iconButton(this@MainActivity)
            }
            Ui.focusZoom(this, 1.12f)
            setOnClickListener { showAccount() }
        }
        bellDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.primary) }
        updateBell()
    }

    private fun startClock() {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        launch {
            while (isActive) {
                clock.text = fmt.format(Date())
                delay(15_000)
            }
        }
    }

    // ------------------------------------------------------------------ abas

    private fun buildTabs() {
        tabsBox.removeAllViews()
        tabViews.clear()
        val kidsProfile = Profiles.current?.kids == true
        PanelConfig.current.tabs.filter { !(kidsProfile && it.id == "sports") }.forEach { t ->
            val tv = TextView(this).apply {
                text = t.label
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 7), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 7))
                background = Ui.tabBg(this@MainActivity)
                setTextColor(Ui.textColors(Ui.text, Ui.focusText))
                isFocusable = true
                isClickable = true
                letterSpacing = 0.03f
                setOnClickListener { switchTab(t.id) }
                setOnKeyListener { _, code, ev ->
                    if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) current?.focusContent() ?: false else false
                }
            }
            if (t.icon.isNotBlank()) loadTabIcon(tv, t.icon, t.iconOnly)
            val isKids = t.id == "kids"
            Ui.focusZoom(tv, 1.08f) { has ->
                if (isKids) rainbow(tv, !has)
                if (has && Ui.isTv && t.id != currentTab) {
                    // no controle remoto a aba abre ao parar o foco nela
                    tabJob?.cancel()
                    tabJob = launch {
                        delay(450)
                        if (tv.isFocused) switchTab(t.id)
                    }
                }
            }
            if (isKids) tv.post { rainbow(tv, true) }
            tabsBox.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(this@MainActivity, 6)
            })
            tabViews[t.id] = tv
        }
    }

    /** Celular em pé: barra de navegação inferior (como nos apps de streaming). */
    private fun buildBottomBar() {
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (Ui.isTv || !portrait) return
        val order = listOf("highlights", "tv", "sports", "movies", "series", "kids", "favorites")
        val tabs = PanelConfig.current.tabs.filter { tabViews.containsKey(it.id) }.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }.take(4)
        if (tabs.size < 2) return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Ui.withAlpha(Ui.surfaceSolid, 250), Ui.withAlpha(Ui.surfaceSolid, 215)))
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
        }
        val tint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(Ui.primary.takeIf { it != Ui.surfaceSolid } ?: Ui.text, Ui.withAlpha(Ui.text, 150)),
        )
        fun item(id: String, label: String, icon: Int, click: () -> Unit): View {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 2))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.withAlpha(Ui.text, 40)), null, null)
                setOnClickListener { click() }
            }
            box.addView(ImageView(this).apply {
                setImageResource(icon)
                imageTintList = tint
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)))
            box.addView(TextView(this).apply {
                text = label
                textSize = 11f
                maxLines = 1
                setTextColor(tint)
                isDuplicateParentStateEnabled = true
            })
            return box
        }
        tabs.forEach { t ->
            val icon = when (t.id) {
                "tv" -> R.drawable.ic_tv
                "sports" -> R.drawable.ic_ball
                "highlights" -> R.drawable.ic_home
                "movies" -> R.drawable.ic_cinema
                "series" -> R.drawable.ic_list
                "kids" -> R.drawable.ic_kids
                "favorites" -> R.drawable.ic_heart
                else -> R.drawable.ic_star
            }
            val label = t.label.lowercase().replaceFirstChar { it.uppercase() }
            val v = item(t.id, label, icon) { switchTab(t.id) }
            bottomViews[t.id] = v
            bar.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(item("search", "Buscar", R.drawable.ic_search) { openSearch() }, LinearLayout.LayoutParams(0, -2, 1f))
        (pagesContainer.parent as LinearLayout).addView(bar, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * Ícone/logotipo da aba enviado no painel. Mostrado nas cores originais (sem pintar),
     * com 28dp de altura e a largura proporcional. Com "só o ícone" ligado no painel,
     * a imagem substitui o nome da aba (o nome continua como fallback se a imagem falhar).
     */
    private fun loadTabIcon(tv: TextView, url: String, iconOnly: Boolean) {
        val h = Ui.dp(this, 28)
        val maxW = Ui.dp(this, 150)
        val req = coil.request.ImageRequest.Builder(this)
            .data(url)
            .size(maxW * 2, h * 2)
            .allowHardware(false)
            .target(onSuccess = { d ->
                val iw = d.intrinsicWidth.takeIf { it > 0 } ?: h
                val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
                val w = (iw.toFloat() * h / ih).toInt().coerceIn(h / 2, maxW)
                d.setBounds(0, 0, w, h)
                if (android.os.Build.VERSION.SDK_INT >= 23) tv.compoundDrawableTintList = null
                if (iconOnly) {
                    tv.contentDescription = tv.text
                    tv.text = ""
                    tv.paint.shader = null
                    tv.compoundDrawablePadding = 0
                    tv.minHeight = h + Ui.dp(this, 14)
                } else {
                    tv.compoundDrawablePadding = Ui.dp(this, 8)
                }
                tv.setCompoundDrawablesRelative(d, null, null, null)
            })
            .build()
        coil.Coil.imageLoader(this).enqueue(req)
    }

    private fun rainbow(tv: TextView, on: Boolean) {
        if (!on || tv.width == 0 || tv.text.isEmpty()) {
            tv.paint.shader = null
            tv.invalidate()
            return
        }
        tv.paint.shader = LinearGradient(
            tv.paddingLeft.toFloat(), 0f, (tv.width - tv.paddingRight).toFloat(), 0f,
            intArrayOf(0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(), 0xFFE040FB.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        tv.invalidate()
    }

    /** JOGOS e ANIME só aparecem se existir conteúdo com as palavras-chave. */
    private fun hideEmptyOptionalTabs() {
        launch {
            if (tabViews.containsKey("sports") && !PanelConfig.current.gamesEnabled) {
                val has = runCatching { Repo.filtered(Kind.LIVE, Repo.config.sportsKeywords).isNotEmpty() }.getOrDefault(true)
                if (!has) tabViews["sports"]?.visibility = View.GONE
            }
            if (tabViews.containsKey("anime")) {
                val has = runCatching { Repo.animeCategories().isNotEmpty() }.getOrDefault(true)
                if (!has) tabViews["anime"]?.visibility = View.GONE
            }
        }
    }

    fun switchTab(id: String, focusTab: Boolean = false) {
        if (id == currentTab) return
        val cfg = PanelConfig.current
        if (currentTab == "kids" && cfg.kidsLock) {
            Dialogs.pin(this, "PIN para sair do modo Kids") { doSwitch(id, focusTab) }
            tabViews["kids"]?.requestFocus()
            return
        }
        doSwitch(id, focusTab)
    }

    private fun doSwitch(id: String, focusTab: Boolean) {
        current?.let {
            it.onHide()
            it.view.visibility = View.GONE
        }
        currentTab = id
        val page = pages.getOrPut(id) { createPage(id) }
        if (page.view.parent == null) pagesContainer.addView(page.view)
        page.view.visibility = View.VISIBLE
        page.view.alpha = 0f
        page.view.animate().alpha(1f).setDuration(200).start()
        current = page
        applySection(id)
        page.onShow()
        tabViews.forEach { (k, v) -> v.isSelected = k == id }
        bottomViews.forEach { (k, v) -> v.isSelected = k == id }
        tabViews[id]?.let { v ->
            if (focusTab) v.requestFocus()
            tabsScroll.post { tabsScroll.smoothScrollTo((v.left - Ui.dp(this, 40)).coerceAtLeast(0), 0) }
        }
    }

    /** Tema próprio de cada seção; o perfil infantil usa o tema Kids em todo o app. */
    private fun applySection(id: String) {
        val style = if (Profiles.current?.kids == true) Sections.kids else Sections.of(id)
        if (style != null) {
            if (backdrop.style?.id != style.id) {
                backdrop.alpha = 0f
                backdrop.style = style
            }
            backdrop.animate().alpha(1f).setDuration(350).start()
        } else {
            backdrop.animate().alpha(0f).setDuration(250).start()
        }
    }

    private fun createPage(id: String): Page = when (id) {
        "tv" -> LivePage(this, id, "tv")
        "sports" -> if (PanelConfig.current.gamesEnabled) GamesPage(this, id) else LivePage(this, id, "sports")
        "highlights" -> HighlightsPage(this, id)
        "movies" -> GridPage(this, id, "movies")
        "series" -> GridPage(this, id, "series")
        "kids" -> GridPage(this, id, "kids")
        "anime" -> GridPage(this, id, "anime")
        "favorites" -> GridPage(this, id, "favorites")
        else -> GridPage(this, id, "movies")
    }

    // ------------------------------------------------------------------ navegação

    /** Atalhos da tela inicial: abre uma aba (se existir). */
    fun goToTab(id: String) {
        if (tabViews.containsKey(id)) switchTab(id, focusTab = false) else Dialogs.toast(this, "Seção indisponível")
        pages[id]?.focusContent()
    }

    /** Abre a aba de filmes/séries já numa categoria (inclusive virtuais: ano, recentes). */
    fun openCategory(tabId: String, c: com.novatv.app.data.Category) {
        if (!tabViews.containsKey(tabId)) {
            Dialogs.toast(this, "Seção indisponível")
            return
        }
        switchTab(tabId)
        (pages[tabId] as? GridPage)?.openFromOutside(c)
    }

    fun openPlayer() {
        fromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    fun openDetail(m: Media) {
        DetailActivity.pending = m
        startActivity(Intent(this, DetailActivity::class.java))
    }

    private fun openSearch(query: String = "") {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("q", query))
    }

    fun bannerAction(b: Banner) {
        when (b.action) {
            "search" -> openSearch(b.value)
            "tab" -> {
                val target = when (b.value.lowercase().trim()) {
                    "filmes", "movies" -> "movies"
                    "series", "séries" -> "series"
                    "kids" -> "kids"
                    "anime" -> "anime"
                    "jogos", "sports" -> "sports"
                    "destaques", "highlights" -> "highlights"
                    "favoritos", "favorites" -> "favorites"
                    else -> "tv"
                }
                if (tabViews.containsKey(target)) switchTab(target, focusTab = true)
            }
            "url" -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.value))) }
                .onFailure { Dialogs.toast(this, "Não foi possível abrir o link") }
        }
    }

    fun handleError(e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) return
        if (e is AuthException) {
            Dialogs.show(this, "Acesso", e.message ?: "Faça login novamente.", listOf(Dialogs.Btn("Entrar", true) {
                Repo.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }), cancelable = false)
        } else {
            Dialogs.toast(this, "Falha de conexão. Tentando novamente pode resolver.")
        }
    }

    override fun onStart() {
        super.onStart()
        if (firstStart) {
            firstStart = false
            return
        }
        if (::pagesContainer.isInitialized && current != null) {
            val page = current
            if (fromPlayer && page is LivePage) {
                fromPlayer = false
                page.resumeFrom(PlayQueue.current?.media?.key)
            } else {
                fromPlayer = false
                page?.onShow()
            }
        }
    }

    override fun onStop() {
        current?.onHide()
        super.onStop()
    }

    override fun onDestroy() {
        pages.values.forEach { it.onDestroy() }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> {
                openSearch()
                return true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                val live = (current as? LivePage) ?: (current as? GamesPage)?.livePage()
                if (live != null) live.openGuide() else if (tabViews.containsKey("tv")) switchTab("tv", true)
                return true
            }
            KeyEvent.KEYCODE_PROG_RED -> { switchTab("tv", true); return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { switchTab("movies", true); return true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { switchTab("series", true); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (tabViews.containsKey("kids")) switchTab("kids", true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current?.onBack() == true) return
        val tab = tabViews[currentTab]
        if (tab != null && !tabsBox.hasFocus()) {
            tab.requestFocus()
            return
        }
        if (System.currentTimeMillis() - backAt < 2000) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            backAt = System.currentTimeMillis()
            Dialogs.toast(this, "Pressione voltar novamente para sair")
        }
    }

    // ------------------------------------------------------------------ menus

    private fun refreshAll() {
        Dialogs.toast(this, "Atualizando...")
        launch {
            PanelConfig.refresh()
            Ui.apply(PanelConfig.current)
            Repo.clear()
            pages.values.forEach { it.reload() }
            current?.onShow()
            updateBell()
        }
    }

    private fun updateBell() {
        val msgs = PanelConfig.current.messages
        bellDot.visibility = if (msgs.any { it.id > Prefs.lastSeenMessage }) View.VISIBLE else View.GONE
    }

    private fun showMessages() {
        val msgs = PanelConfig.current.messages
        if (msgs.isEmpty()) {
            Dialogs.show(this, "Avisos", "Nenhum aviso no momento.")
            return
        }
        Prefs.lastSeenMessage = msgs.maxOf { it.id }
        updateBell()
        val text = msgs.joinToString("\n\n") { "• ${it.title}\n${it.body}".trim() }
        Dialogs.show(this, "Avisos", text)
    }

    private fun showPopups() {
        val pending = PanelConfig.current.messages.filter { it.popup && !Prefs.popupSeen(it.id) }
        val m = pending.firstOrNull() ?: return
        Prefs.markPopup(m.id)
        Dialogs.show(this, m.title, m.body)
    }

    private fun showAccount() {
        val profile = Profiles.current
        val options = listOf(
            "Trocar perfil",
            "Configurações e player",
            "Controle parental",
            "Minha assinatura",
            "Sair da conta",
        )
        Dialogs.choose(this, profile?.let { "${it.emoji}  ${it.name}" } ?: "Conta", options) { i ->
            when (i) {
                0 -> startActivity(Intent(this, ProfilesActivity::class.java))
                1 -> startActivity(Intent(this, SettingsActivity::class.java))
                2 -> {
                    Dialogs.pin(this, "Controle parental", message = "Digite o PIN para alterar os bloqueios.") {
                        startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "parental"))
                    }
                }
                3 -> showSubscription()
                4 -> Dialogs.show(this, "Sair", "Deseja sair desta conta neste aparelho?", listOf(
                    Dialogs.Btn("Cancelar"),
                    Dialogs.Btn("Sair", true) {
                        Repo.logout()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    },
                ))
            }
        }
    }

    private fun showSubscription() {
        val acc = Repo.account
        val p = Repo.provider
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val info = buildString {
            append("Usuário: ").append(Prefs.username).append('\n')
            append("Servidor: ").append(p?.server?.name.orEmpty()).append('\n')
            if (acc != null) {
                append("Validade: ").append(acc.expiresAt?.let { fmt.format(Date(it)) } ?: "Ilimitada").append('\n')
                if (acc.maxConnections.isNotEmpty()) append("Telas: ").append(acc.activeConnections).append(" de ").append(acc.maxConnections).append('\n')
                if (acc.trial) append("Conta de teste\n")
            }
            append("\nVersão ").append(BuildConfig.VERSION_NAME)
            val support = PanelConfig.current.support
            if (support.isNotBlank()) append("\n").append(support)
        }
        Dialogs.show(this, "Minha assinatura", info)
    }
}
