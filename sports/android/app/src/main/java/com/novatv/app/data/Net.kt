package com.novatv.app.data

import android.content.Context
import android.os.Build
import com.novatv.app.R
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
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
        val bootstrap = base.build()
        // operadoras costumam bloquear domínios de IPTV/P2P no DNS: se o DNS do aparelho
        // não resolver, o app pergunta ao DNS seguro (DoH) da Cloudflare e do Google
        base.dns(FallbackDns(listOf(doh(bootstrap, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh(bootstrap, "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"))))
        val root = base.build()

        api = root.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
        // vídeo: servidores lentos/sobrecarregados demoram a mandar o 1º pedaço; paciência maior
        media = root.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        images = root.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun doh(client: OkHttpClient, url: String, vararg ips: String): Dns =
        DnsOverHttps.Builder().client(client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .includeIPv6(false)
            .build()

    /** IPv4 primeiro: muita rede doméstica anuncia IPv6 que não funciona e cada tentativa custa segundos. */
    private fun ipv4First(list: List<InetAddress>): List<InetAddress> =
        list.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }

    /** DNS do aparelho primeiro; se falhar (bloqueio, domínio novo), tenta o DNS seguro. */
    private class FallbackDns(private val secure: List<Dns>) : Dns {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, List<InetAddress>>()

        override fun lookup(hostname: String): List<InetAddress> {
            cache[hostname]?.let { return it }
            val first = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                null
            }
            // 0.0.0.0 / 127.x = domínio bloqueado pela operadora
            if (first != null && first.isNotEmpty() && first.none { it.isAnyLocalAddress || it.isLoopbackAddress }) return Net.ipv4First(first)
            for (d in secure) {
                try {
                    val r = d.lookup(hostname)
                    if (r.isNotEmpty()) {
                        val sorted = Net.ipv4First(r)
                        cache[hostname] = sorted
                        return sorted
                    }
                } catch (_: Exception) {
                }
            }
            if (first != null && first.isNotEmpty()) return first
            throw UnknownHostException("Não foi possível encontrar $hostname")
        }
    }

    /** Explica um erro de rede em português (aparece no login para facilitar o suporte). */
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "endereço não encontrado (DNS)"
        is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "o servidor demorou demais para responder"
        is java.net.ConnectException -> "o servidor recusou a conexão"
        is javax.net.ssl.SSLException -> "falha de segurança HTTPS (certificado)"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
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
