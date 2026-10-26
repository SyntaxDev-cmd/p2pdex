package com.novatv.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.imageLoader
import com.novatv.app.BuildConfig
import com.novatv.app.R
import com.novatv.app.data.AppConfig
import com.novatv.app.data.Category
import com.novatv.app.data.Kind
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Prefs
import com.novatv.app.data.Profiles
import com.novatv.app.data.Repo
import kotlinx.coroutines.launch

/** Configurações do app: player, controle parental, dados e sobre. */
class SettingsActivity : BaseActivity() {

    private lateinit var list: LinearLayout
    private var parentalUnlocked = false

    companion object {
        val resizeNames = listOf("Original (ajustar)", "Esticar", "Zoom (preencher)", "Ajustar à largura", "Ajustar à altura")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.provider == null) {
            finish()
            return
        }
        parentalUnlocked = intent.getStringExtra("section") == "parental"
        window.setBackgroundDrawable(Ui.backgroundGradient())

        val root = FrameLayout(this)
        val scroll = SafeScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(this@SettingsActivity, 20)
            setPadding(pad, pad, pad, Ui.dp(this@SettingsActivity, 40))
            clipChildren = false
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            background = Ui.iconButton(this@SettingsActivity)
            imageTintList = Ui.textColors(Ui.text, Ui.focusText)
            val p = Ui.dp(this@SettingsActivity, 9)
            setPadding(p, p, p, p)
            isFocusable = true
            isClickable = true
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
        header.addView(TextView(this).apply {
            text = "Configurações"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.text)
            setPadding(Ui.dp(this@SettingsActivity, 14), 0, 0, 0)
        })
        column.addView(header)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        column.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 10) })

        // largura máxima confortável em telas grandes
        val maxW = Ui.dp(this, 760)
        scroll.addView(FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(minOf(maxW, resources.displayMetrics.widthPixels), -2, Gravity.CENTER_HORIZONTAL))
        })
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        render()
        if (parentalUnlocked) scroll.post {
            list.findViewWithTag<View>("parental")?.let { scroll.smoothScrollTo(0, it.top) }
        }
    }

    private fun render() {
        val focusedTag = currentFocus?.tag
        list.removeAllViews()
        val profile = Profiles.current

        section("Perfil")
        row("Perfil atual", profile?.let { "${it.emoji}  ${it.name}" + if (it.kids) "  •  infantil" else "" } ?: "-") {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        row("Gerenciar perfis", "Criar, editar, PIN e perfil infantil") {
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra("manage", true))
        }

        section("Player")
        row("Player de vídeo", if (Prefs.playerMode == "external") "Externo (VLC, MX Player...)" else "Interno (recomendado)") {
            Dialogs.choose(this, "Player de vídeo", listOf("Interno (recomendado)", "Externo (VLC, MX Player...)"),
                if (Prefs.playerMode == "external") 1 else 0) { Prefs.playerMode = if (it == 1) "external" else "internal"; render() }
        }
        row("Formato da tela", resizeNames[Prefs.resizeMode.coerceIn(0, resizeNames.size - 1)]) {
            Dialogs.choose(this, "Formato da tela", resizeNames, Prefs.resizeMode) { Prefs.resizeMode = it; render() }
        }
        val formats = listOf("" to "Automático (do servidor)", "ts" to "MPEG-TS (.ts) — mais compatível", "m3u8" to "HLS (.m3u8) — mais estável em redes ruins")
        row("Formato do ao vivo", formats.firstOrNull { it.first == Prefs.liveFormat }?.second ?: formats[0].second) {
            Dialogs.choose(this, "Formato do ao vivo", formats.map { it.second }, formats.indexOfFirst { it.first == Prefs.liveFormat }.coerceAtLeast(0)) {
                Prefs.liveFormat = formats[it].first
                render()
            }
        }
        val buffers = listOf("" to "Automático (do painel)", "low" to "Baixo — troca de canal mais rápida", "normal" to "Normal", "high" to "Alto — internet instável")
        row("Buffer", buffers.firstOrNull { it.first == Prefs.bufferMode }?.second ?: buffers[0].second) {
            Dialogs.choose(this, "Buffer do player", buffers.map { it.second }, buffers.indexOfFirst { it.first == Prefs.bufferMode }.coerceAtLeast(0)) {
                Prefs.bufferMode = buffers[it].first
                render()
            }
        }
        toggle("Próximo episódio automático", "Começa o próximo episódio ao terminar", Prefs.autoNext) { Prefs.autoNext = it }

        section("Controle parental", tag = "parental")
        if (!parentalUnlocked) {
            row("Desbloquear ajustes", "Digite o PIN para alterar o controle parental") {
                Dialogs.pin(this, "Controle parental") { parentalUnlocked = true; render() }
            }
        } else {
            row("PIN parental", "Alterar o PIN (padrão do painel: ${maskPin()})") { changePin() }
            row("Conteúdo adulto", if (PanelConfig.current.hideAdult) "Bloqueado com PIN (definido pelo painel)" else "Liberado pelo painel") {}
            if (profile != null && !profile.kids) {
                listOf(Kind.LIVE to "Canais", Kind.MOVIE to "Filmes", Kind.SERIES to "Séries").forEach { (kind, label) ->
                    val count = profile.locked.count { it.startsWith(kind.name + ":") }
                    row("Bloquear categorias de $label", if (count == 0) "Nenhuma bloqueada neste perfil" else "$count bloqueada(s) neste perfil") {
                        pickLocked(kind, label)
                    }
                }
            } else if (profile?.kids == true) {
                row("Perfil infantil", "Este perfil já mostra apenas conteúdo infantil") {}
            }
        }

        section("Dados")
        row("Limpar continuar assistindo", "Apaga o histórico deste perfil") {
            Dialogs.show(this, "Limpar histórico?", "O \"continuar assistindo\" e os canais recentes deste perfil serão apagados.", listOf(
                Dialogs.Btn("Cancelar"),
                Dialogs.Btn("Limpar", true) { Prefs.clearHistory(); Dialogs.toast(this, "Histórico apagado") },
            ))
        }
        row("Limpar cache de imagens", "Libera espaço no aparelho") {
            imageLoader.memoryCache?.clear()
            launch(kotlinx.coroutines.Dispatchers.IO) { cacheDir.resolve("img").deleteRecursively() }
            Dialogs.toast(this, "Cache de imagens limpo")
        }
        row("Recarregar listas", "Busca canais, filmes e séries de novo") {
            Repo.clear()
            Dialogs.toast(this, "As listas serão recarregadas")
        }

        section("Sobre")
        row("Versão do app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {}
        row("Servidor", Repo.provider?.server?.name.orEmpty()) {}
        row("Painel", AppConfig.panelUrl.ifBlank { "não configurado" }) {}
        list.addView(TextView(this).apply {
            text = "Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB. Informações e imagens de filmes e séries: The Movie Database (TMDB)."
            textSize = 12f
            setTextColor(Ui.withAlpha(Ui.text2, 200))
            setPadding(Ui.dp(this@SettingsActivity, 6), Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 6), 0)
        })
        list.addView(tmdbBadge(), LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@SettingsActivity, 8); leftMargin = Ui.dp(this@SettingsActivity, 6) })

        // devolve o foco ao item que estava selecionado
        val restore = focusedTag?.let { list.findViewWithTag<View>(it) } ?: list.getChildAt(1)
        restore?.requestFocus()
    }

    private fun maskPin(): String = PanelConfig.current.panelPin.let { if (Prefs.customPin.isNotEmpty()) "personalizado" else "•".repeat(it.length) }

    private fun section(title: String, tag: String? = null) {
        list.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.primary.takeIf { it != Color.BLACK } ?: Ui.text2)
            setPadding(Ui.dp(this@SettingsActivity, 6), Ui.dp(this@SettingsActivity, 22), 0, Ui.dp(this@SettingsActivity, 8))
            if (tag != null) this.tag = tag
        })
    }

    private fun baseRow(title: String): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            tag = "row:$title"
            background = android.graphics.drawable.StateListDrawable().apply {
                val r = Ui.radius(this@SettingsActivity)
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.accent, r))
                addState(intArrayOf(android.R.attr.state_pressed), Ui.rounded(Ui.withAlpha(Ui.accent, 180), r))
                addState(intArrayOf(), Ui.rounded(Ui.surface, r))
            }
            val p = Ui.dp(this@SettingsActivity, 16)
            setPadding(p, Ui.dp(this@SettingsActivity, 13), p, Ui.dp(this@SettingsActivity, 13))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.textColors(Ui.text, Ui.focusText))
            isDuplicateParentStateEnabled = true
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@SettingsActivity, 6) })
        return row to texts
    }

    private fun row(title: String, value: String, onClick: () -> Unit) {
        val (row, texts) = baseRow(title)
        texts.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Ui.textColors(Ui.text2, Ui.withAlpha(Ui.focusText, 210)))
            isDuplicateParentStateEnabled = true
        })
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(Ui.textColors(Ui.withAlpha(Ui.text, 120), Ui.focusText))
            isDuplicateParentStateEnabled = true
        })
        row.setOnClickListener { onClick() }
    }

    private fun toggle(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val (row, texts) = baseRow(title)
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(Ui.textColors(Ui.text2, Ui.withAlpha(Ui.focusText, 210)))
            isDuplicateParentStateEnabled = true
        })
        var on = initial
        val sw = Switches.view(this, on)
        row.addView(sw, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 26)))
        row.setOnClickListener {
            on = !on
            Switches.set(sw, on)
            onChange(on)
        }
    }

    private fun tmdbBadge(): View = TextView(this).apply {
        text = "TMDB"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#0D253F"))
        setPadding(Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 4))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#90CEA1"), Color.parseColor("#01B4E4")),
        ).apply { cornerRadius = Ui.dp(this@SettingsActivity, 20).toFloat() }
    }

    private fun changePin() {
        val field = Dialogs.input(this, "Novo PIN (4 a 8 números)", numeric = true, password = true, max = 8)
        Dialogs.show(this, "Novo PIN parental", content = field, buttons = listOf(
            Dialogs.Btn("Cancelar"),
            Dialogs.Btn("Salvar", true) {
                val pin = field.text.toString()
                if (pin.length >= 4) {
                    Prefs.customPin = pin
                    Dialogs.toast(this, "PIN alterado")
                    render()
                } else Dialogs.toast(this, "O PIN precisa de 4 a 8 números")
            },
        ), onShow = { field.requestFocus() })
    }

    /** Escolha múltipla das categorias bloqueadas no perfil atual. */
    private fun pickLocked(kind: Kind, label: String) {
        val profile = Profiles.current ?: return
        launch {
            val cats: List<Category> = runCatching { Repo.allCategories(kind) }.getOrDefault(emptyList())
            if (cats.isEmpty()) {
                Dialogs.toast(this@SettingsActivity, "Nenhuma categoria encontrada")
                return@launch
            }
            val selected = profile.locked.toMutableSet()
            val box = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            cats.forEach { c ->
                val tv = TextView(this@SettingsActivity).apply {
                    textSize = 16f
                    isFocusable = true
                    isClickable = true
                    setPadding(Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 11), Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 11))
                    background = Ui.itemBg(this@SettingsActivity)
                    setTextColor(Ui.textColors())
                    fun paint() { text = (if (c.key in selected) "🔒  " else "○  ") + c.name }
                    paint()
                    setOnClickListener {
                        if (!selected.add(c.key)) selected.remove(c.key)
                        paint()
                    }
                }
                box.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@SettingsActivity, 2) })
            }
            val sv = SafeScrollView(this@SettingsActivity).apply { addView(box) }
            Dialogs.show(this@SettingsActivity, "Bloquear $label", "Itens com 🔒 pedem o PIN neste perfil.", content = sv, buttons = listOf(
                Dialogs.Btn("Cancelar"),
                Dialogs.Btn("Salvar", true) {
                    Profiles.upsert(profile.copy(locked = selected))
                    Repo.onProfileChanged()
                    Dialogs.toast(this@SettingsActivity, "Bloqueios salvos")
                    render()
                },
            ), onShow = { box.getChildAt(0)?.requestFocus() })
            sv.post {
                val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
                if (sv.height > max) sv.layoutParams = sv.layoutParams.apply { height = max }
            }
        }
    }
}
