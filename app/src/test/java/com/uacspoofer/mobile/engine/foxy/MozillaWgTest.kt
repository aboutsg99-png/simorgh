package com.uacspoofer.mobile.engine.foxy

import com.uacspoofer.mobile.engine.wireguard.WgSource
import com.uacspoofer.mobile.engine.wireguard.WgXrayConfig
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MozillaWgTest {
    private val key = "A".repeat(43) + "="
    private val servers = """
        {"countries":[{"name":"Germany","code":"de","cities":[{"name":"Frankfurt","code":"fra","servers":[
          {"hostname":"de-fra-wg-001","ipv4_addr_in":"185.0.0.1","ipv6_addr_in":"2a03::1","public_key":"$key","weight":100,
           "port_ranges":[[53,53],[4000,33433],[33565,51820],[52000,60000]],"ipv4_gateway":"10.64.0.1"},
          {"hostname":"bad","ipv4_addr_in":"","public_key":"$key"}]}]},
         {"name":"Netherlands","code":"nl","cities":[{"name":"Amsterdam","code":"ams","servers":[
          {"hostname":"nl-ams-wg-001","ipv4_addr_in":"185.0.0.2","public_key":"$key","weight":50,"port_ranges":[]}]}]}]}
    """.trimIndent()

    @Test fun parsesRelaysAndSkipsBrokenOnes() {
        val list = MozillaWg.parseServers(servers)
        assertEquals(2, list.size)
        val de = list.first()
        assertEquals("DE", de.countryCode)
        assertEquals("10.64.0.1", de.ipv4Gateway)
        assertEquals(4, de.portRanges.size)
        assertEquals("10.64.0.1", list[1].ipv4Gateway) // default gateway
    }

    @Test fun pickServersPrefersCountryAndFallsBackToAll() {
        val list = MozillaWg.parseServers(servers)
        assertEquals(listOf("nl-ams-wg-001"), MozillaWg.pickServers(list, "NL", 2, Random(1)).map { it.hostname })
        assertEquals(2, MozillaWg.pickServers(list, "REC", 5, Random(1)).size)
    }

    @Test fun udpPortsStartWithStandardThenDns() {
        val de = MozillaWg.parseServers(servers).first()
        val ports = MozillaWg.udpPorts(de, Random(3))
        assertEquals(51820, ports[0])
        assertEquals(53, ports[1])
        assertTrue(ports.all { p -> de.portRanges.any { p in it } })
    }

    @Test fun routesPerTransport() {
        val de = MozillaWg.parseServers(servers).first()
        assertEquals(listOf(443, 80), MozillaWg.routes(FoxyTransport.WG_TCP, de).map { it.port })
        val ss = MozillaWg.routes(FoxyTransport.WG_SS, de, Random(2)).single()
        assertTrue(ss.port in MozillaWg.SS_PORTS)
        assertTrue(MozillaWg.routes(FoxyTransport.MASQUE, de).isEmpty())
        assertEquals(listOf(FoxyTransport.WIREGUARD, FoxyTransport.WG_TCP, FoxyTransport.WG_SS), FoxyTransport.AUTO.wireGuardOrder)
        assertTrue(FoxyTransport.MASQUE.wireGuardOrder.isEmpty())
        assertEquals(FoxyTransport.MASQUE, FoxyTransport.from(null))
    }

    @Test fun tunnelPointsAtRelayOrLoopback() {
        val de = MozillaWg.parseServers(servers).first()
        val device = MozillaWgDevice("pub", "10.66.1.2/32", "fc00:bbbb::2/128")
        val tcp = MozillaWg.tunnel(MozillaWg.routes(FoxyTransport.WG_TCP, de).first(), key, device, localUdpPort = 40000)
        assertEquals("127.0.0.1", tcp.primary.endpointHost)
        assertEquals(40000, tcp.primary.endpointPort)
        assertEquals(WgSource.MOZILLA, tcp.source)
        assertEquals(listOf("10.64.0.1"), tcp.dns)
        val ss = MozillaWg.tunnel(MozillaWg.routes(FoxyTransport.WG_SS, de).first(), key, device)
        assertEquals("185.0.0.1", ss.primary.endpointHost)
        assertEquals(51820, ss.primary.endpointPort)
        val plain = MozillaWg.tunnel(MozillaWg.routes(FoxyTransport.WIREGUARD, de).first(), key, MozillaWgDevice("p", "10.66.1.2", ""))
        assertEquals(listOf("10.66.1.2/32"), plain.addresses)
    }

    @Test fun shadowsocksBridgeChainsTheWireGuardOutbound() {
        val de = MozillaWg.parseServers(servers).first()
        val t = MozillaWg.tunnel(MozillaWg.routes(FoxyTransport.WG_SS, de).first(), key, MozillaWgDevice("p", "10.66.1.2/32", ""))
        val json = JSONObject(WgXrayConfig.build(t, WgXrayConfig.Options(socksPort = 1080, shadowsocks = WgXrayConfig.SsBridge("185.0.0.1", 51901, "aes-256-gcm", "mullvad"))))
        val outs = json.getJSONArray("outbounds")
        val wg = outs.getJSONObject(0)
        assertEquals("ss-bridge", wg.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        val ss = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.getString("tag") == "ss-bridge" }
        assertEquals("shadowsocks", ss.getString("protocol"))
        val plain = JSONObject(WgXrayConfig.build(t, WgXrayConfig.Options(socksPort = 1080)))
        assertFalse(plain.getJSONArray("outbounds").getJSONObject(0).has("streamSettings"))
    }

    @Test fun pkceAndRedirectCode() {
        val v = MozillaWg.newVerifier()
        assertTrue(v.length >= 43 && v.none { it == '=' || it == '+' || it == '/' })
        // RFC 7636 appendix B vector.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", MozillaWg.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(MozillaWg.loginUrl(v).startsWith("https://vpn.mozilla.org/api/v2/vpn/login/android?code_challenge="))
        assertEquals("abc123", MozillaWg.codeFromRedirect("https://vpn.mozilla.org/vpn/client/login/success?code=abc123"))
        assertNull(MozillaWg.codeFromRedirect("https://evil.example/vpn/client/login/success?code=abc"))
        assertNull(MozillaWg.codeFromRedirect("https://vpn.mozilla.org/other?code=abc"))
    }

    @Test fun deviceParsing() {
        val d = MozillaWg.parseDevice("""{"name":"x","pubkey":"k","ipv4_address":"10.1.2.3/32","ipv6_address":"fc00::3/128"}""")
        assertEquals("10.1.2.3/32", d.ipv4)
        val acc = """{"devices":[{"pubkey":"other","ipv4_address":"1"},{"pubkey":"k","ipv4_address":"10.9.9.9/32","ipv6_address":""}]}"""
        assertEquals("10.9.9.9/32", MozillaWg.deviceFromAccount(acc, "k")!!.ipv4)
        assertNull(MozillaWg.deviceFromAccount(acc, "none"))
    }
}
