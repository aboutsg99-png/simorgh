package com.uacspoofer.mobile.engine.gamedns

import java.nio.ByteBuffer
import kotlin.random.Random

/*
 * Game DNS: race public DNS
 * resolvers and use the fastest one that really answers for the game's domains, in a DNS-only
 * mode where only DNS goes through Simorgh and the game's own traffic stays direct (lowest ping).
 * No crowd statistics are collected: nothing about
 * the user's games or network leaves the phone.
 */

enum class DnsCategory(val fa: String) { GAMING_IR("بازی (ایران)"), BYPASS_IR("ضدتحریم (ایران)"), PUBLIC("عمومی"), USER("افزوده‌ی شما") }

data class DnsServer(val ip: String, val name: String, val category: DnsCategory)

data class DnsRaceResult(val server: DnsServer, val samplesMs: List<Long?>) {
    val ok: List<Long> get() = samplesMs.filterNotNull()
    val successRate: Double get() = if (samplesMs.isEmpty()) 0.0 else ok.size.toDouble() / samplesMs.size
    val medianMs: Long? get() = ok.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
    /** Lower is better; a resolver that drops answers is pushed back hard. */
    val cost: Double get() = medianMs?.let { it + (1.0 - successRate) * 1_000.0 } ?: Double.MAX_VALUE
}

object GameDns {
    /** Well-known public resolvers (no keys, no accounts). The user can add and remove. */
    val DEFAULTS = listOf(
        DnsServer("10.202.10.10", "Radar Game", DnsCategory.GAMING_IR),
        DnsServer("10.202.10.11", "Radar Game 2", DnsCategory.GAMING_IR),
        DnsServer("78.157.42.100", "Electro", DnsCategory.GAMING_IR),
        DnsServer("78.157.42.101", "Electro 2", DnsCategory.GAMING_IR),
        DnsServer("178.22.122.100", "Shecan", DnsCategory.BYPASS_IR),
        DnsServer("185.51.200.2", "Shecan 2", DnsCategory.BYPASS_IR),
        DnsServer("10.202.10.202", "403.online", DnsCategory.BYPASS_IR),
        DnsServer("10.202.10.102", "403.online 2", DnsCategory.BYPASS_IR),
        DnsServer("185.55.226.26", "Begzar", DnsCategory.BYPASS_IR),
        DnsServer("185.55.225.25", "Begzar 2", DnsCategory.BYPASS_IR),
        DnsServer("1.1.1.1", "Cloudflare", DnsCategory.PUBLIC),
        DnsServer("8.8.8.8", "Google", DnsCategory.PUBLIC),
        DnsServer("9.9.9.9", "Quad9", DnsCategory.PUBLIC),
    )

    /** Domains raced by default: big game platforms whose names sanctions-aware resolvers answer. */
    val DEFAULT_DOMAINS = listOf("api.steampowered.com", "epicgames.com", "pubgmobile.com", "callofduty.com", "ea.com")

    fun isIpv4(s: String) = s.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true } }

    /** "1.2.3.4 name" or "1.2.3.4", one per line; anything else is ignored. */
    fun parseUserList(text: String): List<DnsServer> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val ip = parts.firstOrNull()?.takeIf(::isIpv4) ?: return@mapNotNull null
        DnsServer(ip, parts.getOrNull(1)?.take(40)?.ifBlank { null } ?: ip, DnsCategory.USER)
    }.distinctBy { it.ip }.take(64).toList()

    fun rank(results: List<DnsRaceResult>): List<DnsRaceResult> =
        results.filter { it.ok.isNotEmpty() }.sortedBy { it.cost }

    // ── DNS wire format (A query) ───────────────────────────────────────────────────────────────

    fun query(id: Int, name: String): ByteArray {
        val labels = name.trim('.').split('.').filter { it.isNotEmpty() }
        require(labels.all { it.length in 1..63 }) { "bad name" }
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val b = ByteBuffer.allocate(size)
        b.putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0)
        labels.forEach { l -> b.put(l.length.toByte()); b.put(l.toByteArray(Charsets.US_ASCII)) }
        b.put(0).putShort(1).putShort(1)
        return b.array()
    }

    /** A usable answer to query [id]: matching id, response bit, RCODE 0, at least one answer. */
    fun isAnswer(bytes: ByteArray, len: Int, id: Int): Boolean {
        if (len < 12) return false
        val rid = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val qr = bytes[2].toInt() and 0x80 != 0
        val rcode = bytes[3].toInt() and 0x0F
        val an = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        return rid == (id and 0xFFFF) && qr && rcode == 0 && an > 0
    }

    fun newId(random: Random = Random.Default) = random.nextInt(1, 0xFFFF)
}
