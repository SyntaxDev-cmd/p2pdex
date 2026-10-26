package com.novatv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Game(
    val id: String,
    val time: String,
    val ts: Long,
    val home: String,
    val away: String,
    val title: String,
    val homeLogo: String,
    val awayLogo: String,
    val league: String,
    val channels: List<String>,
    val status: String,
    val score: String,
) {
    /** Recalcula o status no aparelho (a lista fica em cache). */
    fun liveStatus(now: Long = System.currentTimeMillis() / 1000): String = when {
        status == "ended" -> "ended"
        ts <= 0 -> status
        now >= ts && now < ts + 2 * 3600 -> "live"
        now >= ts + 2 * 3600 -> "ended"
        else -> "soon"
    }
}

/** Jogos do dia vindos do painel (api/games.php) + localização do canal na lista do cliente. */
object Games {
    @Volatile
    private var cache: Pair<Long, List<Game>>? = null

    suspend fun load(force: Boolean = false): List<Game> = withContext(Dispatchers.IO) {
        val cfg = PanelConfig.current
        if (!cfg.gamesEnabled || cfg.gamesUrl.isBlank()) return@withContext emptyList()
        cache?.let { if (!force && System.currentTimeMillis() - it.first < 3 * 60_000) return@withContext it.second }
        val list = Net.execute(Net.api, Net.request(cfg.gamesUrl)).use { resp ->
            val o = JSONObject(resp.body?.string().orEmpty())
            val arr = o.optJSONArray("games") ?: return@use emptyList<Game>()
            (0 until arr.length()).mapNotNull { i ->
                val g = arr.optJSONObject(i) ?: return@mapNotNull null
                val ch = g.optJSONArray("channels")
                Game(
                    id = g.optString("id"),
                    time = g.optString("time"),
                    ts = g.optLong("ts"),
                    home = g.optString("home"),
                    away = g.optString("away"),
                    title = g.optString("title"),
                    homeLogo = g.optString("home_logo"),
                    awayLogo = g.optString("away_logo"),
                    league = g.optString("league"),
                    channels = if (ch == null) emptyList() else (0 until ch.length()).map { ch.optString(it) }.filter { it.isNotBlank() },
                    status = g.optString("status"),
                    score = g.optString("score"),
                )
            }
        }
        cache = System.currentTimeMillis() to list
        list
    }

    private val noise = Regex("\\b(fhd|uhd|hd|sd|4k|h265|hevc|alt|backup|br|ppv|24h|24hs|tv)\\b")

    /** "Premiere 2 FHD [BR]" -> "premiere2" */
    fun compact(name: String): String =
        Repo.normalize(name).replace(Regex("\\[.*?]|\\(.*?\\)"), " ").replace(noise, " ").replace(Regex("[^a-z0-9]"), "")

    /**
     * Procura na lista ao vivo os canais citados no jogo. Ordena pelo nome mais parecido e
     * prefere versões HD/FHD. Retorna (nome do canal do jogo, canais encontrados).
     */
    suspend fun matchChannels(game: Game): List<Media> = withContext(Dispatchers.Default) { match(game) }

    private suspend fun match(game: Game): List<Media> {
        if (game.channels.isEmpty()) return emptyList()
        val lives = Repo.liveIndex()
        val out = LinkedHashMap<String, Pair<Int, Media>>()
        for (wanted in game.channels) {
            val w = compact(wanted)
            if (w.length < 2) continue
            for ((c, m) in lives) {
                val score = when {
                    c == w -> 100
                    c.startsWith(w) && c.length - w.length <= 3 -> 80
                    c.startsWith(w) -> 60
                    w.length >= 4 && c.contains(w) -> 40
                    else -> 0
                }
                if (score == 0) continue
                val quality = Repo.normalize(m.name).let { n -> if ("fhd" in n || "4k" in n) 3 else if ("hd" in n) 2 else 0 }
                val total = score + quality
                val prev = out[m.key]
                if (prev == null || prev.first < total) out[m.key] = total to m
            }
        }
        return out.values.sortedByDescending { it.first }.map { it.second }.take(12)
    }
}
