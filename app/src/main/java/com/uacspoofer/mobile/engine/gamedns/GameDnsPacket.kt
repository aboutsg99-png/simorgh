package com.uacspoofer.mobile.engine.gamedns

/** Minimal IPv4/UDP handling for the DNS-only TUN: just enough to answer port 53. Pure. */
object GameDnsPacket {
    data class Query(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    /** The DNS payload of an IPv4 UDP packet to port 53, or null for anything else. */
    fun parse(packet: ByteArray, len: Int): Query? {
        if (len < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (packet[9].toInt() and 0xFF != 17) return null
        // Fragments are not DNS queries a phone sends.
        val flagsFrag = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        if (flagsFrag and 0x3FFF != 0) return null
        val total = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val end = minOf(total, len)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(packet, ihl + 4)
        val payloadEnd = minOf(end, ihl + udpLen)
        if (payloadEnd <= ihl + 8) return null
        return Query(
            srcIp = packet.copyOfRange(12, 16), dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort, dstPort = dstPort,
            payload = packet.copyOfRange(ihl + 8, payloadEnd),
        )
    }

    /** IPv4/UDP packet carrying [answer] back from the address the query was sent to. */
    fun response(q: Query, answer: ByteArray, answerLen: Int = answer.size): ByteArray {
        val udpLen = 8 + answerLen
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45; p[1] = 0
        put16(p, 2, total)
        put16(p, 4, 0); put16(p, 6, 0x4000) // id 0, DF
        p[8] = 64; p[9] = 17
        System.arraycopy(q.dstIp, 0, p, 12, 4)
        System.arraycopy(q.srcIp, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, q.dstPort)
        put16(p, 22, q.srcPort)
        put16(p, 24, udpLen)
        put16(p, 26, 0) // UDP checksum is optional over IPv4
        System.arraycopy(answer, 0, p, 28, answerLen)
        return p
    }

    internal fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
}
