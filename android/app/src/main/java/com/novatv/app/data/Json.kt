package com.novatv.app.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Leitura em streaming: listas Xtream podem ter dezenas de milhares de itens (vários MB).
 * Ler objeto por objeto evita montar a árvore JSON inteira na memória — essencial em TV Box fraca.
 */
object Json {

    fun <T> readArray(input: InputStream, map: (Map<String, String>) -> T?): List<T> {
        val out = ArrayList<T>(256)
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) map(readFlat(r))?.let { out.add(it) } else r.skipValue()
                    }
                    r.endArray()
                }
                // alguns painéis devolvem {"0":{...},"1":{...}}
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        if (r.peek() == JsonToken.BEGIN_OBJECT) map(readFlat(r))?.let { out.add(it) } else r.skipValue()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        return out
    }

    /** Lê um objeto guardando só valores simples (números/strings/bool). */
    fun readFlat(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>(24)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }
}
