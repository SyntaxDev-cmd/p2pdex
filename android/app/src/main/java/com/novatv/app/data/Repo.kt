package com.novatv.app.data

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Ponto único de acesso aos dados, com cache em memória e filtros (kids, anime, esportes, adulto). */
object Repo {
    @Volatile
    var provider: Provider? = null
        private set
    var account: Account? = null
        private set

    private val cache = ConcurrentHashMap<String, Any>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val unlocked = HashSet<String>()

    val config: PanelConfig get() = PanelConfig.current

    /** Somente os servidores cadastrados no painel (o cliente nunca digita DNS). */
    fun servers(): List<ServerDef> = config.servers

    fun create(server: ServerDef, user: String, pass: String): Provider =
        if (server.type == "m3u") M3uProvider(server, user, pass)
        else XtreamProvider(server, user, pass, Prefs.activeBase)

    suspend fun login(server: ServerDef, user: String, pass: String): Account {
        val p = create(server, user, pass)
        val acc = p.login()
        provider = p
        account = acc
        clear()
        Prefs.serverId = server.id
        Prefs.username = user
        Prefs.password = pass
        return acc
    }

    /** Login automático com os dados salvos. */
    suspend fun restore(): Account? {
        if (!Prefs.loggedIn) return null
        val servers = servers()
        val server = servers.firstOrNull { it.id == Prefs.serverId } ?: servers.firstOrNull() ?: return null
        return login(server, Prefs.username, Prefs.password)
    }

    fun logout() {
        provider = null
        account = null
        clear()
        Prefs.logout()
    }

    fun clear() {
        cache.clear()
        synchronized(unlocked) { unlocked.clear() }
    }

    private fun need(): Provider = provider ?: throw AuthException("Sessão expirada. Entre novamente.")

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T {
        cache[key]?.let { return it as T }
        val mutex = locks.getOrPut(key) { Mutex() }
        // carregamentos e índices rodam fora da thread principal (evita travar a TV Box)
        return mutex.withLock {
            (cache[key] as T?) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { load() }.also { cache[key] = it }
        }
    }

    // ------------------------------------------------------------ filtros

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private val splitRe = Regex("[^a-z0-9+]+")

    fun matches(name: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val n = normalize(name)
        val tokens by lazy { n.split(splitRe).toSet() }
        return keywords.any { raw ->
            val k = normalize(raw)
            when {
                k.isEmpty() -> false
                k.length <= 3 && !k.contains(' ') -> k in tokens
                else -> n.contains(k)
            }
        }
    }

    fun isAdult(c: Category) = config.hideAdult && matches(c.name, config.adultKeywords)

    fun isUnlocked(c: Category) = synchronized(unlocked) { c.key in unlocked }
    fun unlock(c: Category) = synchronized(unlocked) { unlocked.add(c.key) }

    // ------------------------------------------------------------ dados

    private suspend fun rawCategories(kind: Kind): List<Category> = cached("cats:$kind") { need().categories(kind) }

    /**
     * Categorias visíveis para o perfil atual:
     *  - perfil infantil: só categorias infantis (adulto nunca aparece)
     *  - demais: adulto e categorias bloqueadas pelo perfil pedem PIN
     */
    suspend fun categories(kind: Kind): List<Category> {
        val p = Profiles.current
        val kids = p?.kids == true
        return rawCategories(kind).mapNotNull { c ->
            when {
                kids && (isAdultName(c) || !matches(c.name, config.kidsKeywords)) -> null
                isAdult(c) || (p != null && c.key in p.locked) -> c.copy(locked = true)
                else -> c
            }
        }
    }

    /** Todas as categorias (para a tela de bloqueio por perfil). */
    suspend fun allCategories(kind: Kind): List<Category> = rawCategories(kind)

    private fun isAdultName(c: Category) = matches(c.name, config.adultKeywords)

    /** Troca de perfil: limpa desbloqueios temporários e índices de busca. */
    fun onProfileChanged() {
        synchronized(unlocked) { unlocked.clear() }
        cache.keys.filter { it.startsWith("idx:") || it.startsWith("live:") }.forEach { cache.remove(it) }
    }

    suspend fun items(kind: Kind, categoryId: String): List<Media> = cached("items:$kind:$categoryId") {
        need().items(kind, categoryId)
    }

