package com.uacspoofer.mobile.ui.shell

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.settings.BatterySettingsStore
import com.uacspoofer.mobile.simorgh.PowerMode

internal fun ignoringBatteryOptimizations(context: Context): Boolean =
    runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() == true

private fun openBatteryOptimizationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * «باتری و مصرف»: the power mode, Android's battery optimisation for the app, and the
 * individual saving switches (the same settings the power modes set).
 */
@Composable
internal fun BatteryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val store = remember(context) { BatterySettingsStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val power by remember(context) { PowerMode.flow(context) }.collectAsStateWithLifecycle(remember(context) { PowerMode.current(context) })
    val mode = currentEngineMode()
    val powStore = remember(context) { PowEngineStore.get(context) }
    val pow by powStore.settings.collectAsStateWithLifecycle()
    var exempt by remember { mutableStateOf(ignoringBatteryOptimizations(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) exempt = ignoringBatteryOptimizations(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    SubPage("باتری و مصرف", onBack, subtitle = "تغییرات فوراً ذخیره می‌شوند") {
        item(key = "mode") {
            SGroup("حالت مصرف") {
                Column(Modifier.padding(12.dp)) {
                    SSegmented(PowerMode.entries, power, { it.titleFa }) { PowerMode.set(context, it) }
                    Spacer(Modifier.height(10.dp))
                    SParagraph(
                        when (power) {
                            PowerMode.BALANCED -> "همه‌چیز روشن است: پینگ خودکار سرورها، بررسی سلامت تونل و انیمیشن‌ها. برای وقتی که شارژ کافی دارید."
                            PowerMode.SAVER -> "پینگ پس‌زمینه خاموش می‌شود، سلامت تونل هر سه دقیقه بررسی می‌شود و انیمیشن‌ها کوتاه می‌شوند. اتصال همان‌قدر امن است."
                            PowerMode.ULTRA -> "هیچ بررسی دوره‌ای و انیمیشنی اجرا نمی‌شود؛ بلک دی فقط وقتی شبکه عوض شود کاری انجام می‌دهد. برای سفر یا شارژ کم."
                        },
                        size = 13.sp,
                    )
                }
            }
        }
        item(key = "system") {
            SGroup(
                "بهینه‌سازی باتری اندروید",
                footer = "بعضی گوشی‌ها (شیائومی، سامسونگ، هواوی) برنامه‌های پس‌زمینه را می‌بندند. اگر وی‌پی‌ان با خاموش شدن صفحه قطع می‌شود، بلک دی را از بهینه‌سازی خارج کنید و در تنظیمات گوشی «اجرای خودکار» را هم روشن کنید.",
            ) {
                SRow(
                    if (exempt) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryAlert,
                    if (exempt) "بلک دی از بهینه‌سازی خارج است" else "اندروید ممکن است اتصال را ببندد",
                    if (exempt) "اتصال در پس‌زمینه پایدار می‌ماند" else "برای باز کردن تنظیمات باتری اندروید لمس کنید",
                    tint = if (exempt) sx.good else sx.warn,
                ) { openBatteryOptimizationSettings(context) }
            }
        }
        item(key = "switches") {
            SGroup("صرفه‌جویی جزئی") {
                if (mode.isDirect) {
                    SToggleRow(Icons.Outlined.NetworkCheck, "بررسی سبک Direct", "وقتی صفحه خاموش است، اتصال Direct کمتر بررسی می‌شود", settings.lightDirectChecks) {
                        store.save(settings.copy(lightDirectChecks = it))
                    }
                    RowDivider()
                }
                if (mode.isPow) {
                    SToggleRow(Icons.Outlined.NetworkCheck, "بررسی سبک WARP و Psiphon", "از اتصال بعدی، بررسی‌های کم‌هزینه‌تر استفاده می‌شود", pow.optimizedMode) {
                        powStore.save(powStore.snapshot().copy(optimizedMode = it))
                    }
                    RowDivider()
                }
                SToggleRow(Icons.Outlined.BarChart, "آمار مصرف کندتر", "با صفحه‌ی خاموش، آمار ترافیک با فاصله‌ی بیشتری به‌روز می‌شود", settings.slowBackgroundStats) {
                    store.save(settings.copy(slowBackgroundStats = it))
                }
                RowDivider()
                SToggleRow(Icons.Outlined.NotificationsNone, "اعلان سبک", "سرعت داخل اعلان اتصال دیرتر به‌روز می‌شود", settings.slowNotification) {
                    store.save(settings.copy(slowNotification = it))
                }
                RowDivider()
                SToggleRow(Icons.Outlined.AutoAwesome, "بدون انیمیشن", "نقشه، دکمه‌ی اتصال و صفحه‌ها بدون حرکت نمایش داده می‌شوند", settings.staticHomeEffects) {
                    store.save(settings.copy(staticHomeEffects = it))
                }
            }
        }
        item(key = "tip") {
            SNote("مصرف واقعی باتری بیشتر به موتور بستگی دارد: Xray و WireGuard سبک‌ترند، Tor و Psiphon + WARP به خاطر چند لایه بودن مصرف بیشتری دارند.", icon = Icons.Outlined.BatteryChargingFull)
        }
    }
}
