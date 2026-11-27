package com.uacspoofer.mobile.vpn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small HTTPS GETs through the tunnel's local SOCKS port with the hostname resolved by the
 * far end of the tunnel.
 *
 * Android's built-in HttpURLConnection (an old OkHttp 2 fork) resolves the hostname with the
 * system resolver even when a SOCKS proxy is set. Simorgh excludes itself from its own VPN,
 * so that resolver query went straight to the ISP: every exit-IP / country check leaked a
 * DNS lookup outside the tunnel. OkHttp 4 hands SOCKS an unresolved address (SOCKS5 domain
 * request) and [NoLocalDns] makes any local lookup fail loudly instead of leaking.
 */
object TunnelHttp {
    object NoLocalDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("local DNS disabled for tunnel requests: $hostname")
    }

    fun client(socksHost: String, socksPort: Int, timeoutMs: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(socksHost, socksPort)))
        .dns(NoLocalDns)
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs.toLong() * 2, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    /** GET [url] through the SOCKS proxy; returns at most [maxBytes] of the body. */
    fun get(url: String, socksHost: String, socksPort: Int, timeoutMs: Int, maxBytes: Int = 16 * 1024, userAgent: String = "Simorgh/0.1"): String {
        val http = client(socksHost, socksPort, timeoutMs)
        try {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json,text/plain")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", userAgent)
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val source = response.body?.source() ?: error("Empty response")
                source.request(maxBytes.toLong())
                val buffer = source.buffer
                return buffer.readString(minOf(buffer.size, maxBytes.toLong()), Charsets.UTF_8)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    /** SOCKS proxy address of a [Proxy], or null when it is not a SOCKS proxy. */
    fun socksOf(proxy: Proxy): InetSocketAddress? =
        (proxy.address() as? InetSocketAddress)?.takeIf { proxy.type() == Proxy.Type.SOCKS }
}
