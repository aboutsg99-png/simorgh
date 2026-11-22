package com.uacspoofer.mobile.extras

import android.content.Context
import com.uacspoofer.mobile.vpn.MonthlyTrafficSnapshot
import com.uacspoofer.mobile.vpn.MonthlyTrafficStore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Warns once at 80% and once at 100% of the monthly cap, per month.
 *
 * Reads the existing [MonthlyTrafficStore] flow; it only reacts when the
 * usage level bucket changes, so it adds no polling and no wakeups.
 */
internal object DataCapWatcher {
    @Volatile private var started = false

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val app = context.applicationContext
        val extras = ExtrasSettingsStore.get(app)
        val monthly = MonthlyTrafficStore.get(app)
        scope.launch {
            combine(monthly.usage, extras.settings) { usage, settings ->
                Check(usage.monthKey, levelFor(usage, settings), settings.dataCapEnabled, settings.dataCapBytes, usage.totalBytes)
            }
                .distinctUntilChanged { old, new ->
                    old.monthKey == new.monthKey && old.level == new.level &&
                        old.enabled == new.enabled && old.capBytes == new.capBytes
                }
                .collect { check -> evaluate(app, extras, check) }
        }
    }

    private data class Check(
        val monthKey: String,
        val level: Int,
        val enabled: Boolean,
        val capBytes: Long,
        val usedBytes: Long,
    )

    fun levelFor(usage: MonthlyTrafficSnapshot, settings: ExtrasSettings): Int {
        val cap = settings.dataCapBytes
        if (!settings.dataCapEnabled || cap <= 0L) return 0
        val used = usage.totalBytes
        return when {
            used >= cap -> 100
            used * 100 >= cap * 80 -> 80
            else -> 0
        }
    }

    private fun evaluate(context: Context, store: ExtrasSettingsStore, check: Check) {
        if (!check.enabled || check.level == 0) return
        val warned = store.capWarnedLevel(check.monthKey)
        if (check.level <= warned) return
        store.setCapWarnedLevel(check.monthKey, check.level)
        val used = formatGb(check.usedBytes)
        val cap = formatGb(check.capBytes)
        if (check.level >= 100) {
            ExtrasNotifications.cancel(context, ExtrasNotifications.ID_CAP_80)
            ExtrasNotifications.post(
                context,
                ExtrasNotifications.ID_CAP_100,
                "سقف حجم ماهانه تمام شد",
                "مصرف این ماه $used گیگابایت شد و به سقف $cap گیگابایتی که تعیین کرده‌اید رسید. اتصال قطع نمی‌شود؛ این فقط یک هشدار است.",
            )
        } else {
            ExtrasNotifications.post(
                context,
                ExtrasNotifications.ID_CAP_80,
                "۸۰٪ حجم ماهانه مصرف شد",
                "مصرف این ماه $used از $cap گیگابایت است.",
            )
        }
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / ExtrasSettings.BYTES_PER_GB
        return if (gb >= 10) String.format(Locale.US, "%.0f", gb) else String.format(Locale.US, "%.1f", gb)
    }
}
