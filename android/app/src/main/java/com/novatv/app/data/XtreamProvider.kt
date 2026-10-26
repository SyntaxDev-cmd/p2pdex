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
    /** Programação completa (dia) do canal. */
    suspend fun fullEpg(m: Media): List<EpgItem>
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

    /** DNS do painel limpas (aceita colado com /player_api.php, barra no fim ou espaços). */
    private val urls: List<String> = server.urls.map { cleanBase(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * Usuário enviado à API. Com "API Email" (sufixo @dominio) o sufixo é somado ao que o
     * cliente digitou, a não ser que ele já tenha digitado o e-mail completo.
     */
    @Volatile
    private var apiUser: String = loginUsers().first()
    override val username: String get() = user

    /** Alguns servidores P2P só aceitam a API por POST: detectado no login. */
    @Volatile
    private var usePost = false

    @Volatile
    private var base: String = cleanBase(savedBase).takeIf { it in urls } ?: urls.firstOrNull().orEmpty()
    override val activeBase: String get() = base

    private fun loginUsers(): List<String> {
        val u = user.trim()
        val suffix = server.userSuffix.trim()
        if (suffix.isEmpty() || u.contains('@')) return listOf(u)
        // tenta com o sufixo e, se recusar, como o cliente digitou
        return listOf(u + suffix, u)
    }

    private fun apiUrl(b: String, action: String, extra: Map<String, String>, u: String = apiUser): String {
        val ub = Uri.parse("$b/player_api.php").buildUpon()
            .appendQueryParameter("username", u)
            .appendQueryParameter("password", pass)
        if (action.isNotEmpty()) ub.appendQueryParameter("action", action)
        extra.forEach { (k, v) -> ub.appendQueryParameter(k, v) }
        return ub.build().toString()
    }

    private fun apiRequest(b: String, action: String, extra: Map<String, String>, u: String = apiUser, post: Boolean = usePost): okhttp3.Request {
        if (!post) return Net.request(apiUrl(b, action, extra, u), server.userAgent)
        val form = okhttp3.FormBody.Builder()
            .add("username", u)
            .add("password", pass)
            .apply {
                if (action.isNotEmpty()) add("action", action)
                extra.forEach { (k, v) -> add(k, v) }
            }.build()
        return Net.request("$b/player_api.php", server.userAgent).newBuilder().post(form).build()
    }

    /** Chama a API na DNS ativa; se falhar, tenta as outras e memoriza a que funcionou. */
    private suspend fun <T> call(action: String, extra: Map<String, String> = emptyMap(), parse: (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val order = (listOf(base) + urls).filter { it.isNotEmpty() }.distinct()
            if (order.isEmpty()) throw IOException("Nenhuma DNS configurada")
            var last: Exception? = null
            for (b in order) {
                try {
                    val result = Net.execute(Net.api, apiRequest(b, action, extra)).use { resp ->
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

    /** A DNS respondeu, mas não com a API Xtream (página HTML, 404...). */
    private class NotXtreamException(msg: String) : IOException(msg)

    private fun parseLogin(text: String): Account {
        val o = try {
            JSONObject(text.trim().removePrefix("﻿"))
        } catch (e: org.json.JSONException) {
            throw NotXtreamException("o endereço respondeu, mas não é uma API Xtream/P2P compatível")
        }
        val ui = o.optJSONObject("user_info") ?: run {
            // alguns P2P respondem só {"auth":0} ou {"error": "..."} quando o login é recusado
            if (o.has("auth") || o.has("error") || o.has("message")) throw AuthException("Usuário ou senha inválidos")
            throw NotXtreamException("o endereço respondeu, mas não é uma API Xtream/P2P compatível")
        }
        val status = ui.optString("status").trim()
        val auth = ui.opt("auth")
        val authOk = when (auth) {
            null, JSONObject.NULL -> status.equals("active", true) || ui.optString("username").isNotEmpty()
            is Boolean -> auth
            is Number -> auth.toInt() == 1
            else -> auth.toString().trim().let { it == "1" || it.equals("true", true) }
        }
        if (!authOk) throw AuthException("Usuário ou senha inválidos")
        if (status.equals("Expired", true)) throw AuthException("Assinatura expirada. Renove para continuar.")
        if (status.equals("Banned", true) || status.equals("Disabled", true)) throw AuthException("Conta desativada. Fale com o suporte.")
        val exp = ui.optString("exp_date").toDoubleOrNull()?.toLong()?.takeIf { it > 0 }?.times(1000)
        return Account(status.ifEmpty { "Active" }, exp, ui.optString("max_connections"), ui.optString("active_cons"), ui.optString("is_trial") == "1")
    }

    private suspend fun fetchLogin(b: String, u: String, post: Boolean): Account {
        val text = Net.execute(Net.api, apiRequest(b, "", emptyMap(), u, post)).use { resp ->
            if (resp.code in 400..599) {
                // Cloudflare: "error code: 1014" = domínio do servidor mal configurado/fora do ar
                val body = runCatching { resp.peekBody(2048).string() }.getOrDefault("")
                Regex("error code: (\\d{3,4})").find(body)?.let {
                    throw IOException("o domínio ${Uri.parse(b).host} está fora do ar na Cloudflare (erro ${it.groupValues[1]}). Peça ao fornecedor do P2P o endereço correto da API")
                }
            }
            when {
                resp.code == 401 -> throw AuthException("Usuário ou senha inválidos")
                resp.code == 404 || resp.code == 405 -> throw NotXtreamException("a API não existe nesse endereço (HTTP ${resp.code})")
                resp.code == 403 -> throw IOException("o servidor bloqueou o acesso (HTTP 403)")
                !resp.isSuccessful -> throw IOException("o servidor respondeu com erro (HTTP ${resp.code})")
            }
            resp.body?.string().orEmpty()
        }
        return parseLogin(text)
    }

    /** Tenta GET e, se a DNS não entender, POST. Retorna (conta, usou POST). */
    private suspend fun attempt(b: String, u: String): Pair<Account, Boolean> =
        try {
            fetchLogin(b, u, false) to false
        } catch (e: NotXtreamException) {
            fetchLogin(b, u, true) to true
        }

    /** Todas as DNS ao mesmo tempo: vence a primeira que autenticar. */
    private suspend fun race(u: String): Triple<String, Account, Boolean> = withContext(Dispatchers.IO) {
        if (urls.size == 1) {
            val (acc, post) = attempt(urls[0], u)
            return@withContext Triple(urls[0], acc, post)
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val winner = CompletableDeferred<Triple<String, Account, Boolean>>()
        val errors = mutableListOf<Exception>()
        val jobs: List<Job> = urls.map { b ->
            scope.launch {
                try {
                    val (acc, post) = attempt(b, u)
                    winner.complete(Triple(b, acc, post))
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                        if (errors.size == urls.size) winner.completeExceptionally(bestError(errors))
                    }
                }
            }
        }
        try {
            winner.await()
        } finally {
            jobs.forEach { it.cancel() }
            scope.cancel()
        }
    }

    override suspend fun login(): Account {
        if (urls.isEmpty()) throw IOException("Nenhuma DNS configurada")
        var lastAuth: AuthException? = null
        for (u in loginUsers()) {
            try {
                val (b, acc, post) = race(u)
                base = b
                apiUser = u
                usePost = post
                Prefs.activeBase = b
                return acc
            } catch (e: AuthException) {
                // senha errada com o sufixo: tenta o usuário como foi digitado
                if (lastAuth == null || e.message != "Usuário ou senha inválidos") lastAuth = e
            }
        }
        throw lastAuth ?: AuthException("Usuário ou senha inválidos")
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
                tmdbId = info.optString("tmdb").ifBlank { info.optString("tmdb_id") },
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
            tmdbId = info.optString("tmdb_id").ifBlank { info.optString("tmdb") },
        )
    }

    /** EPG XMLTV próprio definido no painel (opcional). */
    private val xmltv: XmlTvEpg? by lazy {
        server.epgUrl.takeIf { it.isNotBlank() }?.let {
            XmlTvEpg.of(it.replace("{username}", Uri.encode(apiUser)).replace("{password}", Uri.encode(pass)), server.userAgent)
        }
    }

    private fun parseListings(input: InputStream): List<EpgItem> {
        val o = JSONObject(input.bufferedReader().readText())
        val arr = o.optJSONArray("epg_listings") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = e.optString("start_timestamp").toLongOrNull()?.times(1000) ?: parseDate(e.optString("start"))
            val end = e.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: parseDate(e.optString("end").ifEmpty { e.optString("stop") })
            if (start <= 0 || end <= start) null else EpgItem(b64(e.optString("title")), b64(e.optString("description")), start, end)
        }.distinctBy { it.start }.sortedBy { it.start }
    }

    private suspend fun fromXmltv(m: Media): List<EpgItem> {
        val x = xmltv ?: return emptyList()
        return try {
            x.ensure()
            x.forChannel(m.epgId, m.name)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun shortEpg(m: Media): List<EpgItem> {
        val now = System.currentTimeMillis()
        fromXmltv(m).filter { it.end > now }.takeIf { it.isNotEmpty() }?.let { return it.take(4) }
        return try {
            call("get_short_epg", mapOf("stream_id" to m.id, "limit" to "6")) { parseListings(it) }.filter { it.end > now }
        } catch (e: AuthException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    override suspend fun fullEpg(m: Media): List<EpgItem> {
        val since = System.currentTimeMillis() - 12 * 3600_000L
        fromXmltv(m).filter { it.end > since }.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            call("get_simple_data_table", mapOf("stream_id" to m.id)) { parseListings(it) }.filter { it.end > since }
                .ifEmpty { shortEpg(m) }
        } catch (e: AuthException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: org.json.JSONException) {
            emptyList()
        }
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
        if (server.streamBase.isNotEmpty() || urls.size < 2) return false
        val i = urls.indexOf(base)
        base = urls[(i + 1).mod(urls.size)]
        Prefs.activeBase = base
        return true
    }

    companion object {
        /** "http://dns.com:80/player_api.php?x" -> "http://dns.com:80" */
        fun cleanBase(raw: String): String {
            var u = raw.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.contains("://")) u = "http://$u"
            for (tail in listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php")) {
                if (u.endsWith(tail, true)) u = u.dropLast(tail.length)
            }
            return u.trimEnd('/')
        }

        /** Entre vários erros de login, o mais útil para mostrar ao cliente. */
        fun bestError(errors: List<Exception>): Exception {
            val auth = errors.filterIsInstance<AuthException>()
            return auth.firstOrNull { it.message != "Usuário ou senha inválidos" } ?: auth.firstOrNull() ?: errors.last()
        }

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
