package com.novatv.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        adopt(server, p, acc, user, pass)
        return acc
    }

    private fun adopt(server: ServerDef, p: Provider, acc: Account, user: String, pass: String) {
        provider = p
        account = acc
        clear()
        Prefs.serverId = server.id
        Prefs.activeBase = p.activeBase
        Prefs.username = user
        Prefs.password = pass
    }

    /**
     * Seleção automática de servidor: testa todos os servidores ativos do painel ao mesmo
     * tempo (DNS, P2P e M3U) e entra no primeiro que aceitar o usuário e a senha.
     * O servidor usado da última vez é testado junto, então quem já entrou volta rápido.
     */
    suspend fun loginAuto(user: String, pass: String): Account = withContext(Dispatchers.IO) {
        val list = servers().sortedByDescending { it.id == Prefs.serverId }
        if (list.isEmpty()) throw NoServerException()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val winner = CompletableDeferred<Triple<ServerDef, Provider, Account>>()
        val errors = mutableListOf<Pair<ServerDef, Exception>>()
        val jobs = list.map { s ->
            scope.launch {
                try {
                    val p = create(s, user, pass)
                    val acc = kotlinx.coroutines.withTimeout(35_000) { p.login() }
                    winner.complete(Triple(s, p, acc))
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(s to e)
                        if (errors.size == list.size) winner.completeExceptionally(loginError(errors))
                    }
                }
            }
        }
        try {
            val (s, p, acc) = winner.await()
            adopt(s, p, acc, user, pass)
            acc
        } finally {
            jobs.forEach { it.cancel() }
            scope.cancel()
        }
    }

    /**
     * Junta os erros de todos os servidores: senha errada/vencida tem prioridade;
     * senão, explica o que aconteceu em cada servidor (ajuda o suporte).
     */
    private fun loginError(errors: List<Pair<ServerDef, Exception>>): Exception {
        val auth = errors.map { it.second }.filterIsInstance<AuthException>()
        if (auth.isNotEmpty()) return XtreamProvider.bestError(auth)
        val detail = errors.joinToString("\n") { (s, e) -> "• ${s.name}: ${Net.describe(e)}" }
        return LoginNetworkException(detail)
    }

    /** Login automático com os dados salvos (em qualquer servidor ativo do painel). */
    suspend fun restore(): Account? {
        if (!Prefs.loggedIn) return null
        if (servers().isEmpty()) return null
        return loginAuto(Prefs.username, Prefs.password)
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

    /** Pouca memória: solta catálogos completos, índices e guias (são recarregados quando precisar). */
    fun trimMemory() {
        val heavy = listOf("all:", "idx:", "live:", "details:", "guide:", "epg:")
        cache.keys.filter { k -> heavy.any { k.startsWith(it) } }.forEach { cache.remove(it) }
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
        val raw = rawCategories(kind)
        // app de esportes: só categorias de esporte (se a lista não tiver nenhuma, mostra os canais ao vivo)
        val sport = raw.filter { matches(it.name, config.sportsKeywords) && !isAdultName(it) }
        val base = if (sport.isNotEmpty() || kind != Kind.LIVE) sport else raw
        return base.mapNotNull { c ->
            when {
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
            // streaming sem categoria própria: o que está no serviço (TMDB) e existe na lista do cliente
            sp != null && sp.startsWith("stream:") -> cached("items:${c.kind}:$sp") { Tmdb.providerInCatalog(sp.removePrefix("stream:"), c.kind == Kind.SERIES) }
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
