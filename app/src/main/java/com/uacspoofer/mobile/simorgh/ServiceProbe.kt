package com.uacspoofer.mobile.simorgh

import android.app.ActivityManager
import android.content.Context
import com.uacspoofer.mobile.vpn.UacVpnService

/** Is the connection service process-alive right now? (own-app services are still listed). */
object ServiceProbe {
    @Suppress("DEPRECATION")
    fun vpnServiceAlive(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return true
        val name = UacVpnService::class.java.name
        return runCatching {
            am.getRunningServices(64).any { it.service.className == name && it.service.packageName == context.packageName }
        }.getOrDefault(true)
    }
}
