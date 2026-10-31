package com.novatv.app.data

import android.net.Uri
import android.os.Build
import com.novatv.app.BuildConfig
import com.novatv.app.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ServerDef(
    val id: Int,
    val name: String,
    val type: String,
    val urls: List<String>,
    val m3uUrl: String,
    val epgUrl: String,
    val userAgent: String,
    val output: String,
    val userSuffix: String,
    val streamBase: String,
    val streamTemplate: String,
)

/** Streaming da fileira da tela inicial (logo e palavras das categorias vêm do painel). */
data class StreamingDef(val name: String, val logo: String, val keywords: List<String>, val color: String, val textColor: String)

data class Banner(val title: String, val subtitle: String, val image: String, val action: String, val value: String)
data class Message(val id: Int, val title: String, val body: String, val popup: Boolean, val date: String)
data class Tab(val id: String, val label: String, val icon: String = "", val iconOnly: Boolean = true)

class PanelConfig(val json: JSONObject) {
    private val app = json.optJSONObject("app") ?: JSONObject()
    private val theme = json.optJSONObject("theme") ?: JSONObject()
    private val filters = json.optJSONObject("filters") ?: JSONObject()
    private val player = json.optJSONObject("player") ?: JSONObject()

    val appName: String = app.optString("name").ifBlank { "NovaTV" }
    // sem imagem no painel, usa as imagens embutidas no APK (res/drawable-nodpi)
    val logo: String = app.optString("logo").ifBlank { AppConfig.logo }
    val background: String = app.optString("background").ifBlank { AppConfig.background }
    val backgroundMode: String =
        if (app.optString("background").isBlank() && AppConfig.background.isNotEmpty()) "image"
        else app.optString("background_mode", "gradient")
    val splashText: String = app.optString("splash_text")
    val welcome: String = app.optString("welcome")
    val support: String = app.optString("support")
    val startTab: String = if (AppConfig.kidsMode) "kids" else app.optString("start_tab", "highlights")
    val allowServerSelect: Boolean = app.optBoolean("allow_server_select", true)
    val resellerName: String = app.optString("reseller")
    val askResellerCode: Boolean = app.optBoolean("ask_reseller_code", false)
    val homeMosaic: Boolean = app.optBoolean("home_mosaic", true)

    private val games = json.optJSONObject("games") ?: JSONObject()
    val gamesEnabled: Boolean = games.optBoolean("enabled", false)
    val gamesUrl: String = games.optString("url")

    private val tmdb = json.optJSONObject("tmdb") ?: JSONObject()
    val tmdbEnabled: Boolean = tmdb.optBoolean("enabled", true)
    val tmdbKey: String = tmdb.optString("api_key").ifBlank { AppConfig.tmdbKey }
    val tmdbToken: String = tmdb.optString("token").ifBlank { AppConfig.tmdbToken }
    val tmdbLanguage: String = tmdb.optString("language").ifBlank { AppConfig.tmdbLanguage }

    fun color(key: String, def: String): String = theme.optString(key).ifBlank { def }
    val surfaceOpacity: Int = theme.optInt("surface_opacity", 35)
    val radius: Int = theme.optInt("radius", 12)
    val scale: Int = theme.optInt("scale", 100)

