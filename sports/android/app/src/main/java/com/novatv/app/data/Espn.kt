package com.novatv.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class League(val code: String, val name: String)

data class Team(val name: String, val short: String, val abbr: String, val logo: String, val score: String, val winner: Boolean)

data class Match(
    val id: String,
    val league: League,
    val leagueLogo: String,
    val start: Long,
    /** pre | in | post */
    val state: String,
    /** STATUS_SCHEDULED, STATUS_HALFTIME, STATUS_FULL_TIME, STATUS_POSTPONED... */
    val statusName: String,
    val clock: String,
    val home: Team,
    val away: Team,
    val venue: String,
    val broadcasts: List<String>,
) {
    val live: Boolean get() = state == "in"
    val finished: Boolean get() = state == "post"
    val key: String get() = "${league.code}:$id"

    /** Texto do status em português: "AO VIVO 37'", "Intervalo", "Encerrado", "21:30". */
    fun statusLabel(): String = when {
        statusName.contains("POSTPONED") -> "Adiado"
        statusName.contains("CANCEL") -> "Cancelado"
        statusName.contains("HALFTIME") -> "Intervalo"
        statusName.contains("PENALT") -> "Pênaltis"
        statusName.contains("EXTRA") || statusName.contains("OVERTIME") -> "Prorrogação ${clock.takeIf { it != "0'" }.orEmpty()}".trim()
        state == "in" -> clock.takeIf { it.isNotBlank() && it != "0'" } ?: "Ao vivo"
        state == "post" -> "Encerrado"
        else -> timeLabel()
    }

    fun timeLabel(): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.time)
        val days = dayDiff(now, c)
        return when (days) {
            0 -> hm
            1 -> "Amanhã $hm"
            -1 -> "Ontem $hm"
            else -> SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(c.time)
        }
    }

    private fun dayDiff(a: Calendar, b: Calendar): Int {
        val x = (a.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val y = (b.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return ((y.timeInMillis - x.timeInMillis) / 86_400_000L).toInt()
    }
}

data class Article(val title: String, val description: String, val image: String, val url: String, val published: Long, val league: String) {
    fun ago(): String {
        if (published <= 0) return ""
        val m = (System.currentTimeMillis() - published) / 60_000
        return when {
            m < 1 -> "agora"
            m < 60 -> "há $m min"
            m < 24 * 60 -> "há ${m / 60} h"
            m < 48 * 60 -> "ontem"
            else -> "há ${m / 1440} dias"
        }
    }
}

data class Standing(
    val rank: Int, val team: String, val abbr: String, val logo: String,
    val points: String, val played: String, val wins: String, val draws: String, val losses: String,
    val goalDiff: String, val goalsFor: String, val goalsAgainst: String,
)

data class StandingGroup(val name: String, val rows: List<Standing>)

/**
 * API pública e gratuita da ESPN (site.api.espn.com): jogos com placar ao vivo, notícias
 * (ESPN Brasil, em português) e tabelas. Se o aparelho não alcançar a ESPN, usa o painel
 * como intermediário (api/espn.php, com cache).
 */
object Espn {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    private const val V2 = "https://site.api.espn.com/apis/v2/sports/soccer"

    /** Ligas padrão (o painel pode trocar a lista e a ordem). */
    val defaultLeagues = listOf(
        League("bra.1", "Brasileirão"),
        League("conmebol.libertadores", "Libertadores"),
        League("bra.copa_do_brazil", "Copa do Brasil"),
        League("uefa.champions", "Champions League"),
        League("conmebol.sudamericana", "Sul-Americana"),
        League("bra.2", "Série B"),
        League("eng.1", "Premier League"),
        League("esp.1", "LaLiga"),
        League("ita.1", "Serie A (Itália)"),
        League("ger.1", "Bundesliga"),
        League("fra.1", "Ligue 1"),
        League("por.1", "Liga Portugal"),
        League("uefa.europa", "Europa League"),
        League("arg.1", "Argentina"),
        League("bra.camp.paulista", "Paulistão"),
        League("bra.camp.carioca", "Carioca"),
        League("usa.1", "MLS"),
        League("tur.1", "Turquia"),
        League("ned.1", "Holanda"),
        League("bel.1", "Bélgica"),
        League("sco.1", "Escócia"),
        League("mex.1", "México"),
        League("sau.1", "Arábia Saudita"),
        League("uefa.nations", "Nations League"),
        League("conmebol.america", "Copa América"),
        League("fifa.world", "Copa do Mundo"),
    )

    val leagues: List<League> get() = PanelConfig.current.sportsLeagues.ifEmpty { defaultLeagues }

    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, load: suspend () -> T): T {
        cache[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < ttlMs) return v as T }
        return try {
            load().also { cache[key] = System.currentTimeMillis() to it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // sem rede: devolve o último resultado bom, se houver
            (cache[key]?.second as? T) ?: throw e
        }
    }

    private suspend fun json(url: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            readUrl(url)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // plano B: painel como intermediário
            val panel = AppConfig.panelUrl
            if (panel.isBlank()) throw e
            readUrl("$panel/api/espn.php?u=" + Uri.encode(url))
        }
    }

    private suspend fun readUrl(url: String): JSONObject =
        Net.execute(Net.api, Net.request(url)).use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            JSONObject(resp.body?.string().orEmpty())
        }

    private val ymd = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd", Locale.US)
    }
    private val iso = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun parseDate(s: String): Long = runCatching { iso.get()!!.parse(s.take(16))?.time ?: 0L }.getOrDefault(0L)

    /** Jogos de uma liga num dia (offset 0 = hoje, -1 = ontem, 1 = amanhã). */
    suspend fun scoreboard(league: League, dayOffset: Int = 0): List<Match> {
        val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val date = ymd.get()!!.format(day.time)
        // ao vivo atualiza rápido; outros dias podem ficar mais tempo em cache
        val ttl = if (dayOffset == 0) 25_000L else 10 * 60_000L
        return cached("sb:${league.code}:$date", ttl) {
            val o = json("$SITE/${league.code}/scoreboard?dates=$date&lang=pt&region=br&limit=100")
            val lg = o.optJSONArray("leagues")?.optJSONObject(0)
            val logo = lg?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
            val events = o.optJSONArray("events") ?: JSONArray()
            (0 until events.length()).mapNotNull { i -> events.optJSONObject(i)?.let { parseMatch(it, league, logo) } }
        }
    }

    private fun parseMatch(e: JSONObject, league: League, leagueLogo: String): Match? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = (comp.optJSONObject("status") ?: e.optJSONObject("status")) ?: JSONObject()
        val type = status.optJSONObject("type") ?: JSONObject()
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: Team? = null
        var away: Team? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.optJSONObject(i) ?: continue
            val t = c.optJSONObject("team") ?: continue
            val team = Team(
                name = t.optString("displayName"),
                short = t.optString("shortDisplayName").ifBlank { t.optString("displayName") },
                abbr = t.optString("abbreviation"),
                logo = t.optString("logo").ifBlank { t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty() },
                score = c.optString("score"),
                winner = c.optBoolean("winner"),
            )
            if (c.optString("homeAway") == "away") away = team else home = team
        }
        if (home == null || away == null) return null
        val names = mutableListOf<String>()
        comp.optJSONArray("broadcasts")?.let { b ->
            for (i in 0 until b.length()) b.optJSONObject(i)?.optJSONArray("names")?.let { n -> for (j in 0 until n.length()) names.add(n.optString(j)) }
        }
        return Match(
            id = e.optString("id"),
            league = league,
            leagueLogo = leagueLogo,
            start = parseDate(e.optString("date")),
            state = type.optString("state", "pre"),
            statusName = type.optString("name"),
            clock = status.optString("displayClock"),
            home = home,
            away = away,
            venue = comp.optJSONObject("venue")?.optString("fullName").orEmpty(),
            broadcasts = names.filter { it.isNotBlank() }.distinct(),
        )
    }

    /**
     * Jogos do dia: ESPN (placar ao vivo) + "Jogos do dia" do painel (ligas que a ESPN não
     * cobre, como sub-20 e estaduais, já com os canais). Ao vivo primeiro, depois por horário.
     */
    suspend fun day(dayOffset: Int = 0, list: List<League> = leagues): List<Match> = coroutineScope {
        val panel = if (dayOffset == 0) async { withTimeoutOrNull(20_000) { runCatching { Games.load() }.getOrNull() }.orEmpty() } else null
        val espn = list.map { lg -> async { withTimeoutOrNull(20_000) { runCatching { scoreboard(lg, dayOffset) }.getOrNull() }.orEmpty() } }
            .flatMap { it.await() }
        val extra = panel?.await().orEmpty()
            .filter { g -> g.home.isNotBlank() && g.away.isNotBlank() && espn.none { m -> sameGame(m, g) } }
            .map { fromPanel(it) }
        (espn + extra).sortedWith(compareBy<Match>({ if (it.live) 0 else if (it.state == "pre") 1 else 2 }, { it.start }))
    }

    private fun same(a: String, b: String): Boolean {
        val x = Games.compact(a)
        val y = Games.compact(b)
        if (x.length < 3 || y.length < 3) return false
        return x.contains(y) || y.contains(x) || x.take(5) == y.take(5)
    }

    private fun sameGame(m: Match, g: Game) =
        (same(m.home.name, g.home) && same(m.away.name, g.away)) || (same(m.home.name, g.away) && same(m.away.name, g.home))

    /** Jogo do painel no formato do app. */
    private fun fromPanel(g: Game): Match {
        val st = g.liveStatus()
        val parts = g.score.split('-', 'x', 'X', '×').map { it.trim() }
        val hs = parts.getOrNull(0).orEmpty()
        val aws = parts.getOrNull(1).orEmpty()
        val code = "panel:" + Games.compact(g.league).ifBlank { "outros" }
        return Match(
            id = "p" + g.id,
            league = League(code, g.league.ifBlank { "Outros jogos" }),
            leagueLogo = g.leagueLogo,
            start = if (g.ts > 0) g.ts * 1000 else System.currentTimeMillis(),
            state = when (st) { "live" -> "in"; "ended" -> "post"; else -> "pre" },
            statusName = if (g.minute.contains("interv", true)) "STATUS_HALFTIME" else "",
            clock = g.minute,
            home = Team(g.home, g.home, "", g.homeLogo, hs, false),
            away = Team(g.away, g.away, "", g.awayLogo, aws, false),
            venue = "",
            broadcasts = g.channels,
        )
    }

    /** Notícias (ESPN Brasil, em português; se vier vazio, a versão internacional). */
    suspend fun news(league: League): List<Article> = cached("news:${league.code}", 10 * 60_000L) {
        var o = json("$SITE/${league.code}/news?lang=pt&region=br&limit=40")
        if ((o.optJSONArray("articles")?.length() ?: 0) == 0) o = json("$SITE/${league.code}/news?limit=40")
        val arr = o.optJSONArray("articles") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = a.optString("headline").ifBlank { return@mapNotNull null }
            val img = a.optJSONArray("images")?.let { im -> (0 until im.length()).mapNotNull { im.optJSONObject(it)?.optString("url") }.firstOrNull { it.isNotBlank() } }.orEmpty()
            Article(
                title = title,
                description = a.optString("description"),
                image = img,
                url = a.optJSONObject("links")?.optJSONObject("web")?.optString("href").orEmpty(),
                published = runCatching {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(a.optString("published").take(19))?.time ?: 0L
                }.getOrDefault(0L),
                league = league.name,
            )
        }
    }

    /** Notícias de várias ligas juntas (mais recentes primeiro, sem repetir). */
    suspend fun headlines(list: List<League>): List<Article> = coroutineScope {
        list.map { lg -> async { withTimeoutOrNull(20_000) { runCatching { news(lg) }.getOrNull() }.orEmpty() } }
            .flatMap { it.await() }.distinctBy { it.title }.sortedByDescending { it.published }
    }

    /** Classificação (uma tabela por grupo; ligas de pontos corridos têm um grupo só). */
    suspend fun standings(league: League): List<StandingGroup> = cached("st:${league.code}", 15 * 60_000L) {
        val o = json("$V2/${league.code}/standings?lang=pt&region=br")
        val groups = mutableListOf<StandingGroup>()
        fun parseEntries(name: String, st: JSONObject?) {
            val entries = st?.optJSONArray("entries") ?: return
            val rows = (0 until entries.length()).mapNotNull { i ->
                val en = entries.optJSONObject(i) ?: return@mapNotNull null
                val t = en.optJSONObject("team") ?: return@mapNotNull null
                val stats = HashMap<String, String>()
                en.optJSONArray("stats")?.let { s -> for (j in 0 until s.length()) s.optJSONObject(j)?.let { stats[it.optString("name")] = it.optString("displayValue") } }
                Standing(
                    rank = stats["rank"]?.toIntOrNull() ?: (i + 1),
                    team = t.optString("displayName"),
                    abbr = t.optString("abbreviation"),
                    logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty(),
                    points = stats["points"].orEmpty(),
                    played = stats["gamesPlayed"].orEmpty(),
                    wins = stats["wins"].orEmpty(),
                    draws = stats["ties"].orEmpty(),
                    losses = stats["losses"].orEmpty(),
                    goalDiff = stats["pointDifferential"].orEmpty(),
                    goalsFor = stats["pointsFor"].orEmpty(),
                    goalsAgainst = stats["pointsAgainst"].orEmpty(),
                )
            }.sortedBy { it.rank }
            if (rows.isNotEmpty()) groups.add(StandingGroup(name, rows))
        }
        val children = o.optJSONArray("children")
        if (children != null && children.length() > 0) {
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { parseEntries(it.optString("name"), it.optJSONObject("standings")) }
        } else parseEntries(o.optString("name"), o.optJSONObject("standings"))
        groups
    }
}
