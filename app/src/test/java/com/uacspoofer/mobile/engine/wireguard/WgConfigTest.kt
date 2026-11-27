package com.uacspoofer.mobile.engine.wireguard

import com.uacspoofer.mobile.profiles.Base64Codec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class WgConfigTest {
    private val priv = WgKeys.generate().privateKey
    private val peerPub = WgKeys.generate().publicKey

    private fun conf(extra: String = "", endpoint: String = "185.159.157.10:51820") = """
        [Interface]
        # Key for Simorgh
        PrivateKey = $priv
        Address = 10.2.0.2/32, fd00::2/128
        DNS = 10.2.0.1
        MTU = 1420
        $extra

        [Peer]
        # NL-FREE#12
        PublicKey = $peerPub
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = $endpoint
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun parsesWireGuardIni() {
        val t = WgConfigParser.parseIni(conf())
        assertEquals(priv, t.privateKey)
        assertEquals(listOf("10.2.0.2/32", "fd00::2/128"), t.addresses)
        assertEquals(listOf("10.2.0.1"), t.dns)
        assertEquals(1420, t.mtu)
        assertEquals("185.159.157.10", t.primary.endpointHost)
        assertEquals(51820, t.primary.endpointPort)
        assertEquals(25, t.primary.keepAliveSeconds)
        assertTrue(t.hasIpv6)
        assertFalse(t.toString().contains(priv))
    }

    @Test
    fun parsesIpv6EndpointAndPrefixlessAddress() {
        val t = WgConfigParser.parseIni(conf(endpoint = "[2a01:4f8::1]:2408").replace("10.2.0.2/32, fd00::2/128", "172.16.0.2"))
        assertEquals("2a01:4f8::1", t.primary.endpointHost)
        assertEquals(2408, t.primary.endpointPort)
        assertEquals("[2a01:4f8::1]:2408", t.primary.endpoint)
        assertEquals(listOf("172.16.0.2/32"), t.addresses)
    }

    @Test
    fun parsesSeveralConfigsInOneText() {
        val all = WgConfigParser.parseAll(conf() + "\n\n" + conf(endpoint = "1.2.3.4:443"))
        assertEquals(2, all.size)
        assertEquals("1.2.3.4", all[1].primary.endpointHost)
    }

    @Test
    fun parsesShareLinkWithReserved() {
        val link = "wireguard://${java.net.URLEncoder.encode(priv, "UTF-8")}@engage.cloudflareclient.com:2408" +
            "?publickey=${java.net.URLEncoder.encode(peerPub, "UTF-8")}&address=172.16.0.2/32,2606:4700:110::2/128&mtu=1280&reserved=1,2,3#WARP%20DE"
        val t = WgConfigParser.parseLink(link)
        assertEquals("WARP DE", t.name)
        assertEquals(listOf(1, 2, 3), t.reserved)
        assertEquals("engage.cloudflareclient.com", t.primary.endpointHost)
        assertEquals(priv, t.privateKey)
        assertEquals("DE", t.countryCode)
    }

    @Test
    fun refusesRealAmneziaWgButAcceptsNeutralValues() {
        try {
            WgConfigParser.parseIni(conf("Jc = 4\nJmin = 40\nJmax = 70\nS1 = 0\nS2 = 0\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4"))
            fail("AmneziaWG with junk packets must be refused")
        } catch (e: WgParseException) {
            assertTrue(e.message!!.contains("Amnezia"))
        }
        val neutral = WgConfigParser.parseIni(conf("Jc = 0\nJmin = 0\nJmax = 0\nS1 = 0\nS2 = 0\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4"))
        assertNotNull(neutral)
    }

    @Test
    fun rejectsBadKeysAndMissingParts() {
        listOf(
            conf().replace(priv, "not-a-key"),
            conf().replace("Endpoint = 185.159.157.10:51820", ""),
            conf().substringBefore("[Peer]"),
        ).forEach { bad ->
            try { WgConfigParser.parseIni(bad); fail("should fail: $bad") } catch (_: WgParseException) {}
        }
    }

    @Test
    fun iniRoundTrips() {
        val t = WgConfigParser.parseIni(conf())
        val back = WgConfigParser.parseIni(WgConfigParser.toIni(t))
        assertEquals(t.copy(id = back.id, name = back.name, countryCode = back.countryCode, addedAt = back.addedAt), back)
    }

    @Test
    fun x25519PublicKeyMatchesRfc7748Vector() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertEquals(Base64Codec.encode(alicePub), WgKeys.publicKey(Base64Codec.encode(alicePriv)))
    }

    @Test
    fun ed25519SeedTwinIsClamped() {
        val x = Base64Codec.decode(WgKeys.x25519FromEd25519Seed(ByteArray(32) { it.toByte() }))
        assertEquals(32, x.size)
        assertEquals(0, x[0].toInt() and 7)
        assertEquals(64, x[31].toInt() and 0xC0)
    }

    @Test
    fun xrayConfigHasUserspaceWireGuardAndTunnelDns() {
        val t = WgConfigParser.parseIni(conf()).copy(addresses = listOf("10.2.0.2/32"))
        val json = JSONObject(WgXrayConfig.build(t, WgXrayConfig.Options(socksPort = 10999)))
        val wg = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", wg.getString("protocol"))
        val settings = wg.getJSONObject("settings")
        assertTrue(settings.getBoolean("noKernelTun"))
        assertEquals("ForceIPv4", settings.getString("domainStrategy"))
        assertEquals("185.159.157.10:51820", settings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals("10.2.0.1", json.getJSONObject("dns").getJSONArray("servers").getString(0))
        val rules = json.getJSONObject("routing").getJSONArray("rules")
        // The tunnel's private DNS (10.2.0.1) rides the tunnel: its rule comes before the LAN rule.
        val dnsRule = (0 until rules.length()).indexOfFirst { rules.getJSONObject(it).optJSONArray("ip")?.optString(0) == "10.2.0.1" }
        val lanRule = (0 until rules.length()).indexOfFirst { rules.getJSONObject(it).optJSONArray("ip")?.optString(0) == "10.0.0.0/8" }
        assertTrue(dnsRule in 0 until lanRule)
        assertEquals("proxy", rules.getJSONObject(dnsRule).getString("outboundTag"))
        assertEquals(10999, json.getJSONArray("inbounds").getJSONObject(0).getInt("port"))
    }

    @Test
    fun pausedConfigSendsEverythingDirect() {
        val t = WgConfigParser.parseIni(conf())
        val json = JSONObject(WgXrayConfig.build(t, WgXrayConfig.Options(socksPort = 1, paused = true)))
        val rules = json.getJSONObject("routing").getJSONArray("rules")
        val last = rules.getJSONObject(rules.length() - 1)
        assertEquals("direct", last.getString("outboundTag"))
    }

    @Test
    fun storeCodecRoundTrips() {
        val t = WgConfigParser.parseIni(conf()).copy(source = WgSource.PROTON, countryCode = "NL", server = "NL-FREE#12")
        val lib = WgLibrary(listOf(t), t.id, mapOf(t.id to WgResult(true, 88, 5L)))
        assertEquals(lib, WgTunnelStore.decode(WgTunnelStore.encode(lib)))
    }

    @Test
    fun protonPickKeepsTheDashboardFilterAndSpreadsCountries() {
        fun logical(name: String, cc: String, tier: Int, features: Int, score: Double, key: String = peerPub) = JSONObject()
            .put("Name", name).put("ExitCountry", cc).put("Tier", tier).put("Features", features).put("Score", score).put("Status", 1)
            .put("Servers", org.json.JSONArray().put(JSONObject().put("EntryIP", "10.0.0.${name.length}").put("X25519PublicKey", key).put("Label", "1").put("Status", 1)))
        val list = org.json.JSONArray()
            .put(logical("NL-FREE#1", "NL", 0, 0, 1.0))
            .put(logical("NL-FREE#22", "NL", 0, 0, 0.5))
            .put(logical("US-FREE#3", "US", 0, 0, 2.0))
            .put(logical("CH-SC#1", "CH", 0, 1, 0.1)) // Secure Core: out
            .put(logical("DE#5", "DE", 2, 0, 0.1)) // paid tier: out
            .put(logical("JP-FREE#1", "JP", 0, 0, 0.2, key = "")) // no WireGuard key: out
        val picks = ProtonVpn.pick(list, maxTier = 0, max = 10, random = kotlin.random.Random(1))
        assertEquals(listOf("NL-FREE#22", "US-FREE#3", "NL-FREE#1"), picks.map { it.name })
        val t = ProtonVpn.tunnelFor(priv, picks.first(), 0)
        assertEquals(WgSource.PROTON, t.source)
        assertEquals("NL", t.countryCode)
        assertEquals(listOf("10.2.0.1"), t.dns)
        assertEquals(51820, t.primary.endpointPort)
    }

    @Test
    fun protonSessionCookiesAreFound() {
        assertEquals(listOf("abc", "def"), ProtonVpn.parseSessionUids("Session-Id=x; AUTH-abc=1; REFRESH-abc=2; AUTH-def=3; AUTH-=4"))
    }

    @Test
    fun amneziaKeyWithWireGuardAndXrayContainers() {
        val wgConf = conf()
        val xray = JSONObject().put("outbounds", org.json.JSONArray().put(JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", org.json.JSONArray().put(JSONObject().put("address", "203.0.113.5").put("port", 443)
                .put("users", org.json.JSONArray().put(JSONObject().put("id", "4e4f1f70-5e56-4a76-bb9d-db087f4690c4").put("flow", "xtls-rprx-vision"))))))
            .put("streamSettings", JSONObject().put("network", "tcp").put("security", "reality")
                .put("realitySettings", JSONObject().put("serverName", "www.example.com").put("publicKey", "pbk").put("shortId", "ab").put("fingerprint", "chrome")))))
        val doc = JSONObject().put("description", "My server").put("containers", org.json.JSONArray()
            .put(JSONObject().put("container", "amnezia-wireguard").put("wireguard", JSONObject().put("last_config", JSONObject().put("config", wgConf).toString())))
            .put(JSONObject().put("container", "amnezia-xray").put("xray", JSONObject().put("last_config", xray.toString())))
            .put(JSONObject().put("container", "amnezia-openvpn")))
        val key = "vpn://" + Base64Codec.encode(qCompress(doc.toString().toByteArray())).replace('+', '-').replace('/', '_').trimEnd('=')
        val out = AmneziaImport.expand(key)!!
        assertEquals("My server", out.name)
        assertEquals(1, out.wireGuardConfigs.size)
        assertEquals(1, WgConfigParser.parseAll(out.wireGuardConfigs.first()).size)
        assertEquals(1, out.xrayLinks.size)
        val link = out.xrayLinks.first()
        assertTrue(link, link.startsWith("vless://4e4f1f70-5e56-4a76-bb9d-db087f4690c4@203.0.113.5:443?"))
        assertTrue(link.contains("security=reality") && link.contains("pbk=pbk") && link.contains("flow=xtls-rprx-vision"))
        assertEquals(listOf("amnezia-openvpn"), out.unsupported)
    }

    private fun qCompress(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        return out.toByteArray()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
