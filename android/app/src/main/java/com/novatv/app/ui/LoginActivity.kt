package com.novatv.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
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

class LoginActivity : BaseActivity() {

    private lateinit var serverView: TextView
    private lateinit var dns: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var error: TextView
    private lateinit var loginBtn: TextView
    private lateinit var loading: ProgressBar
    private var servers: List<ServerDef> = emptyList()
    private var selected = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        applyBackground(findViewById(R.id.bg))
        val cfg = PanelConfig.current

        val form = findViewById<View>(R.id.form)
        form.background = Ui.rounded(Ui.withAlpha(Ui.surfaceSolid, 170), Ui.dp(this, 22).toFloat(), Ui.withAlpha(Ui.text, 30), Ui.dp(this, 1))
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            form.layoutParams = form.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        }

        findViewById<TextView>(R.id.name).apply { text = cfg.appName; setTextColor(Ui.text) }
        findViewById<TextView>(R.id.welcome).apply {
            text = cfg.welcome.ifBlank { cfg.splashText }
            setTextColor(Ui.text2)
        }
        findViewById<TextView>(R.id.support).apply { text = cfg.support; setTextColor(Ui.text2) }
        findViewById<TextView>(R.id.subtitle).setTextColor(Ui.text2)
        val logo = findViewById<ImageView>(R.id.logo)
        if (cfg.logo.isNotEmpty()) logo.load(cfg.logo) { error(R.mipmap.ic_launcher) }

        serverView = findViewById(R.id.server)
        dns = findViewById(R.id.dns)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        error = findViewById(R.id.error)
        loginBtn = findViewById(R.id.login)
        loading = findViewById(R.id.loading)

        listOf(dns, user, pass).forEach {
            it.background = Ui.inputBg(this)
            it.setTextColor(Ui.text)
            it.setHintTextColor(Ui.withAlpha(Ui.text, 130))
        }
        serverView.background = Ui.inputBg(this)
        serverView.setTextColor(Ui.textColors(Ui.text, Ui.text))
        loginBtn.background = Ui.button(this, true)
        loginBtn.setTextColor(Ui.textColors(android.graphics.Color.WHITE, Ui.focusText))
        loading.indeterminateTintList = ColorStateList.valueOf(Ui.focusText)
        Ui.focusZoom(loginBtn, 1.04f)

        servers = Repo.servers()
        selected = servers.indexOfFirst { it.id == Prefs.serverId }.coerceAtLeast(0)
        when {
            cfg.servers.isEmpty() -> {
                // sem painel/servidores: cliente informa a DNS
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
                    }
                }
            }
        }

        user.setText(Prefs.username)
        pass.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                doLogin(); true
            } else false
        }
        loginBtn.setOnClickListener { doLogin() }
        intent.getStringExtra("error")?.let { showError(it) }

        (if (dns.visibility == View.VISIBLE && dns.text.isEmpty()) dns else if (user.text.isEmpty()) user else pass).requestFocus()
    }

    private fun updateServerLabel() {
        serverView.text = "Servidor:  ${servers.getOrNull(selected)?.name.orEmpty()}  ▾"
    }

    private fun showError(msg: String) {
        error.text = msg
        error.visibility = View.VISIBLE
        error.animate().translationX(12f).setDuration(60).withEndAction {
            error.animate().translationX(-12f).setDuration(60).withEndAction {
                error.animate().translationX(0f).setDuration(60).start()
            }.start()
        }.start()
    }

    private fun doLogin() {
        if (busy) return
        val u = user.text.toString().trim()
        val p = pass.text.toString().trim()
        if (u.isEmpty() || p.isEmpty()) {
            showError("Informe usuário e senha.")
            return
        }
        var list = servers
        if (dns.visibility == View.VISIBLE) {
            var d = dns.text.toString().trim().trimEnd('/')
            if (d.isEmpty()) {
                showError("Informe a DNS do servidor.")
                return
            }
            if (!d.startsWith("http://") && !d.startsWith("https://")) d = "http://$d"
            Prefs.customDns = d
            list = Repo.servers()
            selected = 0
        }
        // login automático: se o cliente não escolhe, testa servidor por servidor
        val candidates = if (serverView.visibility == View.VISIBLE || list.size == 1) listOfNotNull(list.getOrNull(selected)) else list
        if (candidates.isEmpty()) {
            showError("Nenhum servidor configurado no painel.")
            return
        }
        setBusy(true)
        error.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(pass.windowToken, 0)
        launch {
            var lastError: Exception? = null
            for (s in candidates) {
                try {
                    val acc = Repo.login(s, u, p)
                    acc.expiresAt?.let {
                        Dialogs.toast(this@LoginActivity, "Assinatura válida até " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it)))
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
        }
    }

    private fun setBusy(on: Boolean) {
        busy = on
        loading.visibility = if (on) View.VISIBLE else View.GONE
        loginBtn.text = if (on) "" else "ENTRAR"
    }
}
