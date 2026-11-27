package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionInfoTest {
    private val gb = 1L shl 30

    @Test fun userInfoHeaderParsesAllFields() {
        val info = SubscriptionUserInfo.parse("upload=1073741824; download=2147483648; total=10737418240; expire=1798761600")!!
        assertEquals(gb, info.upload)
        assertEquals(2 * gb, info.download)
        assertEquals(10 * gb, info.total)
        assertEquals(1798761600L, info.expire)
        assertEquals(3 * gb, info.used)
        assertEquals(7 * gb, info.remaining)
    }

    @Test fun userInfoHeaderToleratesEmptyAndMissingValues() {
        val info = SubscriptionUserInfo.parse("upload=0; download=5; total=0; expire=")!!
        assertTrue(info.unlimited)
        assertTrue(info.noExpiry)
        assertNull(SubscriptionUserInfo.parse("garbage"))
        assertNull(SubscriptionUserInfo.parse(null))
    }

    @Test fun remarkTrafficPairAndPersianDigits() {
        val r = SubscriptionRemarkInfo.parse(listOf("📊 ۳٫۵ GB / ۵۰ GB", "DE-1"))!!
        assertEquals((3.5 * gb).toLong(), r.usedBytes)
        assertEquals(50 * gb, r.totalBytes)
    }

    @Test fun remarkRemainingAndDays() {
        val now = 1_800_000_000_000L
        val r = SubscriptionRemarkInfo.parse(listOf("حجم باقیمانده: 12.5 GB", "⏳ 25 روز"), now)!!
        assertEquals((12.5 * gb).toLong(), r.remainingBytes)
        assertEquals(now / 1000 + 25 * 86_400L, r.expire)
    }

    @Test fun remarkJalaliAndGregorianDates() {
        val jalali = SubscriptionRemarkInfo.parse(listOf("انقضا: 1405/09/10"))!!
        val gregorian = SubscriptionRemarkInfo.parse(listOf("Expire: 2026-12-01"))!!
        assertEquals(gregorian.expire, jalali.expire)
        assertEquals(Triple(2026, 3, 21), SubscriptionRemarkInfo.jalaliToGregorian(1405, 1, 1))
    }

    @Test fun plainNamesGiveNothing() {
        assertNull(SubscriptionRemarkInfo.parse(listOf("Germany 01", "🇳🇱 NL vless reality")))
    }

    @Test fun subscriptionPrefersHeaderThenRemarks() {
        val header = SubscriptionUserInfo(gb, gb, 10 * gb, 2_000_000_000L)
        val remarks = SubscriptionRemarkInfo(remainingBytes = 4 * gb, totalBytes = 5 * gb, expire = 1L)
        val both = Subscription("s", "https://x", "x", emptyList(), header, 0L, remarkInfo = remarks)
        assertEquals(2 * gb, both.usedBytes)
        assertEquals(10 * gb, both.totalBytes)
        assertEquals(8 * gb, both.remainingBytes)
        assertEquals(2_000_000_000L, both.expireAt)
        val onlyRemarks = both.copy(userInfo = null)
        assertEquals(5 * gb, onlyRemarks.totalBytes)
        assertEquals(gb, onlyRemarks.usedBytes)
        assertEquals(0.2f, onlyRemarks.usedFraction!!, 0.001f)
        val none = both.copy(userInfo = null, remarkInfo = null)
        assertNull(none.totalBytes); assertNull(none.expireAt); assertNull(none.daysLeft()); assertNull(none.usedFraction)
    }

    @Test fun daysLeftCountsWholeDays() {
        val now = 1_800_000_000_000L
        val sub = Subscription("s", "u", "n", emptyList(), SubscriptionUserInfo(null, null, null, now / 1000 + 3 * 86_400 + 100), 0L)
        assertEquals(3L, sub.daysLeft(now))
        val expired = sub.copy(userInfo = SubscriptionUserInfo(null, null, null, now / 1000 - 10))
        assertEquals(-1L, expired.daysLeft(now))
    }

    @Test fun shareLinkWithoutTypeIsTcpAndRawIsTcp() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val noType = ProfileUriParser.parse("vless://$uuid@1.2.3.4:443?security=tls&sni=a.example#n")
        assertEquals("tcp", noType.network)
        val withPath = ProfileUriParser.parse("vless://$uuid@1.2.3.4:443?security=tls&sni=a.example&path=%2Fws#n")
        assertEquals("ws", withPath.network)
        assertEquals("tcp", ProfileNetworks.requireSupported("raw"))
        assertNotNull(ProfileUriParser.parse("vless://$uuid@1.2.3.4:443?type=raw&security=tls&sni=a.example#n"))
    }
}
