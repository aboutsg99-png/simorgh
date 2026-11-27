package com.uacspoofer.mobile.engine.wireguard

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress

/**
 * UDP-over-TCP client (the framing Mullvad relays accept on TCP 443/80/5001): every UDP datagram
 * travels on one TCP connection prefixed with its length as a 16-bit big-endian integer.
 *
 * The WireGuard core sends its UDP to 127.0.0.1:[localPort]; this relay carries it to
 * [remoteHost]:[remotePort] over TCP and returns the answers. The app is excluded from its own
 * VPN, so the TCP socket uses the phone's real network. A dropped TCP connection is reopened on
 * the next datagram (WireGuard retries its handshake by itself).
 */
internal class Udp2TcpRelay(private val remoteHost: String, private val remotePort: Int) {
    private val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    val localPort: Int = udp.localPort
    @Volatile private var running = false
    @Volatile private var tcp: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var client: SocketAddress? = null
    private val lock = Any()

    /** Opens the TCP connection (fails fast when the relay port is unreachable) and starts relaying. */
    fun start(connectTimeoutMs: Int = 6_000) {
        running = true
        openTcp(connectTimeoutMs)
        Thread(::upLoop, "udp2tcp-up").apply { isDaemon = true; start() }
    }

    fun isRunning(): Boolean = running

    fun stop() {
        running = false
        runCatching { udp.close() }
        closeTcp()
    }

    private fun openTcp(timeoutMs: Int): Socket = synchronized(lock) {
        tcp?.takeIf { !it.isClosed }?.let { return it }
        val s = Socket(Proxy.NO_PROXY)
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(remoteHost, remotePort), timeoutMs)
        tcp = s
        out = s.getOutputStream()
        Thread({ downLoop(s) }, "udp2tcp-down").apply { isDaemon = true; start() }
        s
    }

    private fun closeTcp() = synchronized(lock) {
        runCatching { tcp?.close() }
        tcp = null
        out = null
    }

    private fun upLoop() {
        val buf = ByteArray(65_535)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                udp.receive(packet)
            } catch (_: IOException) {
                if (!running) return else continue
            }
            client = packet.socketAddress
            val frame = frame(buf, packet.length)
            try {
                val stream = out ?: run { openTcp(6_000); out } ?: continue
                synchronized(stream) { stream.write(frame); stream.flush() }
            } catch (_: IOException) {
                closeTcp()
            }
        }
    }

    private fun downLoop(socket: Socket) {
        val input = DataInputStream(socket.getInputStream().buffered(65_536))
        val buf = ByteArray(65_535)
        try {
            while (running && !socket.isClosed) {
                val len = input.readUnsignedShort()
                input.readFully(buf, 0, len)
                val to = client ?: continue
                udp.send(DatagramPacket(buf, len, to))
            }
        } catch (_: IOException) {
        } finally {
            if (tcp === socket) closeTcp()
        }
    }

    companion object {
        /** Length-prefixed frame of one datagram. */
        fun frame(payload: ByteArray, length: Int): ByteArray {
            require(length in 0..65_535) { "datagram too large" }
            val out = ByteArray(length + 2)
            out[0] = (length ushr 8).toByte()
            out[1] = length.toByte()
            System.arraycopy(payload, 0, out, 2, length)
            return out
        }
    }
}
