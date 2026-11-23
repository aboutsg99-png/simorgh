package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import com.uacspoofer.mobile.mci.DomesticDirect
import com.uacspoofer.mobile.mci.GeoAssets
import java.io.File
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

/**
 * The global "Iranian sites direct" setting (NetworkGuardStore.iranDirect) for Rubah.
 * Builds a sing-box local rule-set (source format) from the same data the other engines
 * use: geosite category-ir suffixes via [DomesticDirect], every .ir domain, and the
 * geoip:ir CIDRs from the bundled geoip.dat. Private ranges are a separate
 * `ip_is_private` rule that is always on.
 *
 * Note: with the hev/zeptun fake-DNS relay, apps reach sing-box by hostname, so the
 * domain rules do the real work; the IR CIDRs cover apps that dial literal IPs.
 */
internal object FoxyIranRules {
    private const val FILE = "foxy/ir-direct.json"

    /** Blocking (file parse); call on Dispatchers.IO. Returns the rule-set path or null. */
    fun build(context: Context): String? = runCatching {
        val app = context.applicationContext
        runCatching { DomesticDirect.ensure(app) }
        val suffixes = (DomesticDirect.suffixSnapshot() + "ir")
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
            .distinct()
        val cidrs = runCatching { geoIpCidrs(File(app.filesDir, GeoAssets.GEOIP), "IR") }
            .onFailure { AppLogger.w("IranRules", "geoip:ir unavailable; domains only", it) }
            .getOrDefault(emptyList())
        val rules = JSONArray()
        rules.put(JSONObject().put("domain_suffix", JSONArray(suffixes)))
        if (cidrs.isNotEmpty()) rules.put(JSONObject().put("ip_cidr", JSONArray(cidrs)))
        val json = JSONObject().put("version", 1).put("rules", rules).toString()
        val out = File(app.filesDir, FILE)
        out.parentFile?.mkdirs()
        out.writeText(json)
        AppLogger.i("IranRules", "rule-set ${suffixes.size} suffixes, ${cidrs.size} IR CIDRs")
        out.absolutePath
    }.onFailure { AppLogger.w("IranRules", "could not build Iran rule-set", it) }.getOrNull()

    /** Minimal protobuf walk of v2ray GeoIPList: entry{1:country_code, 2:cidr{1:ip, 2:prefix}}. */
    internal fun geoIpCidrs(file: File, code: String): List<String> {
        if (!file.isFile) return emptyList()
        val data = file.readBytes()
        val out = ArrayList<String>(2048)
        var i = 0
        fun varint(buf: ByteArray, start: Int): Pair<Long, Int> {
            var pos = start
            var shift = 0
            var value = 0L
            while (pos < buf.size) {
                val b = buf[pos].toInt() and 0xff
                pos++
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) error("bad varint")
            }
            return value to pos
        }
        while (i < data.size) {
            val (key, afterKey) = varint(data, i)
            i = afterKey
            if ((key and 7L) != 2L) error("unexpected wire type")
            val (len, afterLen) = varint(data, i)
            i = afterLen
            val end = i + len.toInt()
            if (end > data.size || end < i) error("truncated geoip")
            if ((key ushr 3) == 1L) {
                parseEntry(data, i, end, code, out, ::varint)
            }
            i = end
        }
        return out
    }

    private fun parseEntry(
        data: ByteArray,
        start: Int,
        end: Int,
        code: String,
        out: MutableList<String>,
        varint: (ByteArray, Int) -> Pair<Long, Int>,
    ) {
        var j = start
        var country = ""
        val cidrs = ArrayList<Pair<Int, Int>>()
        while (j < end) {
            val (key, a) = varint(data, j)
            j = a
            when ((key and 7L).toInt()) {
                0 -> j = varint(data, j).second
                2 -> {
                    val (len, b) = varint(data, j)
                    j = b
                    val stop = j + len.toInt()
                    when ((key ushr 3).toInt()) {
                        1 -> country = String(data, j, len.toInt(), Charsets.US_ASCII)
                        2 -> cidrs += j to stop
                    }
                    j = stop
                }
                else -> return
            }
        }
        if (!country.equals(code, ignoreCase = true)) return
        for ((cStart, cEnd) in cidrs) {
            var k = cStart
            var ip: ByteArray? = null
            var prefix = -1
            while (k < cEnd) {
                val (key, a) = varint(data, k)
                k = a
                when ((key ushr 3).toInt() to (key and 7L).toInt()) {
                    1 to 2 -> {
                        val (len, b) = varint(data, k)
                        k = b
                        ip = data.copyOfRange(k, k + len.toInt())
                        k += len.toInt()
                    }
                    2 to 0 -> {
                        val (v, b) = varint(data, k)
                        prefix = v.toInt()
                        k = b
                    }
                    else -> return
                }
            }
            val bytes = ip ?: continue
            if ((bytes.size == 4 && prefix in 0..32) || (bytes.size == 16 && prefix in 0..128)) {
                out += "${InetAddress.getByAddress(bytes).hostAddress}/$prefix"
            }
        }
    }
}
