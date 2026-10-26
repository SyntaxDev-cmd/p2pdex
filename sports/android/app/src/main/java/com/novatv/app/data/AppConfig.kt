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
    /** Código da revenda (APK personalizado por revenda). */
    var resellerCode: String = ""
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

    /** URI da imagem embutida, ou "" se ela não existir ou for o marcador vazio (1x1). */
    private fun resImage(ctx: Context, name: String): String {
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id == 0) return ""
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, id, opts) }
        if (opts.outWidth in 0..8 || opts.outHeight in 0..8) return ""
        return "android.resource://${ctx.packageName}/$id"
    }

    fun load(ctx: Context) {
        runCatching {
            val txt = ctx.assets.open("config.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            o.optString("panel_url").trim().trimEnd('/').takeIf { it.startsWith("http") }?.let { panelUrl = it }
            o.optString("panel_key").trim().takeIf { it.isNotEmpty() }?.let { panelKey = it }
            resellerCode = o.optString("reseller_code").trim().uppercase()
            tmdbKey = o.optString("tmdb_api_key").trim()
            tmdbToken = o.optString("tmdb_read_token").trim()
            o.optString("tmdb_language").trim().takeIf { it.isNotEmpty() }?.let { tmdbLanguage = it }
        }
        // imagens editáveis no APK (res/drawable-nodpi): aparecem na aba "Imagens" do APK Editor
        logo = resImage(ctx, "app_logo")
        background = resImage(ctx, "app_background")
    }
}
