package com.uacspoofer.mobile.engine.gamedns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDnsTest {
    @Test
    fun buildsAStandardAQuery() {
        val q = GameDns.query(0x1234, "api.steampowered.com")
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(1, q[5].toInt()) // QDCOUNT
        assertEquals(3, q[12].toInt()) // "api"
        assertEquals(12 + 22 + 4, q.size)
    }

    @Test
    fun recognisesOnlyRealAnswers() {
        val q = GameDns.query(7, "ea.com")
        val answer = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 }
        assertTrue(GameDns.isAnswer(answer, answer.size, 7))
        assertFalse(GameDns.isAnswer(answer, answer.size, 8))
        val nx = answer.copyOf().also { it[3] = 0x83.toByte() }
        assertFalse(GameDns.isAnswer(nx, nx.size, 7))
        assertFalse(GameDns.isAnswer(q, q.size, 7))
    }

    @Test
    fun parsesUserListAndRanksByCost() {
        val list = GameDns.parseUserList("1.2.3.4 My DNS\n999.1.1.1\n5.6.7.8\n")
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), list.map { it.ip })
        assertEquals("My DNS", list.first().name)
        val fast = DnsRaceResult(list[0], listOf(30, 32, 31))
        val lossy = DnsRaceResult(list[1], listOf(10, null, null))
        val dead = DnsRaceResult(GameDns.DEFAULTS.first(), listOf(null, null))
        assertEquals(listOf(fast, lossy), GameDns.rank(listOf(dead, lossy, fast)))
    }

    @Test
    fun packetRoundTripSwapsAddressesAndKeepsTheAnswer() {
        val dns = GameDns.query(42, "callofduty.com")
        val ip = udpPacket(byteArrayOf(10, 111, 222.toByte(), 1), byteArrayOf(10, 111, 222.toByte(), 2), 40000, 53, dns)
        val q = GameDnsPacket.parse(ip, ip.size)!!
        assertArrayEquals(dns, q.payload)
        assertEquals(40000, q.srcPort)
        val resp = GameDnsPacket.response(q, dns)
        val back = GameDnsPacket.parse(swapPorts(resp), resp.size)
        assertArrayEquals(dns, back!!.payload)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), resp.copyOfRange(12, 16))
        assertEquals(0, GameDnsPacket.checksum(resp, 0, 20))
        assertNull(GameDnsPacket.parse(udpPacket(byteArrayOf(1, 1, 1, 1), byteArrayOf(2, 2, 2, 2), 1, 443, dns), 48))
    }

    private fun swapPorts(p: ByteArray): ByteArray = p.copyOf().also {
        // response goes 53 -> client; make it look like a query again for parse()
        it[22] = 0; it[23] = 53
    }

    private fun udpPacket(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total ushr 8).toByte(); p[3] = total.toByte(); p[8] = 64; p[9] = 17
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4)
        p[20] = (sport ushr 8).toByte(); p[21] = sport.toByte(); p[22] = (dport ushr 8).toByte(); p[23] = dport.toByte()
        p[24] = ((8 + payload.size) ushr 8).toByte(); p[25] = (8 + payload.size).toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }
}