    private val allTabs: List<Tab> = json.optJSONArray("tabs")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Tab(it.optString("id"), it.optString("label"), it.optString("icon"), it.optBoolean("icon_only", true)) } }
    } ?: listOf(Tab("highlights", "INÍCIO"), Tab("tv", "TV"), Tab("sports", "JOGOS"), Tab("movies", "FILMES"),
        Tab("series", "SÉRIES"), Tab("kids", "KIDS"), Tab("anime", "ANIME"), Tab("favorites", "FAVORITOS"))

    /** NovaKids: só as abas infantis (Kids primeiro, canais infantis e favoritos). */
    val tabs: List<Tab> = if (!AppConfig.kidsMode) allTabs else {
        val byId = allTabs.associateBy { it.id }
        listOf(
            byId["kids"] ?: Tab("kids", "🧸 KIDS"),
            (byId["tv"] ?: Tab("tv", "TV")).let { if (it.icon.isBlank()) it.copy(label = "📺 CANAIS") else it },
            (byId["favorites"] ?: Tab("favorites", "FAVORITOS")).let { if (it.icon.isBlank()) it.copy(label = "⭐ FAVORITOS") else it },
        )
    }

    val servers: List<ServerDef> = json.optJSONArray("servers")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ServerDef(
                id = o.optInt("id"),
                name = o.optString("name"),
                type = o.optString("type", "xtream"),
                urls = o.optJSONArray("urls").strings().map { it.trimEnd('/') },
                m3uUrl = o.optString("m3u_url"),
                epgUrl = o.optString("epg_url"),
                userAgent = o.optString("user_agent"),
                output = o.optString("output", "ts"),
                userSuffix = o.optString("user_suffix"),
                streamBase = o.optString("stream_base").trimEnd('/'),
                streamTemplate = o.optString("stream_template"),
            )
        }
    } ?: emptyList()

    // padrões embutidos: funcionam mesmo se o painel ainda não respondeu
    val kidsKeywords: List<String> = filters.optJSONArray("kids").strings().ifEmpty {
        listOf("kids", "infantil", "infantis", "criança", "desenho", "desenhos", "cartoon", "disney", "nick", "gloob", "cartoonito", "animação", "family", "família")
    }
    val animeKeywords: List<String> = filters.optJSONArray("anime").strings().ifEmpty { listOf("anime", "animes", "crunchyroll") }
    val sportsKeywords: List<String> = filters.optJSONArray("sports").strings().ifEmpty {
        listOf("esporte", "esportes", "sport", "sports", "futebol", "premiere", "espn", "sportv", "combate", "ufc", "nba", "f1")
    }
    val adultKeywords: List<String> = filters.optJSONArray("adult").strings().ifEmpty { listOf("adult", "adulto", "adultos", "xxx", "+18", "18+", "porn") }
    val hideAdult: Boolean = filters.optBoolean("hide_adult", true)
    val kidsLock: Boolean = filters.optBoolean("kids_lock", false)
    val panelPin: String = filters.optString("pin", "0000")

    val buffer: String = player.optString("buffer", "normal")
    val epg: Boolean = player.optBoolean("epg", true)

    val banners: List<Banner> = json.optJSONArray("banners")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Banner(it.optString("title"), it.optString("subtitle"), it.optString("image"), it.optString("action"), it.optString("value")) }
        }
    } ?: emptyList()

    private val streamingsObj = json.optJSONObject("streamings")
    /** null = painel antigo (o app usa a lista embutida). */
    val streamings: List<StreamingDef>? = streamingsObj?.optJSONArray("items")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                StreamingDef(it.optString("name"), it.optString("logo"), it.optJSONArray("keywords").strings(),
                    it.optString("color", "#FFFFFF"), it.optString("text_color", "#000000"))
            }
        }.filter { it.name.isNotBlank() }
    }
    val streamingsEnabled: Boolean = streamingsObj?.optBoolean("enabled", true) ?: true
    val streamingsTitle: String = streamingsObj?.optString("title").orEmpty().ifBlank { "Streamings" }

    val messages: List<Message> = json.optJSONArray("messages")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Message(it.optInt("id"), it.optString("title"), it.optString("body"), it.optBoolean("popup"), it.optString("date")) }
        }
    } ?: emptyList()

    val maintenance: Boolean = json.optJSONObject("maintenance")?.optBoolean("on") ?: false
    val maintenanceMessage: String = json.optJSONObject("maintenance")?.optString("message").orEmpty()
    val updateVersion: Int = json.optJSONObject("update")?.optInt("version_code") ?: 0
    val updateUrl: String = json.optJSONObject("update")?.optString("url").orEmpty()
    val forceUpdate: Boolean = json.optJSONObject("update")?.optBoolean("force") ?: false
    val blocked: Boolean = json.optJSONObject("device")?.optBoolean("blocked") ?: false
    val blockReason: String = json.optJSONObject("device")?.optString("reason").orEmpty()
    val blockMessage: String = json.optJSONObject("device")?.optString("message").orEmpty()

    val pin: String get() = Prefs.customPin.ifEmpty { panelPin }

    companion object {
        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

        @Volatile
        var current: PanelConfig = load()
            private set

        /** Último erro do painel (ex.: código de revenda inválido) para mostrar ao usuário. */
        @Volatile
        var lastError: String = ""
            private set
        @Volatile
        var lastErrorCode: String = ""
            private set

        /** Código efetivo: o do APK tem prioridade sobre o digitado. */
        val resellerCode: String get() = AppConfig.resellerCode.ifEmpty { Prefs.resellerCode }

        private fun load(): PanelConfig =
            runCatching { PanelConfig(JSONObject(Prefs.configJson)) }.getOrElse { PanelConfig(JSONObject()) }

        /**
         * Baixa a configuração do painel. Usa ETag: se nada mudou, o servidor responde 304
         * e mantemos o cache. Em caso de falha de rede, segue com o cache (modo offline).
         * Retorna true se veio do servidor (ou 304), false se ficou só no cache.
         */
        suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
            if (AppConfig.panelUrl.isBlank()) return@withContext false
            val url = Uri.parse(AppConfig.panelUrl + "/api/config.php").buildUpon()
                .appendQueryParameter("key", AppConfig.panelKey)
                .appendQueryParameter("reseller", resellerCode)
                .appendQueryParameter("device_id", Prefs.deviceId)
                .appendQueryParameter("model", (Build.MANUFACTURER + " " + Build.MODEL).trim().take(80))
                .appendQueryParameter("platform", if (Ui.isTv) "tv" else "mobile")
                .appendQueryParameter("android", Build.VERSION.RELEASE)
                .appendQueryParameter("version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                .appendQueryParameter("username", Prefs.username)
                .appendQueryParameter("server_id", Prefs.serverId.toString())
                .build().toString()
            try {
                val req = Net.request(url).newBuilder().apply {
                    if (Prefs.configEtag.isNotEmpty() && Prefs.configJson.isNotEmpty()) header("If-None-Match", Prefs.configEtag)
                }.build()
                lastError = ""
                lastErrorCode = ""
                Net.execute(Net.api, req).use { resp ->
                    if (resp.code == 304) return@withContext true
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    if (!obj.optBoolean("ok")) {
                        lastErrorCode = obj.optString("error")
                        lastError = obj.optString("message")
                        // código de revenda errado: não reaproveita configuração de outra revenda
                        if (lastErrorCode == "invalid_reseller" || lastErrorCode == "reseller_required") {
                            Prefs.configEtag = ""
                        }
                        return@withContext false
                    }
                    Prefs.configJson = body
                    Prefs.configEtag = resp.header("ETag").orEmpty()
                    current = PanelConfig(obj)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
