package com.uacspoofer.mobile.engine.foxy

/** Which measured Mozilla edges, and which HTTP versions on each, a connect tries. Pure. */
internal object FoxyPathPlan {
    /**
     * Indexes into [ranked] (already best first): up to two edges whose TLS handshake completed,
     * then one more edge that only answered TCP or QUIC. With no TLS edge at all, up to two of
     * the others.
     */
    fun pickServers(ranked: List<FoxyPingResult>): List<Int> {
        val tls = ranked.indices.filter { ranked[it].reachable }
        val rest = ranked.indices.filter { !ranked[it].reachable && ranked[it].answered }
        return if (tls.isEmpty()) rest.take(2) else tls.take(2) + rest.take(1)
    }

    /**
     * Versions for one edge. TLS completed: the user's order. TCP answered but TLS was reset:
     * HTTP/3 first (QUIC is a different handshake on UDP), then the TCP versions once.
     * Only QUIC answered: HTTP/3 only.
     */
    fun versionsFor(ping: FoxyPingResult, order: List<Int>): List<Int> = when {
        ping.reachable -> order
        ping.tcpMs == null -> order.filter { it == 3 }.ifEmpty { order }
        else -> (order.filter { it == 3 } + order.filter { it != 3 }).distinct()
    }
}
