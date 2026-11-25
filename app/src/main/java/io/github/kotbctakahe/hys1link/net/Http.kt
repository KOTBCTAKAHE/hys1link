package io.github.kotbctakahe.hys1link.net

import android.content.Context
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.R
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Link's servers are signed partly by public CAs and partly by private "CryptNet" CAs
 * that are bundled in the official app's whisper.store. We trust the union of both.
 */
object Http {
    @Volatile private var client: OkHttpClient? = null

    fun client(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: build(context.applicationContext).also { client = it }
    }

    private fun build(context: Context): OkHttpClient {
        val tm = CompositeTrustManager(listOf(systemTrustManager(), bundledTrustManager(context)))
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", LinkConfig.USER_AGENT)
                        .header("X-Signal-Agent", LinkConfig.SIGNAL_AGENT)
                        .build()
                )
            })
            .build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun bundledTrustManager(context: Context): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        context.resources.openRawResource(R.raw.link_trust).use { input ->
            cf.generateCertificates(input).forEachIndexed { i, c -> ks.setCertificateEntry("link$i", c) }
        }
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            delegates.first().checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var last: CertificateException? = null
            for (d in delegates) {
                try { d.checkServerTrusted(chain, authType); return } catch (e: CertificateException) { last = e }
            }
            throw last ?: CertificateException("Untrusted chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }
}
