package com.uacspoofer.mobile.extras

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Entry point for the 1.0.1 extras; called once from Application.onCreate. */
object SimorghExtras {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    @Volatile private var initialized = false

    fun init(app: Application) {
        if (initialized || !isMainProcess(app)) return
        initialized = true
        // Off the main thread: prefs reads and binder calls must not delay app start.
        scope.launch {
            runCatching { UntrustedWifiWatcher.sync(app) }
            runCatching { DataCapWatcher.start(app, scope) }
        }
    }

    private fun isMainProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return name == null || name == context.packageName
    }
}
