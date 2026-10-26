package com.novatv.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("novatv", Context.MODE_PRIVATE)
        if (deviceId.isEmpty()) deviceId = buildDeviceId(ctx)
    }

    @SuppressLint("HardwareIds")
    private fun buildDeviceId(ctx: Context): String {
        val raw = runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: UUID.randomUUID().toString()
        val md = MessageDigest.getInstance("SHA-256").digest((raw + ctx.packageName).toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    fun getRaw(k: String): String = str(k)
    fun putRaw(k: String, v: String) = put(k, v)

    var deviceId: String
        get() = str("device_id")
        set(v) = put("device_id", v)

    // ---- Configuração do painel (cache offline)
    var configJson: String
        get() = str("config_json")
        set(v) = put("config_json", v)
    var configEtag: String
        get() = str("config_etag")
        set(v) = put("config_etag", v)

    // ---- Login
    var serverId: Int
        get() = sp.getInt("server_id", 0)
        set(v) = sp.edit().putInt("server_id", v).apply()
    var username: String
        get() = str("user")
        set(v) = put("user", v)
    var password: String
        get() = str("pass")
        set(v) = put("pass", v)
    var activeBase: String
        get() = str("active_base")
        set(v) = put("active_base", v)
    /** Código de revenda digitado no login (quando o APK não traz um). */
    var resellerCode: String
        get() = str("reseller_code")
        set(v) = put("reseller_code", v)


    fun logout() {
        sp.edit().remove("user").remove("pass").remove("active_base").apply()
    }

    val loggedIn: Boolean get() = username.isNotEmpty() && password.isNotEmpty()

    // ---- PIN parental (o cliente pode trocar; vazio = PIN do painel)
    var customPin: String
        get() = str("pin")
        set(v) = put("pin", v)

    // ---- Avisos
    var lastSeenMessage: Int
        get() = sp.getInt("last_msg", 0)
        set(v) = sp.edit().putInt("last_msg", v).apply()
    fun popupSeen(id: Int) = sp.getBoolean("popup_$id", false)
    fun markPopup(id: Int) = sp.edit().putBoolean("popup_$id", true).apply()

    // ---- Últimas escolhas
    fun lastCategory(page: String): String = str("lastcat_$page")
    fun setLastCategory(page: String, key: String) = put("lastcat_$page", key)

    // ---- Favoritos (canais, filmes, séries)
    private fun loadList(key: String): MutableList<Media> {
        val out = mutableListOf<Media>()
        val raw = str(key)
        if (raw.isEmpty()) return out
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(Media.fromJson(arr.getJSONObject(i)))
        }
        return out
    }

    private fun saveList(key: String, list: List<Media>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        put(key, arr.toString())
    }

    /** Dados separados por conta e por perfil. */
    private fun scoped(k: String) = "${k}_${serverId}_${username}_${Profiles.currentId}"

    /** Tema da seção Kids deste perfil: "" (arco-íris), "boys" ou "girls". */
    var kidsTheme: String
        get() = str(scoped("kids_theme"))
        set(v) = put(scoped("kids_theme"), v)

    fun clearHistory() {
        sp.edit().remove(scoped("resume")).remove(scoped("recent")).apply()
    }

    fun favorites(kind: Kind? = null): List<Media> =
        loadList(scoped("fav")).filter { kind == null || it.kind == kind }

    fun isFavorite(m: Media) = loadList(scoped("fav")).any { it.key == m.key }

    /** Retorna true se ficou favoritado. */
    fun toggleFavorite(m: Media): Boolean {
        val list = loadList(scoped("fav"))
        val idx = list.indexOfFirst { it.key == m.key }
        val added = if (idx >= 0) { list.removeAt(idx); false } else { list.add(0, m); true }
        saveList(scoped("fav"), list.take(300))
        return added
    }

    fun recents(): List<Media> = loadList(scoped("recent"))

    fun addRecent(m: Media) {
        if (m.kind != Kind.LIVE) return
        val list = loadList(scoped("recent"))
        list.removeAll { it.key == m.key }
        list.add(0, m)
        saveList(scoped("recent"), list.take(30))
    }

    // ---- Continuar assistindo (filmes e episódios)
    data class Resume(val key: String, val title: String, val image: String, val position: Long, val duration: Long, val media: Media?, val payload: String)

    fun saveResume(key: String, title: String, image: String, position: Long, duration: Long, media: Media?, payload: String = "") {
        val obj = runCatching { JSONObject(str(scoped("resume"), "{}")) }.getOrDefault(JSONObject())
        if (duration > 0 && position > duration * 0.95) {
            obj.remove(key)
        } else if (position > 15_000) {
            obj.put(key, JSONObject().put("t", title).put("i", image).put("p", position).put("d", duration)
                .put("ts", System.currentTimeMillis()).put("m", media?.toJson()).put("x", payload))
        }
        // mantém só os 40 mais recentes
        if (obj.length() > 40) {
            val keys = obj.keys().asSequence().toList().sortedBy { obj.getJSONObject(it).optLong("ts") }
            keys.take(obj.length() - 40).forEach { obj.remove(it) }
        }
        put(scoped("resume"), obj.toString())
    }

    fun resumePosition(key: String): Long =
        runCatching { JSONObject(str(scoped("resume"), "{}")).optJSONObject(key)?.optLong("p") ?: 0L }.getOrDefault(0L)

    fun resumes(): List<Resume> {
        val obj = runCatching { JSONObject(str(scoped("resume"), "{}")) }.getOrDefault(JSONObject())
        return obj.keys().asSequence().mapNotNull { k ->
            val o = obj.optJSONObject(k) ?: return@mapNotNull null
            Resume(k, o.optString("t"), o.optString("i"), o.optLong("p"), o.optLong("d"),
                o.optJSONObject("m")?.let { Media.fromJson(it) }, o.optString("x")) to o.optLong("ts")
        }.sortedByDescending { it.second }.map { it.first }.toList()
    }

    // ---- Player (escolhas do usuário)
    var resizeMode: Int
        get() = sp.getInt("resize", 0)
        set(v) = sp.edit().putInt("resize", v).apply()
    /** "internal" ou "external" (VLC, MX Player...). */
    var playerMode: String
        get() = str("player_mode", "internal")
        set(v) = put("player_mode", v)
    /** "" = o que o painel definir, "ts" ou "m3u8". */
    var liveFormat: String
        get() = str("live_format")
        set(v) = put("live_format", v)
    /** "" = do painel, "low", "normal", "high". */
    var bufferMode: String
        get() = str("buffer_mode")
        set(v) = put("buffer_mode", v)
    /** "" = automático (SurfaceView, troca sozinho se ficar sem imagem), "texture" = modo compatível. */
    var videoSurface: String
        get() = str("video_surface")
        set(v) = put("video_surface", v)
    /** Ligado enquanto o player toca: se o app morrer tocando, a próxima abertura usa o modo compatível. */
    var playerBusy: Boolean
        get() = sp.getBoolean("player_busy", false)
        // commit: precisa estar gravado mesmo se o processo morrer logo em seguida
        set(v) { sp.edit().putBoolean("player_busy", v).commit() }
    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()
}
