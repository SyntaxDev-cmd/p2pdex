package com.novatv.app.data

import android.content.Context
import android.os.Build
import com.novatv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Se o app fechar por erro, grava o motivo e envia ao painel na próxima abertura
 * (Painel → Segurança e registros → Erros do app). Assim nenhum travamento passa despercebido.
 */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("thread=").append(thread.name).append('\n')
                    append("versao=").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                    append("aparelho=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" android=").append(Build.VERSION.RELEASE).append('\n')
                    append(sw.toString().take(16_000))
                }
                file(app).writeText(text)
            }
            // erro numa tarefa em segundo plano (vídeo, rede, guia): registra e mantém o app aberto.
            // Só um erro na tela principal (que a rede de segurança não segurou) fecha o app.
            if (thread === android.os.Looper.getMainLooper().thread) {
                previous?.uncaughtException(thread, e)
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { android.widget.Toast.makeText(app, "Ops! Algo não saiu como esperado. Tente de novo.", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    /** Erro que não derrubou o app (capturado): guarda para enviar ao painel. Falta de memória limpa os caches. */
    fun nonFatal(e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) return
        if (e is OutOfMemoryError) Repo.trimMemory()
        runCatching {
            val f = file(com.novatv.app.App.instance)
            // mantém um erro fatal pendente; entre os não fatais, guarda o mais recente
            if (f.exists() && !f.readText().startsWith("[nao fatal]")) return@runCatching
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            f.writeText("[nao fatal]\nversao=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "aparelho=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE}\n" + sw.toString().take(16_000))
        }
    }

    /** Envia o último erro (se houver) e apaga o arquivo. */
    suspend fun sendPending(ctx: Context) = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (!f.exists() || AppConfig.panelUrl.isBlank()) return@withContext
        val trace = runCatching { f.readText() }.getOrDefault("")
        if (trace.isBlank()) {
            f.delete()
            return@withContext
        }
        val body = FormBody.Builder()
            .add("key", AppConfig.panelKey)
            .add("device_id", Prefs.deviceId)
            .add("model", (Build.MANUFACTURER + " " + Build.MODEL).take(80))
            .add("android", Build.VERSION.RELEASE)
            .add("version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            .add("trace", trace)
            .build()
        val req = Request.Builder().url(AppConfig.panelUrl + "/api/crash.php").post(body)
            .header("User-Agent", Net.DEFAULT_UA).build()
        runCatching {
            Net.execute(Net.api, req).use { if (it.isSuccessful || it.code in 400..499) f.delete() }
        }
    }
}
