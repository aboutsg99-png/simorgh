package com.uacspoofer.mobile.profiles

import java.util.Calendar
import java.util.TimeZone

/**
 * Usage and expiry that panels write into config names when they do not send the
 * `subscription-userinfo` header (Marzban/3x-ui/Hiddify templates, Persian and English):
 * "حجم باقیمانده: 12.5 GB", "📊 3.2 GB / 50 GB", "Remaining: 800 MB", "⏳ 25 روز",
 * "Expire: 2026-12-01", "انقضا: 1405/09/10". Only what is written is reported; null otherwise.
 */
data class SubscriptionRemarkInfo(
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val remainingBytes: Long? = null,
    /** Expiry, Unix seconds (UTC midnight of the written date, or now + written days). */
    val expire: Long? = null,
) {
    val isEmpty: Boolean get() = usedBytes == null && totalBytes == null && remainingBytes == null && expire == null

    companion object {
        private val SIZE = "(\\d+(?:[.,]\\d+)?)\\s*(tb|gb|mb|kb|ترابایت|گیگابایت|گیگ|مگابایت|مگ|کیلوبایت|t|g|m|k)"
        private val PAIR = Regex("$SIZE\\s*(?:/|از|of)\\s*$SIZE", RegexOption.IGNORE_CASE)
        private val REMAINING = Regex("(?:باقی\\s*[\\u200c]?\\s*مانده|باقیمانده|مانده|remaining|left|remain)[^\\d]{0,12}$SIZE", RegexOption.IGNORE_CASE)
        private val REMAINING_AFTER = Regex("$SIZE[^\\d]{0,6}(?:باقی\\s*[\\u200c]?\\s*مانده|باقیمانده|مانده|remaining|left)", RegexOption.IGNORE_CASE)
        private val USED = Regex("(?:مصرف\\s*شده|مصرفی|مصرف|used|usage)[^\\d]{0,12}$SIZE", RegexOption.IGNORE_CASE)
        private val TOTAL = Regex("(?:حجم\\s*کل|کل\\s*حجم|total|حجم)[^\\d]{0,12}$SIZE", RegexOption.IGNORE_CASE)
        private val DAYS = Regex("(\\d{1,4})\\s*(?:روز|days?|d\\b)", RegexOption.IGNORE_CASE)
        private val DATE = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})")

        fun parse(remarks: List<String>, nowMillis: Long = System.currentTimeMillis()): SubscriptionRemarkInfo? {
            var used: Long? = null
            var total: Long? = null
            var remaining: Long? = null
            var expire: Long? = null
            for (raw in remarks.take(50)) {
                val text = normalizeDigits(raw)
                if (used == null && total == null) {
                    PAIR.find(text)?.let { m ->
                        val a = bytes(m.groupValues[1], m.groupValues[2])
                        val b = bytes(m.groupValues[3], m.groupValues[4])
                        if (a != null && b != null && b >= a) { used = a; total = b }
                    }
                }
                if (remaining == null) {
                    (REMAINING.find(text) ?: REMAINING_AFTER.find(text))?.let { m -> remaining = bytes(m.groupValues[1], m.groupValues[2]) }
                }
                if (used == null) USED.find(text)?.let { m -> used = bytes(m.groupValues[1], m.groupValues[2]) }
                if (total == null && remaining == null) {
                    TOTAL.find(text)?.takeIf { PAIR.find(text) == null }?.let { m -> total = bytes(m.groupValues[1], m.groupValues[2]) }
                }
                if (expire == null && looksLikeExpiry(text)) {
                    DATE.find(text)?.let { m -> expire = dateSeconds(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                }
                if (expire == null && looksLikeExpiry(text)) {
                    DAYS.find(text)?.let { m -> expire = nowMillis / 1000L + m.groupValues[1].toLong() * 86_400L }
                }
            }
            val info = SubscriptionRemarkInfo(used, total, remaining, expire)
            return info.takeUnless { it.isEmpty }
        }

        private fun looksLikeExpiry(text: String): Boolean {
            val t = text.lowercase()
            return listOf("روز", "day", "انقضا", "expir", "⏳", "⌛", "📅", "تا پایان", "تاریخ", "date", "اعتبار", "valid").any { it in t }
        }

        fun normalizeDigits(s: String): String = buildString(s.length) {
            for (c in s) append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    '٫' -> '.'
                    else -> c
                },
            )
        }

        internal fun bytes(number: String, unit: String): Long? {
            val n = number.replace(',', '.').toDoubleOrNull() ?: return null
            val mult = when (unit.lowercase()) {
                "tb", "t", "ترابایت" -> 1L shl 40
                "gb", "g", "گیگابایت", "گیگ" -> 1L shl 30
                "mb", "m", "مگابایت", "مگ" -> 1L shl 20
                "kb", "k", "کیلوبایت" -> 1L shl 10
                else -> return null
            }
            return (n * mult).toLong()
        }

        /** UTC midnight of a Gregorian date, or of a Jalali one (year < 1700), in Unix seconds. */
        internal fun dateSeconds(y: Int, m: Int, d: Int): Long? {
            if (m !in 1..12 || d !in 1..31) return null
            val (gy, gm, gd) = if (y < 1700) jalaliToGregorian(y, m, d) else Triple(y, m, d)
            if (gy !in 2000..2100) return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(gy, gm - 1, gd)
            return cal.timeInMillis / 1000L
        }

        /** Jalali (Solar Hijri) to Gregorian, the standard arithmetic algorithm. */
        internal fun jalaliToGregorian(jy0: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
            val jy = jy0 + 1595
            var days = -355668 + 365 * jy + (jy / 33) * 8 + ((jy % 33) + 3) / 4 + jd +
                if (jm < 7) (jm - 1) * 31 else (jm - 7) * 30 + 186
            var gy = 400 * (days / 146097)
            days %= 146097
            if (days > 36524) {
                days--
                gy += 100 * (days / 36524)
                days %= 36524
                if (days >= 365) days++
            }
            gy += 4 * (days / 1461)
            days %= 1461
            if (days > 365) {
                gy += (days - 1) / 365
                days = (days - 1) % 365
            }
            var gd = days + 1
            val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
            val monthDays = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            var gm = 1
            while (gm <= 12 && gd > monthDays[gm]) { gd -= monthDays[gm]; gm++ }
            return Triple(gy, gm, gd)
        }
    }
}
