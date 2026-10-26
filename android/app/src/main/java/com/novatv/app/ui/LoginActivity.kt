package com.novatv.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.AuthException
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import com.novatv.app.data.ServerDef
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Login responsivo:
 *  - TV / tablet deitado: marca à esquerda, formulário à direita
 *  - celular deitado (tela baixa): só o formulário, com logo compacta e rolagem
 *  - celular em pé: marca em cima, formulário ocupando a largura
 */
class LoginActivity : BaseActivity() {

    // no celular o teclado precisa redimensionar a tela (modo imersivo impede isso)
    override val immersiveMode: Boolean get() = Ui.isTv

    private lateinit var serverView: TextView
    private lateinit var dns: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var errorBox: View
    private lateinit var error: TextView
    private lateinit var loginBtn: TextView
    private lateinit var loading: ProgressBar
    private lateinit var passToggle: ImageView
    private var servers: List<ServerDef> = emptyList()
    private var selected = 0
    private var busy = false
    private var passVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        applyBackground(findViewById(R.id.bg))
        val cfg = PanelConfig.current

        serverView = findViewById(R.id.server)
        dns = findViewById(R.id.dns)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        errorBox = findViewById(R.id.errorBox)
        error = findViewById(R.id.error)
        loginBtn = findViewById(R.id.login)
        loading = findViewById(R.id.loading)
        passToggle = findViewById(R.id.passToggle)

        styleScreen(cfg)
        adaptLayout()
        setupServers(cfg)

