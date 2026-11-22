package com.uacspoofer.mobile.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.uacspoofer.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Release-build integrity checks: the APK must carry the official signing
 * certificate, must not be debuggable, and must not run under a hooking
 * framework or attached debugger. A repackaged or patched copy fails here.
 */
internal object IntegrityGuard {
    enum class Problem { SIGNATURE, DEBUGGABLE, HOOKED, DEBUGGER }

    @Volatile private var cached: Problem? = null
    @Volatile private var checked = false

    fun problem(context: Context): Problem? {
        if (BuildConfig.DEBUG) return null
        if (checked) return cached
        val result = runCatching { evaluate(context.applicationContext) }.getOrDefault(Problem.SIGNATURE)
        cached = result
        checked = true
        return result
    }

    fun isTrusted(context: Context): Boolean = problem(context) == null

    private fun evaluate(context: Context): Problem? {
        val expected = BuildConfig.SIGNING_CERT_SHA256.replace(":", "").lowercase()
        if (expected.isNotEmpty()) {
            val actual = signingDigests(context)
            if (actual.isEmpty() || expected !in actual) return Problem.SIGNATURE
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return Problem.DEBUGGABLE
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return Problem.DEBUGGER
        if (hookFrameworkLoaded()) return Problem.HOOKED
        return null
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(context: Context): Set<String> {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun hookFrameworkLoaded(): Boolean {
        val markers = listOf("frida", "xposed", "lsposed", "edxp", "substrate", "riru", "gum-js-loop")
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrNull()?.lowercase() ?: return false
        if (markers.any { it in maps }) return true
        return runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        }.getOrDefault(false)
    }
}
