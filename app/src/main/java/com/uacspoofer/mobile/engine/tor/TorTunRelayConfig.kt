package com.uacspoofer.mobile.engine.tor

internal object TorTunRelayConfig {
    const val MAP_DNS = "198.18.0.2"
    const val FAKE_NET = "100.64.0.0"
    const val FAKE_MASK = "255.192.0.0"
    const val UDP_TIMEOUT_MS = 15_000
    const val UDP_BLOCKED_TIMEOUT_MS = 1_000
    const val DEFAULT_TCP_BUFFER = 65_536
    const val BIG_TCP_BUFFER = 262_144

    /**
     * MTU of a TUN that hev-socks5-tunnel terminates locally (Tor, Rubah, WireGuard-via-Xray).
     * Those packets never leave the phone as-is: hev rebuilds each TCP stream into the SOCKS
     * connection, so the outer path MTU does not apply. 1500 instead of the app default 1280
     * means ~17% fewer packets (and JNI reads/writes) per byte through the userspace stack;
     * still the Ethernet size, so UDP datagrams forwarded through SOCKS stay unfragmented.
     */
    const val RELAY_MTU = 1_500

    fun relayMtu(userChoice: Int? = null): Int =
        userChoice?.takeIf { it in 1280..1500 } ?: RELAY_MTU

    fun yaml(
        mtu: Int,
        socksPort: Int,
        tunIpv4: String = "198.18.0.1",
        mapDns: String = MAP_DNS,
        blockUdp: Boolean = true,
        bigBuffers: Boolean = false,
    ): String {
        // Tor SOCKS has no UDP ASSOCIATE: with blockUdp the dead UDP sessions are freed in 1 s
        // instead of 15 s so QUIC apps fall back to TCP sooner and hev holds fewer sessions.
        val udpTimeout = if (blockUdp) UDP_BLOCKED_TIMEOUT_MS else UDP_TIMEOUT_MS
        val safeMtu = mtu.coerceIn(1280, 1500)
        val safePort = socksPort.coerceIn(1024, 65_535)
        return """
            tunnel:
              name: tun0
              mtu: $safeMtu
              ipv4: '$tunIpv4'
              icmp: 'off'
            socks5:
              port: $safePort
              address: '127.0.0.1'
              udp: 'udp'
            mapdns:
              address: '$mapDns'
              port: 53
              network: '$FAKE_NET'
              netmask: '$FAKE_MASK'
              cache-size: 10000
            misc:
              log-level: warn
              connect-timeout: 30000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: $udpTimeout
              max-session-count: 0
              tcp-buffer-size: ${if (bigBuffers) BIG_TCP_BUFFER else DEFAULT_TCP_BUFFER}
        """.trimIndent() + "\n"
    }
}
