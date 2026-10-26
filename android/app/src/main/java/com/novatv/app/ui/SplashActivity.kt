package com.novatv.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import coil.load
import com.novatv.app.BuildConfig
import com.novatv.app.R
import com.novatv.app.data.AuthException
import com.novatv.app.data.Net
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SplashActivity : BaseActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        status = findViewById(R.id.status)
        paint()
        findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME}"
        Ui.fadeIn(findViewById(R.id.center), 450)
        start()
    }

    private fun paint() {
        val cfg = PanelConfig.current
        applyBackground(findViewById(R.id.bg))
        val name = findViewById<TextView>(R.id.name)
        name.text = cfg.appName
        // com logo cadastrada: só a logo, maior
        name.visibility = if (cfg.logo.isNotEmpty()) View.GONE else View.VISIBLE
        name.setTextColor(Ui.text)
        status.setTextColor(Ui.text2)
        status.text = cfg.splashText.ifBlank { "Carregando..." }
        findViewById<ProgressBar>(R.id.progress).indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.accent)
        val logo = findViewById<ImageView>(R.id.logo)
        if (cfg.logo.isNotEmpty()) {
            logo.layoutParams = logo.layoutParams.apply { height = Ui.dp(this@SplashActivity, 140) }
            logo.load(cfg.logo) { error(R.mipmap.ic_launcher) }
        }
    }

    private fun start() {
        launch {
            val online = PanelConfig.refresh()
            // relatório do último fechamento inesperado (se houver) vai para o painel
            launch { com.novatv.app.data.CrashReporter.sendPending(applicationContext) }
            Ui.apply(PanelConfig.current)
            paint()
            val cfg = PanelConfig.current
            if (!online && com.novatv.app.data.AppConfig.panelUrl.isNotBlank() && Prefs.configJson.isEmpty()) {
                Dialogs.show(this@SplashActivity, "Sem conexão", "Não foi possível falar com o servidor. Verifique sua internet.", listOf(
                    Dialogs.Btn("Sair") { finish() },
                    Dialogs.Btn("Tentar de novo", true) { start() },
                ), cancelable = false)
                return@launch
            }
            val err = PanelConfig.lastErrorCode
            if (err == "invalid_reseller" || err == "reseller_required") {
                if (com.novatv.app.data.AppConfig.resellerCode.isNotEmpty()) {
                    Dialogs.show(this@SplashActivity, "Revenda indisponível", PanelConfig.lastError, listOf(
                        Dialogs.Btn("Sair") { finish() },
                        Dialogs.Btn("Tentar de novo", true) { start() },
                    ), cancelable = false)
                } else {
                    Prefs.resellerCode = ""
                    startActivity(Intent(this@SplashActivity, LoginActivity::class.java)
                        .putExtra("error", PanelConfig.lastError).putExtra("ask_code", true))
                    finish()
                }
                return@launch
            }
            if (cfg.blocked) {
                val title = if (cfg.blockReason == "limit") "Limite de aparelhos" else "Dispositivo bloqueado"
                val msg = cfg.blockMessage.ifBlank { "Este aparelho foi bloqueado." } + "\n\n" + cfg.support
                Dialogs.show(this@SplashActivity, title, msg, listOf(
                    Dialogs.Btn("Sair") { finish() },
                    Dialogs.Btn("Tentar de novo", true) { start() },
                ), cancelable = false)
                return@launch
            }
            if (cfg.maintenance) {
                Dialogs.show(this@SplashActivity, "Manutenção", cfg.maintenanceMessage, listOf(
                    Dialogs.Btn("Sair") { finish() },
                    Dialogs.Btn("Tentar de novo", true) { start() },
                ), cancelable = false)
                return@launch
            }
            if (cfg.updateVersion > BuildConfig.VERSION_CODE && cfg.updateUrl.isNotBlank()) {
                val buttons = mutableListOf<Dialogs.Btn>()
                if (!cfg.forceUpdate) buttons.add(Dialogs.Btn("Depois") { proceed() })
                buttons.add(Dialogs.Btn("Atualizar", true) { Updater.run(this@SplashActivity, cfg.updateUrl) { if (!cfg.forceUpdate) proceed() } })
                Dialogs.show(this@SplashActivity, "Nova versão disponível", "Atualize para ter as últimas melhorias e correções.", buttons, cancelable = false)
                return@launch
            }
            proceed()
        }
    }

    private fun proceed() {
        if (!Prefs.loggedIn) {
            goLogin(null)
            return
        }
        status.text = "Conectando ao servidor..."
        launch {
            try {
                if (Repo.restore() == null) {
                    goLogin(null)
                    return@launch
                }
                ProfilesActivity.route(this@SplashActivity)
            } catch (e: AuthException) {
                goLogin(e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Dialogs.show(this@SplashActivity, "Falha de conexão", "Não foi possível conectar ao servidor.\n${e.message.orEmpty()}", listOf(
                    Dialogs.Btn("Trocar conta") { Repo.logout(); goLogin(null) },
                    Dialogs.Btn("Tentar de novo", true) { proceed() },
                ), cancelable = false)
            }
        }
    }

    private fun goLogin(msg: String?) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra("error", msg))
        finish()
    }
}

/** Baixa e instala a nova versão do APK informada no painel. */
object Updater {
    fun run(act: Activity, url: String, onFail: () -> Unit) {
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.primary)
        }
        val dialog = Dialogs.show(act, "Baixando atualização...", null, buttons = emptyList(), content = bar, cancelable = false)
        val scope = act as? kotlinx.coroutines.CoroutineScope ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    // Android < 7 não usa FileProvider: o instalador precisa ler de armazenamento externo
                    val dir = (if (Build.VERSION.SDK_INT >= 24) File(act.cacheDir, "updates") else (act.externalCacheDir ?: act.cacheDir))
                        .apply { mkdirs() }
                    val out = File(dir, "update.apk")
                    Net.execute(Net.media, Net.request(url)).use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        val body = resp.body!!
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            out.outputStream().use { os ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var done = 0L
                                while (input.read(buf).also { read = it } >= 0) {
                                    os.write(buf, 0, read)
                                    done += read
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        bar.post { bar.isIndeterminate = false; bar.progress = pct }
                                    }
                                }
                            }
                        }
                    }
                    out
                }
                dialog?.dismiss()
                val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(act, act.packageName + ".files", file)
                else android.net.Uri.fromFile(file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                act.startActivity(intent)
            } catch (e: Exception) {
                dialog?.dismiss()
                Dialogs.toast(act, "Falha ao baixar atualização")
                onFail()
            }
        }
    }
}
