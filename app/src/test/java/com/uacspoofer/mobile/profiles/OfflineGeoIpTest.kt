package com.uacspoofer.mobile.profiles

import java.io.File
import java.net.InetAddress
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineGeoIpTest {
    @Test fun parsesAndLooksUpRanges() {
        val db = OfflineGeoIp.parse(
            sequenceOf("# comment", "15726992,15726999,??", "16777216,16777471,AU", "100663296,135630591,US", "3758096384,4294967295,ZZ"),
        )
        assertEquals("US", db.lookup(InetAddress.getByName("8.8.8.8")))
        assertEquals("AU", db.lookup(InetAddress.getByName("1.0.0.1")))
        assertEquals("ZZ", db.lookup(InetAddress.getByName("255.255.255.254"))) // unsigned compare
        assertNull(db.lookup(InetAddress.getByName("2.2.2.2")))
        assertNull(db.lookup(InetAddress.getByName("::1")))
    }

    @Test fun bundledTorDatabaseResolvesOffline() {
        val file = listOf(File("src/main/assets/tor/geoip.gz"), File("app/src/main/assets/tor/geoip.gz")).first { it.isFile }
        val db = GZIPInputStream(file.inputStream()).bufferedReader().use { OfflineGeoIp.parse(it.lineSequence()) }
        assertTrue(db.size > 100_000)
        assertEquals("US", db.lookup(InetAddress.getByName("8.8.8.8")))
        assertEquals("IR", db.lookup(InetAddress.getByName("5.160.0.1")))
    }

    @Test fun ipLiteralDetection() {
        assertTrue(ProfileCountryRepository.isIpLiteral("104.16.1.1"))
        assertTrue(ProfileCountryRepository.isIpLiteral("[2606:4700::1]"))
        assertFalse(ProfileCountryRepository.isIpLiteral("example.com"))
    }
}
