package com.novatv.app.data

import org.json.JSONObject

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(
    val id: String,
    val name: String,
    val kind: Kind,
    val locked: Boolean = false,
    /** "fav", "recent" para categorias especiais. */
    val special: String? = null,
) {
    /** Chave única entre tipos (Kids mistura canais, filmes e séries). */
    val key: String get() = "${kind.name}:$id"
}

data class Media(
    val id: String,
    val name: String,
    val kind: Kind,
    val icon: String = "",
    val categoryId: String = "",
    val ext: String = "",
    val rating: String = "",
    val year: String = "",
    val added: Long = 0,
    val epgId: String = "",
    val num: Int = 0,
    /** URL completa (listas M3U). */
    val directUrl: String = "",
) {
    val key: String get() = "${kind.name}:$id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("icon", icon)
        .put("cat", categoryId).put("ext", ext).put("rating", rating).put("year", year)
        .put("added", added).put("epg", epgId).put("num", num).put("url", directUrl)

    companion object {
        fun fromJson(o: JSONObject): Media = Media(
            id = o.optString("id"),
            name = o.optString("name"),
            kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.LIVE),
            icon = o.optString("icon"),
            categoryId = o.optString("cat"),
            ext = o.optString("ext"),
            rating = o.optString("rating"),
            year = o.optString("year"),
            added = o.optLong("added"),
            epgId = o.optString("epg"),
            num = o.optInt("num"),
            directUrl = o.optString("url"),
        )
    }
}

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val ext: String,
    val image: String,
    val plot: String,
    val duration: String,
    val directUrl: String = "",
)

data class Details(
    val name: String,
    val cover: String,
    val backdrop: String,
    val plot: String,
    val genre: String,
    val cast: String,
    val director: String,
    val rating: String,
    val year: String,
    val duration: String,
    val ext: String = "",
    /** ID do TMDB informado pelo servidor (quando houver). */
    val tmdbId: String = "",
    /** Somente séries: temporada -> episódios (ordenados). */
    val seasons: Map<Int, List<Episode>> = emptyMap(),
)

data class EpgItem(val title: String, val description: String, val start: Long, val end: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Int =
        if (end <= start) 0 else (((now - start) * 100) / (end - start)).toInt().coerceIn(0, 100)
}

data class Account(
    val status: String,
    val expiresAt: Long?,
    val maxConnections: String,
    val activeConnections: String,
    val trial: Boolean,
)

class AuthException(message: String) : Exception(message)

/** O painel não mandou nenhum servidor ativo para este aparelho. */
class NoServerException : java.io.IOException("Nenhum servidor disponível no momento. Fale com o suporte.")

/** Nenhum servidor respondeu ao login; [detail] diz o motivo de cada um. */
class LoginNetworkException(val detail: String) : java.io.IOException(detail)
