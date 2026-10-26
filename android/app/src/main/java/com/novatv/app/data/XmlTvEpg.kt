package com.novatv.app.data

import android.util.Xml
import com.novatv.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Guia XMLTV (usado por listas M3U ou quando o painel define um EPG próprio).
 * Lê em streaming e guarda só a janela útil (6h atrás até 36h à frente), então
 * funciona mesmo com arquivos grandes em TV Box simples. Cache em disco por 6h.
 */
class XmlTvEpg(private val url: String, private val userAgent: String) {

    private val mutex = Mutex()
    @Volatile
    private var byId: Map<String, List<EpgItem>> = emptyMap()
    @Volatile
    private var idByName: Map<String, String> = emptyMap()
    @Volatile
    private var loadedAt = 0L

    private val cacheFile: File
        get() = File(App.instance.cacheDir, "xmltv_${url.hashCode()}.xml")

    suspend fun ensure() = mutex.withLock {
        if (byId.isNotEmpty() && System.currentTimeMillis() - loadedAt < 3 * 3600_000L) return@withLock
        withContext(Dispatchers.IO) {
            val f = cacheFile
            val fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L
            if (!fresh) {
                val tmp = File(f.parentFile, f.name + ".tmp")
                Net.execute(Net.media, Net.request(url, userAgent)).use { resp ->
                    if (!resp.isSuccessful) throw IOException("EPG HTTP ${resp.code}")
                    resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                }
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, true)
                    tmp.delete()
                }
            }
            open(f).use { parse(it) }
            loadedAt = System.currentTimeMillis()
        }
    }

    /** Abre o arquivo detectando gzip pelo cabeçalho (muitos EPGs vêm .xml.gz). */
    private fun open(f: File): InputStream {
        val raw = BufferedInputStream(f.inputStream(), 64 * 1024)
        raw.mark(2)
        val b1 = raw.read()
        val b2 = raw.read()
        raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw
    }

    private fun parse(input: InputStream) {
        val now = System.currentTimeMillis()
        val from = now - 6 * 3600_000L
        val to = now + 36 * 3600_000L
        val map = HashMap<String, MutableList<EpgItem>>()
        val names = HashMap<String, String>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var channelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title = ""
        var desc = ""
        var inProgramme = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> channelId = p.getAttributeValue(null, "id")
                    "display-name" -> {
                        val id = channelId
                        if (id != null) {
                            val n = p.nextText().orEmpty()
                            if (n.isNotBlank()) names.putIfAbsent(Repo.normalize(n).replace(Regex("[^a-z0-9]"), ""), id.lowercase())
                        }
                    }
                    "programme" -> {
                        progStart = parseTime(p.getAttributeValue(null, "start"))
                        progStop = parseTime(p.getAttributeValue(null, "stop"))
                        progChannel = p.getAttributeValue(null, "channel")?.lowercase()
                        inProgramme = progStop > from && progStart < to && progChannel != null
                        title = ""
                        desc = ""
                    }
                    "title" -> if (inProgramme && title.isEmpty()) title = p.nextText().orEmpty().trim()
                    "desc" -> if (inProgramme && desc.isEmpty()) desc = p.nextText().orEmpty().trim().take(400)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        val ch = progChannel
                        if (inProgramme && ch != null) map.getOrPut(ch) { ArrayList() }.add(EpgItem(title, desc, progStart, progStop))
                        inProgramme = false
                    }
                }
            }
            ev = p.next()
        }
        map.values.forEach { it.sortBy { e -> e.start } }
        byId = map
        idByName = names
    }

    /** Programação de um canal: pelo tvg-id/epg_channel_id; se não houver, pelo nome. */
    fun forChannel(epgId: String, name: String): List<EpgItem> {
        if (epgId.isNotBlank()) byId[epgId.lowercase()]?.let { return it }
        val key = Repo.normalize(name)
            .replace(Regex("\\b(fhd|uhd|hd|sd|4k|h265|hevc|alt|backup|\\[.*?]|\\(.*?\\))\\b"), "")
            .replace(Regex("[^a-z0-9]"), "")
        val id = idByName[key] ?: return emptyList()
        return byId[id].orEmpty()
    }

    companion object {
        private val fmtTz = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        }
        private val fmtLocal = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        }

        fun parseTime(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            val t = s.trim()
            return runCatching { fmtTz.get()!!.parse(t)!!.time }.getOrElse {
                runCatching { fmtLocal.get()!!.parse(t.take(14))!!.time }.getOrDefault(0L)
            }
        }

        private val instances = HashMap<String, XmlTvEpg>()

        @Synchronized
        fun of(url: String, ua: String): XmlTvEpg = instances.getOrPut(url) { XmlTvEpg(url, ua) }
    }
}
