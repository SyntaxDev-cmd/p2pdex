package com.novatv.app.data

import android.content.Context
import com.novatv.app.BuildConfig
import org.json.JSONObject

/**
 * Configuração embutida no APK em assets/config.json — pode ser editada com MT Manager /
 * APK Editor sem recompilar (link do painel, chave, TMDB). Imagens em assets/images/.
 */
object AppConfig {
    var panelUrl: String = BuildConfig.PANEL_URL
        private set
    var panelKey: String = BuildConfig.PANEL_KEY
        private set
    var tmdbKey: String = ""
        private set
    var tmdbToken: String = ""
        private set
    var tmdbLanguage: String = "pt-BR"
        private set

    /** Logo/fundo embutidos (URI que o Coil entende) ou "". */
    var logo: String = ""
        private set
    var background: String = ""
        private set

    fun load(ctx: Context) {
        runCatching {
            val txt = ctx.assets.open("config.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            o.optString("panel_url").trim().trimEnd('/').takeIf { it.startsWith("http") }?.let { panelUrl = it }
            o.optString("panel_key").trim().takeIf { it.isNotEmpty() }?.let { panelKey = it }
            tmdbKey = o.optString("tmdb_api_key").trim()
            tmdbToken = o.optString("tmdb_read_token").trim()
            o.optString("tmdb_language").trim().takeIf { it.isNotEmpty() }?.let { tmdbLanguage = it }
        }
        val images = runCatching { ctx.assets.list("images")?.toSet() }.getOrNull().orEmpty()
        logo = images.firstOrNull { it.startsWith("logo.") }?.let { "file:///android_asset/images/$it" }.orEmpty()
        background = images.firstOrNull { it.startsWith("background.") }?.let { "file:///android_asset/images/$it" }.orEmpty()
    }
}
