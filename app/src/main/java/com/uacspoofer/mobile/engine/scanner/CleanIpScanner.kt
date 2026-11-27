package com.uacspoofer.mobile.engine.scanner

import kotlin.random.Random

/*
 * Clean-IP scanner for Cloudflare-fronted configs. Uses a
 * «same clean IP» step; the code is
 * Simorgh's. Pure Kotlin here (ranges, sampling, scoring), unit-tested; the probe is Android.
 */

/** One measured address: every round of TCP connect, TLS handshake and a real HTTP answer. */
data class CleanIpSample(
    val ip: String,
    val tcpMs: List<Long?>,
    val tlsMs: List<Long?>,
    /** Cloudflare's own answer (`/cdn-cgi/trace`) through that address and SNI; null when none. */
    val httpMs: List<Long?>,
    /** Cloudflare data center the address answered from (`colo=`), e.g. FRA. */
    val colo: String? = null,
) {
    val rounds: Int get() = maxOf(tcpMs.size, tlsMs.size, httpMs.size)
    val okHttp: List<Long> get() = httpMs.filterNotNull()
    val successRate: Double get() = if (rounds == 0) 0.0 else okHttp.size.toDouble() / rounds
    val medianMs: Long? get() = okHttp.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
    val jitterMs: Long? get() = okHttp.takeIf { it.size >= 2 }?.let { it.max() - it.min() }
}

data class CleanIpResult(val sample: CleanIpSample, val score: Double)

object CleanIpRanges {
    /** Cloudflare's published IPv4 ranges (cloudflare.com/ips-v4). */
    val V4 = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    /** Cloudflare's published IPv6 ranges (cloudflare.com/ips-v6), the /32 that carries the CDN first. */
    val V6 = listOf("2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32")

    /** Ranges the user may add (another CDN, a list from a friend). Bad lines are ignored. */
    fun parseUser(text: String): List<String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .filter { cidr4(it) != null || isIpv4(it) || (it.contains(':') && it.contains('/')) }
        .distinct().take(256).toList()

    fun isIpv4(s: String): Boolean = s.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true } }

    internal fun cidr4(cidr: String): Pair<Long, Int>? {
        val parts = cidr.split('/')
        if (parts.size != 2 || !isIpv4(parts[0])) return null
        val bits = parts[1].toIntOrNull()?.takeIf { it in 8..32 } ?: return null
        val base = parts[0].split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (base and mask) to bits
    }

    /**
     * [count] distinct IPv4 addresses, at most one per /24 (an operator filters by /24 more often
     * than by single address, so spreading across /24s finds clean blocks faster), weighted by the
     * size of each range. Plain IPs in [ranges] are taken as they are.
     */
    fun sampleV4(ranges: List<String>, count: Int, random: Random = Random.Default): List<String> {
        val literal = ranges.filter(::isIpv4)
        val cidrs = ranges.mapNotNull(::cidr4)
        val out = LinkedHashSet<String>(literal.take(count))
        if (cidrs.isEmpty()) return out.toList()
        val weights = cidrs.map { (_, bits) -> 1L shl (32 - bits) }
        val total = weights.sum()
        val seen24 = HashSet<Long>()
        var guard = 0
        while (out.size < count && guard++ < count * 20) {
            var r = random.nextLong(total)
            var i = 0
            while (r >= weights[i]) { r -= weights[i]; i++ }
            val (base, bits) = cidrs[i]
            val size = 1L shl (32 - bits)
            val ip = base + random.nextLong(size)
            val block = ip shr 8
            val host = ip and 0xFF
            if (host == 0L || host == 255L) continue
            if (!seen24.add(block) && seen24.size < total / 256) continue
            out += listOf(ip shr 24, (ip shr 16) and 0xFF, (ip shr 8) and 0xFF, host).joinToString(".")
        }
        return out.toList()
    }

    /** Random addresses inside the first /48s of Cloudflare's IPv6 CDN range. */
    fun sampleV6(count: Int, random: Random = Random.Default): List<String> = List(count) {
        val a = random.nextInt(0, 0x10000)
        val b = random.nextInt(0, 0x10000)
        "2606:4700:${Integer.toHexString(random.nextInt(0, 0x100))}::${Integer.toHexString(a)}:${Integer.toHexString(b)}"
    }.distinct()
}

object CleanIpScoring {
    /**
     * 0..100, higher is better. Answered share counts most (an IP that answers 2 of 3 times breaks
     * pages), then the median delay, then how little it wanders. A sample that never got Cloudflare's
     * real answer scores 0 however fast its TCP was: the filter often lets TCP through and kills TLS.
     */
    fun score(s: CleanIpSample): Double {
        val median = s.medianMs ?: return 0.0
        val success = s.successRate
        val delay = 1.0 - (median.coerceAtMost(3_000L) / 3_000.0)
        val jitter = s.jitterMs?.let { 1.0 - (it.coerceAtMost(1_000L) / 1_000.0) } ?: 0.6
        return 100.0 * (0.5 * success + 0.35 * delay + 0.15 * jitter)
    }

    fun rank(samples: List<CleanIpSample>): List<CleanIpResult> =
        samples.map { CleanIpResult(it, score(it)) }.filter { it.score > 0.0 }
            .sortedWith(compareByDescending<CleanIpResult> { it.score }.thenBy { it.sample.medianMs ?: Long.MAX_VALUE })

    /** Cloudflare trace body -> (is Cloudflare, colo). */
    fun parseTrace(body: String): Pair<Boolean, String?> {
        val kv = body.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        val colo = kv["colo"]?.takeIf { it.length in 3..4 && it.all(Char::isLetter) }?.uppercase()
        return (kv.containsKey("fl") || kv.containsKey("colo")) to colo
    }
}
