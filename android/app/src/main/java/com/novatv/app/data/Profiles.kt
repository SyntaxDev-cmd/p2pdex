package com.novatv.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Perfil estilo Netflix: cada um tem favoritos, histórico, bloqueios e (opcional) PIN próprios. */
data class Profile(
    val id: String,
    val name: String,
    val color: Int,
    val emoji: String,
    val kids: Boolean = false,
    /** PIN para entrar neste perfil ("" = sem bloqueio). */
    val pin: String = "",
    /** Categorias bloqueadas por este perfil (chaves "LIVE:12", "MOVIE:3"...). */
    val locked: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("color", color).put("emoji", emoji)
        .put("kids", kids).put("pin", pin).put("locked", JSONArray(locked.toList()))

    companion object {
        fun fromJson(o: JSONObject): Profile = Profile(
            id = o.optString("id"),
            name = o.optString("name"),
            color = o.optInt("color"),
            emoji = o.optString("emoji", "🙂"),
            kids = o.optBoolean("kids"),
            pin = o.optString("pin"),
            locked = o.optJSONArray("locked")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
        )
    }
}

object Profiles {
    const val MAX = 5

    /** Paleta de avatares (cor inicial, cor final). */
    val palette: List<Pair<Int, Int>> = listOf(
        0xFFE50914.toInt() to 0xFF7A0010.toInt(),
        0xFF1E88E5.toInt() to 0xFF0D2E6E.toInt(),
        0xFF43A047.toInt() to 0xFF124D17.toInt(),
        0xFFFFB300.toInt() to 0xFFB25500.toInt(),
        0xFF8E24AA.toInt() to 0xFF3A0A52.toInt(),
        0xFF00ACC1.toInt() to 0xFF00474F.toInt(),
        0xFFF06292.toInt() to 0xFF8A1C46.toInt(),
        0xFF546E7A.toInt() to 0xFF1C272C.toInt(),
    )

    val emojis: List<String> = listOf("😀", "😎", "🤩", "🦁", "🐼", "🦊", "🐱", "🐶", "🦄", "🚀", "⚽", "🎮", "🎬", "🎧", "🌟", "👑")

    private fun storeKey() = "profiles_${Prefs.serverId}_${Prefs.username}"
    private fun currentKey() = "profile_cur_${Prefs.serverId}_${Prefs.username}"

    fun all(): List<Profile> = runCatching {
        val arr = JSONArray(Prefs.getRaw(storeKey()).ifEmpty { "[]" })
        (0 until arr.length()).map { Profile.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveAll(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.putRaw(storeKey(), arr.toString())
    }

    var currentId: String
        get() = Prefs.getRaw(currentKey())
        set(v) = Prefs.putRaw(currentKey(), v)

    /** No NovaKids todo perfil é infantil (só conteúdo infantil, sem adulto/esportes). */
    val current: Profile? get() = all().firstOrNull { it.id == currentId }?.let { if (AppConfig.kidsMode) it.copy(kids = true) else it }

    /** Garante pelo menos um perfil e um perfil atual válido. */
    fun ensureDefault(): Profile {
        var list = all()
        if (list.isEmpty()) {
            val p = Profile(newId(), Prefs.username.ifBlank { "Principal" }.replaceFirstChar { it.uppercase() }.take(14), 0, "😀")
            list = listOf(p)
            saveAll(list)
        }
        val cur = list.firstOrNull { it.id == currentId } ?: list.first().also { currentId = it.id }
        return cur
    }

    fun upsert(p: Profile) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        saveAll(list.take(MAX))
    }

    fun delete(id: String) {
        val list = all().filter { it.id != id }
        saveAll(list)
        if (currentId == id) currentId = list.firstOrNull()?.id.orEmpty()
    }

    fun newId(): String = "p" + System.currentTimeMillis().toString(36)
}
