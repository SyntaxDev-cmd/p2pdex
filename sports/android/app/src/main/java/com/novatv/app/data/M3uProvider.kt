package com.novatv.app.data

import android.net.Uri
import com.novatv.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Lista M3U / M3U8 (#EXTM3U). Baixa uma vez, guarda em cache por 6h e separa em
 * canais ao vivo, filmes e séries (pela URL /movie/ e /series/ ou pelo nome do grupo).
 */
class M3uProvider(override val server: ServerDef, private val user: String, private val pass: String) : Provider {

    override val username: String get() = user
    override val activeBase: String get() = server.m3uUrl

    // trocados de uma vez ao terminar de ler a lista (leitura nunca vê dados pela metade)
    @Volatile
    private var cats: Map<Kind, List<Category>> = emptyMap()
    @Volatile
    private var items: Map<Kind, List<Media>> = emptyMap()
    @Volatile
    private var loaded = false
    /** url-tvg / x-tvg-url do cabeçalho #EXTM3U. */
    @Volatile
    private var headerEpg = ""

    private fun epg(): XmlTvEpg? {
        val u = server.epgUrl.ifBlank { headerEpg }
            .replace("{username}", Uri.encode(user)).replace("{password}", Uri.encode(pass))
        return if (u.startsWith("http")) XmlTvEpg.of(u, server.userAgent) else null
    }

    private val url: String
        get() = server.m3uUrl.replace("{username}", Uri.encode(user)).replace("{password}", Uri.encode(pass))

    private val cacheFile: File
        get() = File(App.instance.cacheDir, "m3u_${server.id}_${(user + url).hashCode()}.m3u")

    private val loadLock = kotlinx.coroutines.sync.Mutex()

    /** Um carregamento por vez: duas telas pedindo a lista juntas não podem corromper os dados. */
    private suspend fun ensure(force: Boolean = false) {
        if (loaded && !force) return
        loadLock.lock()
        try {
            if (loaded && !force) return
            doEnsure(force)
        } finally {
            loadLock.unlock()
        }
    }

    private suspend fun doEnsure(force: Boolean) = withContext(Dispatchers.IO) {
        val f = cacheFile
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L && f.length() > 0
        if (!fresh || force) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            Net.execute(Net.api, Net.request(url, server.userAgent)).use { resp ->
                if (resp.code == 401 || resp.code == 403) throw AuthException("Usuário ou senha inválidos")
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, true)
                tmp.delete()
            }
        }
        BufferedReader(InputStreamReader(f.inputStream(), Charsets.UTF_8), 64 * 1024).use { parse(it) }
        if (items.values.sumOf { it.size } == 0) {
            f.delete()
            throw AuthException("Lista vazia ou login inválido")
        }
        loaded = true
    }

    private val attrRe = Regex("([\\w-]+)=\"([^\"]*)\"")

    private fun parse(r: BufferedReader) {
        val cats = HashMap<Kind, MutableList<Category>>()
        val items = HashMap<Kind, MutableList<Media>>()
        val catIndex = HashMap<String, String>()
        var info: String? = null
        var group: String? = null
        var counter = 0
        var line = r.readLine()
        if (line != null && line.startsWith("﻿")) line = line.substring(1)
        while (line != null) {
            val l = line.trim()
            when {
                l.startsWith("#EXTM3U", true) -> {
                    Regex("(?:url-tvg|x-tvg-url)=\"([^\"]+)\"").find(l)?.let { headerEpg = it.groupValues[1].split(',').first().trim() }
                }
                l.startsWith("#EXTINF", true) -> { info = l; group = null }
                l.startsWith("#EXTGRP:", true) -> group = l.substringAfter(':').trim()
                l.isEmpty() || l.startsWith("#") -> Unit
                info != null -> {
                    val attrs = HashMap<String, String>()
                    attrRe.findAll(info).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                    val lastQuote = info.lastIndexOf('"')
                    val comma = info.indexOf(',', if (lastQuote > 0) lastQuote else 0)
                    val name = (if (comma >= 0) info.substring(comma + 1) else attrs["tvg-name"].orEmpty()).trim()
                        .ifEmpty { attrs["tvg-name"].orEmpty() }
                    val g = (attrs["group-title"] ?: group).orEmpty().trim().ifEmpty { "Sem categoria" }
                    val lower = l.lowercase()
                    val kind = when {
                        "/movie/" in lower -> Kind.MOVIE
                        "/series/" in lower -> Kind.MOVIE
                        lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") -> Kind.MOVIE
                        else -> Kind.LIVE
                    }
                    val ck = "${kind.name}|$g"
                    val catId = catIndex.getOrPut(ck) {
                        val id = "c${catIndex.size}"
                        cats.getOrPut(kind) { mutableListOf() }.add(Category(id, g, kind))
                        id
                    }
                    counter++
                    items.getOrPut(kind) { mutableListOf() }.add(
                        Media(
                            id = "m$counter", name = name, kind = kind, icon = attrs["tvg-logo"].orEmpty(),
                            categoryId = catId, epgId = attrs["tvg-id"].orEmpty(), num = counter,
                            year = XtreamProvider.yearOf(name), directUrl = l,
                        )
                    )
                    info = null
                }
            }
            line = r.readLine()
        }
        this.cats = cats
        this.items = items
    }

    override suspend fun login(): Account {
        ensure(force = true)
        return Account("Active", null, "", "", false)
    }

    override suspend fun categories(kind: Kind): List<Category> {
        ensure()
        return cats[kind].orEmpty().toList()
    }

    override suspend fun items(kind: Kind, categoryId: String): List<Media> {
        ensure()
        return items[kind].orEmpty().filter { it.categoryId == categoryId }
    }

    override suspend fun all(kind: Kind): List<Media> {
        ensure()
        return items[kind].orEmpty().toList()
    }

    override suspend fun details(m: Media): Details =
        Details(m.name, m.icon, "", "", "", "", "", m.rating, m.year, "", m.ext)

    override suspend fun shortEpg(m: Media): List<EpgItem> {
        val now = System.currentTimeMillis()
        return fullEpg(m).filter { it.end > now }.take(4)
    }

    override suspend fun fullEpg(m: Media): List<EpgItem> {
        ensure()
        val x = epg() ?: return emptyList()
        return try {
            x.ensure()
            val since = System.currentTimeMillis() - 12 * 3600_000L
            x.forChannel(m.epgId, m.name).filter { it.end > since }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    override fun streamUrl(m: Media): String = m.directUrl

    override fun episodeUrl(e: Episode): String = e.directUrl

    override fun failover(): Boolean = false
}