        user.setText(Prefs.username)
        user.setSelection(user.text.length)
        pass.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                doLogin(); true
            } else false
        }
        loginBtn.setOnClickListener { doLogin() }
        passToggle.setOnClickListener { togglePassword() }
        // ao corrigir um campo, o erro some
        listOf(user, pass, dns).forEach { f -> f.setOnFocusChangeListener { _, has -> if (has) scrollTo(f) } }
        intent.getStringExtra("error")?.let { showError(it) }

        (if (dns.visibility == View.VISIBLE && dns.text.isEmpty()) dns else if (user.text.isEmpty()) user else pass).requestFocus()
        animateIn()
    }

    // ------------------------------------------------------------------ visual

    private fun styleScreen(cfg: PanelConfig) {
        val r = Ui.dp(this, 24).toFloat()
        findViewById<View>(R.id.form).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Ui.withAlpha(Ui.surfaceSolid, 200), Ui.withAlpha(Ui.surfaceSolid, 160)),
        ).apply {
            cornerRadius = r
            setStroke(Ui.dp(this@LoginActivity, 1), Ui.withAlpha(Color.WHITE, 36))
        }
        glow(findViewById(R.id.glow1), Ui.primary)
        glow(findViewById(R.id.glow2), Ui.withAlpha(Ui.accent, 255).takeIf { Ui.accent != Color.WHITE } ?: Ui.bg1)

        findViewById<TextView>(R.id.name).apply { text = cfg.appName; setTextColor(Ui.text) }
        findViewById<TextView>(R.id.welcome).apply {
            text = cfg.welcome.ifBlank { cfg.splashText.ifBlank { "Seu entretenimento em um só lugar." } }
            setTextColor(Ui.text2)
        }
        findViewById<TextView>(R.id.formTitle).setTextColor(Ui.text)
        findViewById<TextView>(R.id.subtitle).setTextColor(Ui.text2)
        findViewById<TextView>(R.id.support).apply {
            text = cfg.support
            setTextColor(Ui.withAlpha(Ui.text2, 200))
            visibility = if (cfg.support.isBlank()) View.GONE else View.VISIBLE
        }
        val logos = listOf(findViewById<ImageView>(R.id.logo), findViewById(R.id.formLogo))
        if (cfg.logo.isNotEmpty()) logos.forEach { it.load(cfg.logo) { error(R.mipmap.ic_launcher) } }

        val iconTint = Ui.textColors(Ui.withAlpha(Ui.text, 160), Ui.text)
        listOf(dns, user, pass).forEach {
            it.background = Ui.inputBg(this)
            it.setTextColor(Ui.text)
            it.setHintTextColor(Ui.withAlpha(Ui.text, 120))
            it.compoundDrawablesRelative[0]?.mutate()?.setTintList(iconTint)
        }
        serverView.background = Ui.inputBg(this)
        serverView.setTextColor(Ui.text)
        serverView.compoundDrawablesRelative.forEach { it?.mutate()?.setTintList(iconTint) }
        Ui.focusZoom(serverView, 1.02f)

        passToggle.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.accent) })
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
        }
        passToggle.imageTintList = Ui.textColors(Ui.withAlpha(Ui.text, 170), Ui.focusText)

        errorBox.background = Ui.rounded(Color.argb(200, 183, 28, 28), Ui.dp(this, 12).toFloat())
        findViewById<ImageView>(R.id.errorIcon).imageTintList = ColorStateList.valueOf(Color.WHITE)

        loginBtn.background = Ui.button(this, true)
        loginBtn.setTextColor(Ui.textColors(Color.WHITE, Ui.focusText))
        loading.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        Ui.focusZoom(loginBtn, 1.03f) { has ->
            loading.indeterminateTintList = ColorStateList.valueOf(if (has) Ui.focusText else Color.WHITE)
        }

        buildFeatures()
    }

    private fun glow(v: View, color: Int) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = Ui.dp(this@LoginActivity, 260).toFloat()
            colors = intArrayOf(Ui.withAlpha(color, 110), Ui.withAlpha(color, 0))
        }
    }

    /** Chips com o que o app oferece (só na TV / telas largas). */
    private fun buildFeatures() {
        val box = findViewById<LinearLayout>(R.id.features)
        box.removeAllViews()
        val tabs = PanelConfig.current.tabs.map { it.id }
        val items = listOfNotNull(
            if ("tv" in tabs) "TV ao vivo com guia de programação" else null,
            if ("movies" in tabs || "series" in tabs) "Filmes e séries para assistir quando quiser" else null,
            if ("kids" in tabs) "Área Kids segura para as crianças" else null,
            if ("favorites" in tabs) "Seus favoritos sempre à mão" else null,
        )
        items.forEach { label ->
            box.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Ui.withAlpha(Ui.text, 225))
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = Ui.dp(this@LoginActivity, 12)
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Ui.primary)
                    setSize(Ui.dp(this@LoginActivity, 10), Ui.dp(this@LoginActivity, 10))
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                setPadding(0, Ui.dp(this@LoginActivity, 6), 0, Ui.dp(this@LoginActivity, 6))
            })
        }
    }

    /** Ajusta larguras e o que aparece conforme o tamanho real da tela. */
    private fun adaptLayout() {
        val conf = resources.configuration
        val widthDp = conf.screenWidthDp
        val heightDp = conf.screenHeightDp
        val portrait = conf.orientation == Configuration.ORIENTATION_PORTRAIT
        val form = findViewById<View>(R.id.form)
        val brand = findViewById<View>(R.id.brand)
        val formLogo = findViewById<View>(R.id.formLogo)
        val features = findViewById<View>(R.id.features)

        val maxForm = if (portrait) 460 else 420
        val formWidth = minOf(Ui.dp(this, maxForm), resources.displayMetrics.widthPixels - Ui.dp(this, if (portrait) 36 else 56))
        form.layoutParams = form.layoutParams.apply { width = formWidth }

        if (!portrait) {
            val compact = widthDp < 700 || heightDp < 420
            brand.visibility = if (compact) View.GONE else View.VISIBLE
            formLogo.visibility = if (compact) View.VISIBLE else View.GONE
            features.visibility = if (heightDp >= 480) View.VISIBLE else View.GONE
        } else {
            // celular pequeno em pé: marca mais enxuta
            if (heightDp < 640) findViewById<TextView>(R.id.welcome).visibility = View.GONE
        }
    }

    private fun animateIn() {
        val form = findViewById<View>(R.id.form)
        val brand = findViewById<View>(R.id.brand)
        form.alpha = 0f
        form.translationY = Ui.dp(this, 28).toFloat()
        form.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(420)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        if (brand.visibility == View.VISIBLE) {
            brand.alpha = 0f
            brand.translationX = -Ui.dp(this, 20).toFloat()
            brand.animate().alpha(1f).translationX(0f).setDuration(500).start()
        }
    }

    private fun scrollTo(v: View) {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        scroll.postDelayed({
            val rect = android.graphics.Rect()
            v.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(v, rect)
            scroll.smoothScrollTo(0, (rect.top - Ui.dp(this, 90)).coerceAtLeast(0))
        }, 250)
    }

    // ------------------------------------------------------------------ servidores

    private fun setupServers(cfg: PanelConfig) {
        servers = Repo.servers()
        selected = servers.indexOfFirst { it.id == Prefs.serverId }.coerceAtLeast(0)
        when {
            cfg.servers.isEmpty() -> {
                // sem servidores no painel: cliente informa a DNS
                serverView.visibility = View.GONE
                dns.visibility = View.VISIBLE
                dns.setText(Prefs.customDns)
            }
            servers.size == 1 || !cfg.allowServerSelect -> serverView.visibility = View.GONE
            else -> {
                serverView.visibility = View.VISIBLE
                updateServerLabel()
                serverView.setOnClickListener {
                    Dialogs.choose(this, "Escolha o servidor", servers.map { it.name }, selected) {
                        selected = it
                        updateServerLabel()
                        user.requestFocus()
                    }
                }
            }
        }
    }

    private fun updateServerLabel() {
        serverView.text = servers.getOrNull(selected)?.name.orEmpty()
    }

    private fun togglePassword() {
        passVisible = !passVisible
        val sel = pass.selectionEnd
        pass.inputType = InputType.TYPE_CLASS_TEXT or
            if (passVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        pass.typeface = Typeface.DEFAULT
        pass.setSelection(sel.coerceIn(0, pass.text.length))
        passToggle.setImageResource(if (passVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        passToggle.contentDescription = if (passVisible) "Ocultar senha" else "Mostrar senha"
    }

    // ------------------------------------------------------------------ login

    private fun showError(msg: String) {
        error.text = msg
        if (errorBox.visibility != View.VISIBLE) {
            errorBox.visibility = View.VISIBLE
            errorBox.alpha = 0f
            errorBox.animate().alpha(1f).setDuration(180).start()
        }
        // "tremidinha" de erro
        val form = findViewById<View>(R.id.form)
        val d = Ui.dp(this, 8).toFloat()
        form.animate().translationX(d).setDuration(50).withEndAction {
            form.animate().translationX(-d).setDuration(70).withEndAction {
                form.animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
    }

    private fun clearError() {
        errorBox.visibility = View.GONE
    }

    private fun doLogin() {
        if (busy) return
        val u = user.text.toString().trim()
        val p = pass.text.toString().trim()
        when {
            dns.visibility == View.VISIBLE && dns.text.isBlank() -> { showError("Informe a DNS do servidor."); dns.requestFocus(); return }
            u.isEmpty() -> { showError("Informe seu usuário."); user.requestFocus(); return }
            p.isEmpty() -> { showError("Informe sua senha."); pass.requestFocus(); return }
        }
        var list = servers
        if (dns.visibility == View.VISIBLE) {
            var d = dns.text.toString().trim().trimEnd('/')
            if (!d.startsWith("http://") && !d.startsWith("https://")) d = "http://$d"
            Prefs.customDns = d
            list = Repo.servers()
            selected = 0
        }
        // se o cliente não escolhe o servidor, tenta um por um (ordem de prioridade do painel)
        val candidates = if (serverView.visibility == View.VISIBLE || list.size == 1) listOfNotNull(list.getOrNull(selected)) else list
        if (candidates.isEmpty()) {
            showError("Nenhum servidor configurado no painel.")
            return
        }
        setBusy(true)
        clearError()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(pass.windowToken, 0)
        launch {
            var lastError: Exception? = null
            for (s in candidates) {
                try {
                    val acc = Repo.login(s, u, p)
                    acc.expiresAt?.let {
                        Dialogs.toast(this@LoginActivity, "Bem-vindo! Assinatura válida até " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it)))
                    }
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
            setBusy(false)
            val e = lastError
            showError(
                when (e) {
                    is AuthException -> e.message ?: "Usuário ou senha inválidos."
                    null -> "Falha ao entrar."
                    else -> "Não foi possível conectar ao servidor. Verifique sua internet e tente novamente."
                }
            )
            if (e is AuthException) pass.requestFocus() else loginBtn.requestFocus()
        }
    }

    private fun setBusy(on: Boolean) {
        busy = on
        loading.visibility = if (on) View.VISIBLE else View.GONE
        loginBtn.text = if (on) "" else "ENTRAR"
        listOf(user, pass, dns, serverView).forEach { it.isEnabled = !on }
    }
}
