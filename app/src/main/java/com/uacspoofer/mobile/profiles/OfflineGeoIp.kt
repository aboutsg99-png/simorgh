package com.uacspoofer.mobile.profiles

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4 -> country without any network request, from the IPFire location database that
 * already ships in the APK for Tor (assets/tor/geoip, lines "start,end,CC").
 *
 * Replaces the ipwho.is lookup that sent every config's server IP, from the phone's real
 * address and outside the tunnel, to a third party.
 */
class OfflineGeoIp private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val codes: ShortArray,
    private val names: List<String>,
) {
    val size: Int get() = starts.size

    /** Two-letter code, or null when the address is in no range (or IPv6). */
    fun lookup(address: InetAddress): String? {
        if (address !is Inet4Address) return null
        val b = address.address
        val ip = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
            ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
        return lookup(ip)
    }

    fun lookup(ip: Long): String? {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = starts[mid].toLong() and 0xffffffffL
            val end = ends[mid].toLong() and 0xffffffffL
            when {
                ip < start -> hi = mid - 1
                ip > end -> lo = mid + 1
                else -> return names[codes[mid].toInt()].takeIf { it.length == 2 && it != "??" }
            }
        }
        return null
    }

    companion object {
        @Volatile private var loaded: OfflineGeoIp? = null

        fun get(context: Context): OfflineGeoIp = loaded ?: synchronized(this) {
            // Same plain file Tor uses (extracted once from the APK; the build may store the
            // asset as tor/geoip or tor/geoip.gz, TorGeoIp handles both).
            loaded ?: com.uacspoofer.mobile.engine.tor.TorGeoIp.install(context.applicationContext).geoip
                .bufferedReader(Charsets.US_ASCII, 64 * 1024)
                .use { parse(it.lineSequence()) }
                .also { loaded = it }
        }

        /** Parses Tor geoip lines; ranges must be sorted by start (as Tor's file is). */
        fun parse(lines: Sequence<String>): OfflineGeoIp {
            var starts = IntArray(1 shl 16)
            var ends = IntArray(1 shl 16)
            var codes = ShortArray(1 shl 16)
            val names = ArrayList<String>()
            val index = HashMap<String, Short>()
            var n = 0
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val c1 = line.indexOf(',')
                val c2 = line.indexOf(',', c1 + 1)
                if (c1 <= 0 || c2 <= c1) continue
                val start = line.substring(0, c1).toLongOrNull() ?: continue
                val end = line.substring(c1 + 1, c2).toLongOrNull() ?: continue
                val cc = line.substring(c2 + 1).trim().uppercase()
                if (n == starts.size) {
                    starts = starts.copyOf(n * 2); ends = ends.copyOf(n * 2); codes = codes.copyOf(n * 2)
                }
                starts[n] = start.toInt()
                ends[n] = end.toInt()
                codes[n] = index.getOrPut(cc) { names.add(cc); (names.size - 1).toShort() }
                n++
            }
            return OfflineGeoIp(starts.copyOf(n), ends.copyOf(n), codes.copyOf(n), names)
        }
    }
}
