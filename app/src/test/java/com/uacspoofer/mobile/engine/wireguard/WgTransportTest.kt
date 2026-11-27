package com.uacspoofer.mobile.engine.wireguard

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WgTransportTest {
    @Test fun frameIsLengthPrefixedBigEndian() {
        val f = Udp2TcpRelay.frame(byteArrayOf(1, 2, 3), 3)
        assertArrayEquals(byteArrayOf(0, 3, 1, 2, 3), f)
        val big = Udp2TcpRelay.frame(ByteArray(300), 300)
        assertEquals(1, big[0].toInt()); assertEquals(44, big[1].toInt() and 0xff)
    }

    @Test fun relayCarriesDatagramsBothWays() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val relay = Udp2TcpRelay("127.0.0.1", server.localPort)
            relay.start()
            val conn = server.accept()
            DatagramSocket().use { app ->
                app.soTimeout = 3_000
                val payload = "hello".toByteArray()
                app.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), relay.localPort))
                val input = DataInputStream(conn.getInputStream())
                val len = input.readUnsignedShort()
                val got = ByteArray(len).also { input.readFully(it) }
                assertEquals("hello", String(got))
                conn.getOutputStream().apply { write(Udp2TcpRelay.frame("pong".toByteArray(), 4)); flush() }
                val back = DatagramPacket(ByteArray(100), 100)
                app.receive(back)
                assertEquals("pong", String(back.data, 0, back.length))
            }
            relay.stop()
            conn.close()
        }
    }

    @Test fun tunMtuStaysWithinTunnelMtu() {
        fun t(mtu: Int) = WgTunnel("i", "n", "k", listOf("10.0.0.2/32"), mtu = mtu, peers = listOf(WgPeer("p", endpointHost = "1.1.1.1", endpointPort = 1)))
        assertEquals(1280, WgEndpoints.tunMtu(t(1280)))
        assertEquals(1420, WgEndpoints.tunMtu(t(1500)))
        assertEquals(1280, WgEndpoints.tunMtu(t(1000)))
        assertTrue(WgEndpoints.isLiteral("1.2.3.4"))
        assertTrue(WgEndpoints.isLiteral("[2001:db8::1]"))
        assertFalse(WgEndpoints.isLiteral("engage.cloudflareclient.com"))
    }
}
