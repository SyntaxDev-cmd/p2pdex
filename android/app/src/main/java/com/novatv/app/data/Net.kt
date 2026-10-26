package com.novatv.app.data

import android.content.Context
import android.os.Build
import com.novatv.app.R
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Net {
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 NovaTV"

    /** Cliente para APIs (timeouts curtos para failover rápido). */
    lateinit var api: OkHttpClient
        private set

    /** Cliente para vídeo (leitura longa). */
    lateinit var media: OkHttpClient
        private set

    /** Cliente para imagens. */
    lateinit var images: OkHttpClient
        private set

    fun init(ctx: Context) {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        }
        val base = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        if (Build.VERSION.SDK_INT < 25) trustLetsEncrypt(ctx, base)
        val root = base.build()

        api = root.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
        media = root.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        images = root.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Android < 7.1 não conhece o ISRG Root X1 (Let's Encrypt) — muito usado em hospedagens
     * como a Hostinger. Junta os certificados do sistema com o ISRG embarcado.
     */
    private fun trustLetsEncrypt(ctx: Context, b: OkHttpClient.Builder) {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val isrg = ctx.resources.openRawResource(R.raw.isrg_root_x1).use { cf.generateCertificate(it) }
            val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            sysTmf.init(null as KeyStore?)
            val sysTm = sysTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            sysTm.acceptedIssuers.forEachIndexed { i, c -> ks.setCertificateEntry("sys$i", c) }
            ks.setCertificateEntry("isrg_x1", isrg)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(tm), null)
            b.sslSocketFactory(ssl.socketFactory, tm)
        } catch (_: Exception) {
        }
    }

    fun request(url: String, ua: String = ""): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", ua.ifBlank { DEFAULT_UA })
            .header("Accept", "*/*")
            .build()

    /** Executa a chamada de forma cancelável (cancela o socket se a coroutine for cancelada). */
    suspend fun execute(client: OkHttpClient, req: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
}
