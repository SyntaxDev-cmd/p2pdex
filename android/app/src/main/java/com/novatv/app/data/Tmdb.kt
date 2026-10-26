package com.novatv.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB (The Movie Database): completa filmes e séries com sinopse em pt-BR, imagens em alta,
 * elenco, trailer e classificação indicativa. Chave/token vêm do painel (ou do assets/config.json).
 * Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.
 */
object Tmdb {
    data class Person(val name: String, val role: String, val photo: String)

    data class Info(
        val id: Int,
        val tv: Boolean,
        val title: String,
        val overview: String,
        val poster: String,
        val backdrop: String,
        val rating: String,
        val year: String,
        val runtime: String,
        val genres: String,
        val cast: List<Person>,
        val trailer: String,
        val certification: String,
        val tagline: String,
    )

    data class Trend(val id: Int, val tv: Boolean, val title: String, val poster: String, val backdrop: String, val rating: String, val year: String)

    private val cache = ConcurrentHashMap<String, Info>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean
        get() = PanelConfig.current.let { it.tmdbEnabled && (it.tmdbKey.isNotBlank() || it.tmdbToken.isNotBlank()) }

    fun img(path: String, size: String = "w500"): String =
        if (path.isBlank()) "" else "https://image.tmdb.org/t/p/$size$path"

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        val cfg = PanelConfig.current
        val b = Uri.parse("https://api.themoviedb.org/3$path").buildUpon()
            .appendQueryParameter("language", cfg.tmdbLanguage)
        if (cfg.tmdbKey.isNotBlank()) b.appendQueryParameter("api_key", cfg.tmdbKey)
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        val req = Net.request(b.build().toString()).newBuilder().apply {
            header("Accept", "application/json")
            if (cfg.tmdbKey.isBlank() && cfg.tmdbToken.isNotBlank()) header("Authorization", "Bearer ${cfg.tmdbToken}")
        }.build()
        try {
            Net.execute(Net.api, req).use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private val junk = Regex(
        "(?i)\\[.*?]|\\{.*?}|\\b(dublado|legendado|dual audio|dual|nacional|4k|uhd|fhd|hd|sd|1080p|720p|480p|h264|h265|hevc|hdr|cam|ts|web-?dl|bluray|lat|leg|dub)\\b|\\bS\\d{1,2}E\\d{1,3}\\b"
    )

    /** "Vingadores: Ultimato (2019) [4K] Dublado" -> ("Vingadores: Ultimato", "2019") */
    fun clean(name: String): Pair<String, String> {
        val year = Regex("\\((19|20)\\d{2}\\)|\\b(19|20)\\d{2}\\b").findAll(name).lastOrNull()?.value?.trim('(', ')').orEmpty()
        var t = name.replace(Regex("\\(.*?\\)"), " ").replace(junk, " ")
        if (year.isNotEmpty()) t = t.replace(year, " ")
        t = t.substringBefore(" | ").replace(Regex("[_.]"), " ").replace(Regex("\\s*-\\s*$"), "").replace(Regex("\\s+"), " ").trim()
        return t to year
    }

    /** Informações TMDB do título (usa o tmdb_id do servidor quando existir; senão busca pelo nome). */
    suspend fun info(m: Media, tmdbId: String = ""): Info? {
        if (!enabled || m.kind == Kind.LIVE) return null
        val key = m.key
        cache[key]?.let { return it }
        if (key in misses) return null
        val tv = m.kind == Kind.SERIES
        var id = tmdbId.toIntOrNull() ?: 0
        if (id <= 0) {
            val (q, year) = clean(m.name)
            if (q.length < 2) return null
            val params = mutableMapOf("query" to q, "include_adult" to "false")
            if (year.isNotEmpty()) params[if (tv) "first_air_date_year" else "year"] = year
            var res = get(if (tv) "/search/tv" else "/search/movie", params)?.optJSONArray("results")
            if ((res == null || res.length() == 0) && year.isNotEmpty()) {
                params.remove(if (tv) "first_air_date_year" else "year")
                res = get(if (tv) "/search/tv" else "/search/movie", params)?.optJSONArray("results")
            }
            id = res?.optJSONObject(0)?.optInt("id") ?: 0
        }
        if (id <= 0) {
            misses.add(key)
            return null
        }
        val extra = if (tv) "credits,videos,content_ratings" else "credits,videos,release_dates"
        val o = get(if (tv) "/tv/$id" else "/movie/$id", mapOf("append_to_response" to extra, "include_video_language" to "pt,en,null"))
        if (o == null) {
            misses.add(key)
            return null
        }
        val info = parse(o, tv)
        cache[key] = info
        return info
    }

    private fun parse(o: JSONObject, tv: Boolean): Info {
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("name").orEmpty() } }
            .orEmpty().filter { it.isNotBlank() }.take(3).joinToString(", ")
        val castArr = o.optJSONObject("credits")?.optJSONArray("cast")
        val cast = if (castArr == null) emptyList() else (0 until minOf(castArr.length(), 15)).mapNotNull { i ->
            castArr.optJSONObject(i)?.let { Person(it.optString("name"), it.optString("character"), img(it.optString("profile_path"), "w185")) }
        }
        val videos = o.optJSONObject("videos")?.optJSONArray("results")
        var trailer = ""
        if (videos != null) {
            val list = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.filter { it.optString("site") == "YouTube" }
            trailer = (list.firstOrNull { it.optString("type") == "Trailer" && it.optString("iso_639_1") == "pt" }
                ?: list.firstOrNull { it.optString("type") == "Trailer" }
                ?: list.firstOrNull())?.optString("key").orEmpty()
        }
        var cert = ""
        if (tv) {
            o.optJSONObject("content_ratings")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("iso_3166_1") == "BR") cert = it.optString("rating") }
            }
        } else {
            o.optJSONObject("release_dates")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    if (r.optString("iso_3166_1") != "BR") continue
                    val d = r.optJSONArray("release_dates") ?: continue
                    for (j in 0 until d.length()) d.optJSONObject(j)?.optString("certification")?.takeIf { it.isNotBlank() }?.let { cert = it }
                }
            }
        }
        val minutes = if (tv) o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime")
        val runtime = when {
            minutes <= 0 -> ""
            tv -> "$minutes min/ep"
            minutes >= 60 -> "${minutes / 60}h ${"%02d".format(minutes % 60)}min"
            else -> "$minutes min"
        }
        val vote = o.optDouble("vote_average", 0.0)
        return Info(
            id = o.optInt("id"),
            tv = tv,
            title = o.optString(if (tv) "name" else "title"),
            overview = o.optString("overview"),
            poster = img(o.optString("poster_path"), "w500"),
            backdrop = img(o.optString("backdrop_path"), "w1280"),
            rating = if (vote > 0) String.format(java.util.Locale.US, "%.1f", vote) else "",
            year = o.optString(if (tv) "first_air_date" else "release_date").take(4),
            runtime = runtime,
            genres = genres,
            cast = cast,
            trailer = trailer,
            certification = cert,
            tagline = o.optString("tagline"),
        )
    }

    /** Em alta na semana (filmes ou séries). */
    suspend fun trending(tv: Boolean): List<Trend> {
        if (!enabled) return emptyList()
        val arr = get(if (tv) "/trending/tv/week" else "/trending/movie/week")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val vote = o.optDouble("vote_average", 0.0)
            Trend(
                id = o.optInt("id"), tv = tv,
                title = o.optString(if (tv) "name" else "title"),
                poster = img(o.optString("poster_path"), "w342"),
                backdrop = img(o.optString("backdrop_path"), "w1280"),
                rating = if (vote > 0) String.format(java.util.Locale.US, "%.1f", vote) else "",
                year = o.optString(if (tv) "first_air_date" else "release_date").take(4),
            )
        }
    }

    /** Liga os títulos em alta do TMDB aos itens que existem no catálogo do cliente. */
    suspend fun trendingInCatalog(tv: Boolean): List<Pair<Trend, Media>> {
        // TV Box com pouca memória: não carrega o catálogo inteiro só para esta fileira
        if (com.novatv.app.ui.Ui.isLowRam(com.novatv.app.App.instance)) return emptyList()
        val trends = trending(tv)
        if (trends.isEmpty()) return emptyList()
        val kind = if (tv) Kind.SERIES else Kind.MOVIE
        val index = runCatching { Repo.searchIndex(kind) }.getOrDefault(emptyList())
        if (index.isEmpty() || index.size > 60_000) return emptyList()
        val byTitle = HashMap<String, Media>()
        index.forEach { (n, m) ->
            val k = Repo.normalize(clean(m.name).first).replace(Regex("[^a-z0-9]"), "")
            if (k.isNotEmpty()) byTitle.putIfAbsent(k, m)
        }
        return trends.mapNotNull { t ->
            val k = Repo.normalize(t.title).replace(Regex("[^a-z0-9]"), "")
            byTitle[k]?.let { t to it }
        }
    }
}