    suspend fun items(c: Category): List<Media> {
        val sp = c.special
        return when {
            sp == "fav" -> Prefs.favorites(c.kind)
            sp == "recent" -> Prefs.recents()
            // categorias virtuais usadas pelos atalhos da tela inicial
            sp == "new" -> searchIndex(c.kind).map { it.second }.sortedByDescending { it.added }.take(200)
            sp != null && sp.startsWith("year:") -> {
                val y = sp.removePrefix("year:")
                searchIndex(c.kind).map { it.second }.filter { it.year == y || it.name.contains(y) }.sortedByDescending { it.added }.take(300)
            }
            else -> items(c.kind, c.id)
        }
    }

    suspend fun all(kind: Kind): List<Media> = cached("all:$kind") { need().all(kind) }

    suspend fun details(m: Media): Details = cached("details:${m.key}") { need().details(m) }

    suspend fun epg(m: Media): List<EpgItem> {
        if (!config.epg || m.kind != Kind.LIVE) return emptyList()
        val key = "epg:${m.id}"
        @Suppress("UNCHECKED_CAST")
        val hit = cache[key] as? Pair<Long, List<EpgItem>>
        if (hit != null && System.currentTimeMillis() - hit.first < 5 * 60_000) return hit.second
        val list = need().shortEpg(m)
        cache[key] = System.currentTimeMillis() to list
        return list
    }

    /** Guia completo do canal (cache de 15 min). */
    suspend fun guide(m: Media): List<EpgItem> {
        if (!config.epg || m.kind != Kind.LIVE) return emptyList()
        val key = "guide:${m.id}"
        @Suppress("UNCHECKED_CAST")
        val hit = cache[key] as? Pair<Long, List<EpgItem>>
        if (hit != null && System.currentTimeMillis() - hit.first < 15 * 60_000) return hit.second
        val list = need().fullEpg(m)
        cache[key] = System.currentTimeMillis() to list
        return list
    }

    /** Categorias de um tipo que batem com palavras-chave (kids, anime, esportes). */
    suspend fun filtered(kind: Kind, keywords: List<String>): List<Category> =
        categories(kind).filter { !it.locked && matches(it.name, keywords) }

    /** Categorias para a aba KIDS: canais + filmes + séries, em paralelo. */
    suspend fun kidsCategories(): List<Category> = mixed(config.kidsKeywords, listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES))

    suspend fun animeCategories(): List<Category> = mixed(config.animeKeywords, listOf(Kind.SERIES, Kind.MOVIE))

    private suspend fun mixed(keywords: List<String>, kinds: List<Kind>): List<Category> = coroutineScope {
        val jobs: List<Deferred<List<Category>>> = kinds.map { k ->
            async { runCatching { filtered(k, keywords) }.getOrDefault(emptyList()) }
        }
        jobs.flatMap { it.await() }
    }

    private suspend fun index(kind: Kind): List<Pair<String, Media>> = cached("idx:$kind:${Profiles.currentId}") {
        // só entra na busca o que o perfil pode ver sem PIN
        val allowed = categories(kind).filter { !it.locked }.map { it.id }.toHashSet()
        all(kind).filter { it.categoryId in allowed || it.categoryId.isEmpty() && Profiles.current?.kids != true }
            .map { normalize(it.name) to it }
    }

    /** Canais ao vivo visíveis para o perfil, com nome compactado (para achar o canal do jogo). */
    suspend fun liveIndex(): List<Pair<String, Media>> = cached("live:${Profiles.currentId}") {
        val allowed = categories(Kind.LIVE).filter { !it.locked }.map { it.id }.toHashSet()
        all(Kind.LIVE).filter { it.categoryId in allowed || it.categoryId.isEmpty() }.map { Games.compact(it.name) to it }
    }

    /** Catálogo completo visível para o perfil (nome normalizado + item). */
    suspend fun searchIndex(kind: Kind): List<Pair<String, Media>> = index(kind)

    /** Busca em todo o catálogo (sem acento, sem diferenciar maiúsculas). */
    suspend fun search(query: String, kinds: List<Kind>): List<Media> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val q = normalize(query.trim())
        if (q.length < 2) return@withContext emptyList<Media>()
        val parts = q.split(' ').filter { it.isNotBlank() }
        kinds.map { k -> async { runCatching { index(k) }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
            .asSequence()
            .filter { (n, _) -> parts.all { n.contains(it) } }
            .sortedBy { (n, _) -> if (n.startsWith(q)) 0 else 1 }
            .map { it.second }
            .take(300)
            .toList()
    }

    /** Pré-carrega o índice de busca em segundo plano. */
    suspend fun warmSearch(kinds: List<Kind>) = coroutineScope {
        kinds.map { k -> async { runCatching { index(k) } } }.forEach { it.await() }
    }
}
