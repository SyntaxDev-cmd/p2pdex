package com.novatv.app.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

interface Provider {
    val server: ServerDef
    val username: String
    val activeBase: String
    suspend fun login(): Account
    suspend fun categories(kind: Kind): List<Category>
    suspend fun items(kind: Kind, categoryId: String): List<Media>
    suspend fun all(kind: Kind): List<Media>
    suspend fun details(m: Media): Details
    suspend fun shortEpg(m: Media): List<EpgItem>
    fun streamUrl(m: Media): String
    fun episodeUrl(e: Episode): String
    /** Troca para a próxima DNS depois de falha no player. true se trocou. */
    fun failover(): Boolean
}

/**
 * Cliente da API Xtream Codes (player_api.php), usado tanto para DNS IPTV quanto para P2P
 * com API compatível. Recursos:
 *  - várias DNS por servidor, login em paralelo (vence a primeira que autenticar)
 *  - failover automático a cada chamada e no player
 *  - sufixo de usuário (API Email) e domínio de streams separado (Domínio LIB)
 *  - modelo de URL de stream configurável no painel
 */
class XtreamProvider(
    override val server: ServerDef,
    private val user: String,
    private val pass: String,
    savedBase: String = "",
) : Provider {

    private val apiUser = user + server.userSuffix
    override val username: String get() = user

    @Volatile
    private var base: String = savedBase.takeIf { it in server.urls } ?: server.urls.firstOrNull().orEmpty()
    override val activeBase: String get() = base

    private fun apiUrl(b: String, action: String, extra: Map<String, String>): String {
        val ub = Uri.parse("$b/player_api.php").buildUpon()
            .appendQueryParameter("username", apiUser)
            .appendQueryParameter("password", pass)
        if (action.isNotEmpty()) ub.appendQueryParameter("action", action)
        extra.forEach { (k, v) -> ub.appendQueryParameter(k, v) }
        return ub.build().toString()
    }

    /** Chama a API na DNS ativa; se falhar, tenta as outras e memoriza a que funcionou. */
    private suspend fun <T> call(action: String, extra: Map<String, String> = emptyMap(), parse: (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val order = (listOf(base) + server.urls).filter { it.isNotEmpty() }.distinct()
            if (order.isEmpty()) throw IOException("Nenhuma DNS configurada")
            var last: Exception? = null
            for (b in order) {
                try {
                    val result = Net.execute(Net.api, Net.request(apiUrl(b, action, extra), server.userAgent)).use { resp ->
                        if (resp.code == 401 || resp.code == 403) throw AuthException("Acesso negado pelo servidor (${resp.code})")
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("Resposta vazia")
                        parse(body.byteStream())
                    }
                    if (base != b) {
                        base = b
                        Prefs.activeBase = b
                    }
                    return@withContext result
                } catch (e: AuthException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("Servidor indisponível")
        }

    private fun parseLogin(input: InputStream): Account {
        val o = JSONObject(input.bufferedReader().readText())
        val ui = o.optJSONObject("user_info") ?: throw IOException("Resposta não é Xtream")
        if (ui.optString("auth") != "1" && ui.optInt("auth", 0) != 1) throw AuthException("Usuário ou senha inválidos")
        val status = ui.optString("status")
        if (status.equals("Expired", true)) throw AuthException("Assinatura expirada. Renove para continuar.")
        if (status.equals("Banned", true) || status.equals("Disabled", true)) throw AuthException("Conta desativada. Fale com o suporte.")
        val exp = ui.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        return Account(status.ifEmpty { "Active" }, exp, ui.optString("max_connections"), ui.optString("active_cons"), ui.optString("is_trial") == "1")
    }

    override suspend fun login(): Account {
        val urls = server.urls.filter { it.isNotEmpty() }
        if (urls.isEmpty()) throw IOException("Nenhuma DNS configurada")
        if (urls.size == 1) {
            return call("", parse = ::parseLogin).also { Prefs.activeBase = base }
        }
        // corrida: todas as DNS ao mesmo tempo, vence a primeira que autenticar
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val winner = CompletableDeferred<Pair<String, Account>>()
        val errors = mutableListOf<Exception>()
        val jobs: List<Job> = urls.map { b ->
            scope.launch {
                try {
                    val acc = Net.execute(Net.api, Net.request(apiUrl(b, "", emptyMap()), server.userAgent)).use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        parseLogin(resp.body!!.byteStream())
                    }
                    winner.complete(b to acc)
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                        if (errors.size == urls.size) {
                            winner.completeExceptionally(errors.firstOrNull { it is AuthException } ?: errors.last())
                        }
                    }
                }
            }
        }
        try {
            val (b, acc) = winner.await()
            base = b
            Prefs.activeBase = b
            return acc
        } finally {
            jobs.forEach { it.cancel() }
            scope.cancel()
        }
    }

    private fun kindAction(kind: Kind, cats: Boolean) = when (kind) {
        Kind.LIVE -> if (cats) "get_live_categories" else "get_live_streams"
        Kind.MOVIE -> if (cats) "get_vod_categories" else "get_vod_streams"
        Kind.SERIES -> if (cats) "get_series_categories" else "get_series"
    }

    override suspend fun categories(kind: Kind): List<Category> =
        call(kindAction(kind, true)) { input ->
            Json.readArray(input) { m ->
                val id = m["category_id"] ?: return@readArray null
                Category(id, m["category_name"].orEmpty().trim(), kind)
            }
        }

    private fun mapItem(kind: Kind, m: Map<String, String>): Media? = when (kind) {
        Kind.LIVE -> m["stream_id"]?.let {
            Media(it, m["name"].orEmpty().trim(), kind, m["stream_icon"].orEmpty(), m["category_id"].orEmpty(),
                epgId = m["epg_channel_id"].orEmpty(), num = m["num"]?.toDoubleOrNull()?.toInt() ?: 0,
                added = m["added"]?.toLongOrNull() ?: 0)
        }
        Kind.MOVIE -> m["stream_id"]?.let {
            Media(it, m["name"].orEmpty().trim(), kind, m["stream_icon"].orEmpty(), m["category_id"].orEmpty(),
                ext = m["container_extension"].orEmpty(), rating = cleanRating(m["rating"]),
                year = m["year"].orEmpty().ifEmpty { yearOf(m["releasedate"]) }, added = m["added"]?.toLongOrNull() ?: 0)
        }
        Kind.SERIES -> m["series_id"]?.let {
            Media(it, m["name"].orEmpty().trim(), kind, m["cover"].orEmpty(), m["category_id"].orEmpty(),
                rating = cleanRating(m["rating"]), year = m["year"].orEmpty().ifEmpty { yearOf(m["releaseDate"] ?: m["release_date"]) },
                added = m["last_modified"]?.toLongOrNull() ?: 0)
        }
    }

    override suspend fun items(kind: Kind, categoryId: String): List<Media> =
        call(kindAction(kind, false), mapOf("category_id" to categoryId)) { input ->
            Json.readArray(input) { mapItem(kind, it) }
        }

    override suspend fun all(kind: Kind): List<Media> =
        call(kindAction(kind, false)) { input -> Json.readArray(input) { mapItem(kind, it) } }

    override suspend fun details(m: Media): Details {
        if (m.kind == Kind.SERIES) {
            val o = call("get_series_info", mapOf("series_id" to m.id)) { JSONObject(it.bufferedReader().readText()) }
            val info = o.optJSONObject("info") ?: JSONObject()
            val seasons = sortedMapOf<Int, List<Episode>>()
            val eps = o.opt("episodes")
            fun addEpisodes(arr: JSONArray?, seasonHint: Int) {
                if (arr == null) return
                val list = (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val ei = e.optJSONObject("info") ?: JSONObject()
                    val season = e.optString("season").toIntOrNull() ?: seasonHint
                    Episode(
                        id = e.optString("id"),
                        title = e.optString("title").ifBlank { "Episódio ${e.optString("episode_num")}" },
                        season = season,
                        number = e.optString("episode_num").toIntOrNull() ?: (i + 1),
                        ext = e.optString("container_extension", "mp4"),
                        image = ei.optString("movie_image").ifBlank { ei.optString("cover_big") },
                        plot = ei.optString("plot"),
                        duration = ei.optString("duration"),
                    )
                }.sortedBy { it.number }
                if (list.isNotEmpty()) seasons[list.first().season] = list
            }
            when (eps) {
                is JSONObject -> eps.keys().forEach { k -> addEpisodes(eps.optJSONArray(k), k.toIntOrNull() ?: 1) }
                is JSONArray -> for (i in 0 until eps.length()) addEpisodes(eps.optJSONArray(i), i + 1)
            }
            return Details(
                name = info.optString("name").ifBlank { m.name },
                cover = info.optString("cover").ifBlank { m.icon },
                backdrop = firstOf(info.opt("backdrop_path")),
                plot = info.optString("plot"),
                genre = info.optString("genre"),
                cast = info.optString("cast"),
                director = info.optString("director"),
                rating = cleanRating(info.optString("rating")).ifEmpty { m.rating },
                year = yearOf(info.optString("releaseDate")).ifEmpty { m.year },
                duration = info.optString("episode_run_time").let { if (it.isNotBlank() && it != "0") "$it min/ep" else "" },
                seasons = seasons,
            )
        }
        val o = call("get_vod_info", mapOf("vod_id" to m.id)) { JSONObject(it.bufferedReader().readText()) }
        val info = o.optJSONObject("info") ?: JSONObject()
        val data = o.optJSONObject("movie_data") ?: JSONObject()
        return Details(
            name = info.optString("name").ifBlank { data.optString("name").ifBlank { m.name } },
            cover = info.optString("movie_image").ifBlank { m.icon },
            backdrop = firstOf(info.opt("backdrop_path")),
            plot = info.optString("plot").ifBlank { info.optString("description") },
            genre = info.optString("genre"),
            cast = info.optString("cast").ifBlank { info.optString("actors") },
            director = info.optString("director"),
            rating = cleanRating(info.optString("rating")).ifEmpty { m.rating },
            year = yearOf(info.optString("releasedate")).ifEmpty { m.year },
            duration = info.optString("duration").removePrefix("00:"),
            ext = data.optString("container_extension").ifBlank { m.ext },
        )
    }

    override suspend fun shortEpg(m: Media): List<EpgItem> = try {
        call("get_short_epg", mapOf("stream_id" to m.id, "limit" to "4")) { input ->
            val o = JSONObject(input.bufferedReader().readText())
            val arr = o.optJSONArray("epg_listings") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val start = e.optString("start_timestamp").toLongOrNull()?.times(1000) ?: parseDate(e.optString("start"))
                val end = e.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: parseDate(e.optString("end"))
                EpgItem(b64(e.optString("title")), b64(e.optString("description")), start, end)
            }.filter { it.end > System.currentTimeMillis() }.sortedBy { it.start }
        }
    } catch (e: AuthException) {
        emptyList()
    } catch (e: IOException) {
        emptyList()
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    private fun enc(s: String) = Uri.encode(s, "@._-")

    private fun build(kind: String, id: String, ext: String): String {
        val b = server.streamBase.ifEmpty { base }
        val tpl = server.streamTemplate.ifBlank { "{base}/{kind}/{user}/{pass}/{id}.{ext}" }
        return tpl.replace("{base}", b).replace("{kind}", kind).replace("{user}", enc(apiUser))
            .replace("{pass}", enc(pass)).replace("{id}", id).replace("{ext}", ext)
    }

    override fun streamUrl(m: Media): String {
        if (m.directUrl.isNotEmpty()) return m.directUrl
        return when (m.kind) {
            Kind.LIVE -> build("live", m.id, Prefs.liveFormat.ifEmpty { server.output.ifBlank { "ts" } })
            Kind.MOVIE -> build("movie", m.id, m.ext.ifBlank { "mp4" })
            Kind.SERIES -> build("series", m.id, m.ext.ifBlank { "mp4" })
        }
    }

    override fun episodeUrl(e: Episode): String =
        e.directUrl.ifEmpty { build("series", e.id, e.ext.ifBlank { "mp4" }) }

    override fun failover(): Boolean {
        if (server.streamBase.isNotEmpty() || server.urls.size < 2) return false
        val i = server.urls.indexOf(base)
        base = server.urls[(i + 1).mod(server.urls.size)]
        Prefs.activeBase = base
        return true
    }

    companion object {
        fun b64(s: String): String {
            if (s.isEmpty()) return ""
            return try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (e: IllegalArgumentException) {
                s
            }
        }

        private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
        }

        fun parseDate(s: String): Long = runCatching { dateFmt.get()!!.parse(s)?.time ?: 0L }.getOrDefault(0L)

        fun cleanRating(r: String?): String {
            val d = r?.replace(',', '.')?.toDoubleOrNull() ?: return ""
            if (d <= 0) return ""
            val v = if (d > 10) d / 10 else d
            return String.format(Locale.US, "%.1f", v)
        }

        fun yearOf(s: String?): String = s?.let { Regex("(19|20)\\d{2}").find(it)?.value }.orEmpty()

        private fun firstOf(v: Any?): String = when (v) {
            is JSONArray -> if (v.length() > 0) v.optString(0) else ""
            is String -> v
            else -> ""
        }
    }
}
