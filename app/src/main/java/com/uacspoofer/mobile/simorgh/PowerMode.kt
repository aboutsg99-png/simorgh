package com.uacspoofer.mobile.simorgh

import android.content.Context
import com.uacspoofer.mobile.settings.BatteryPreset
import com.uacspoofer.mobile.settings.BatterySettings
import com.uacspoofer.mobile.settings.BatterySettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The three battery modes, read from the existing battery settings (no new storage). */
enum class PowerMode(val titleFa: String, val subtitleFa: String) {
    BALANCED("متعادل", "همه‌ی قابلیت‌ها و انیمیشن‌ها"),
    SAVER("کم‌مصرف", "بدون پینگ پس‌زمینه، بررسی تونل هر ۳ دقیقه، انیمیشن کم، نقشه ثابت"),
    ULTRA("فوق‌کم‌مصرف", "بدون انیمیشن و بررسی دوره‌ای؛ فقط هنگام تغییر شبکه");

    companion object {
        internal fun of(settings: BatterySettings): PowerMode = when {
            settings.staticHomeEffects -> ULTRA
            settings.slowNotification || settings.slowBackgroundStats -> SAVER
            else -> BALANCED
        }

        fun current(context: Context): PowerMode = of(BatterySettingsStore.get(context).snapshot())

        fun flow(context: Context): Flow<PowerMode> =
            BatterySettingsStore.get(context).settings.map { of(it) }.distinctUntilChanged()

        fun set(context: Context, mode: PowerMode) {
            val store = BatterySettingsStore.get(context)
            val preset = when (mode) {
                BALANCED -> BatteryPreset.BALANCED
                SAVER -> BatteryPreset.SAVER
                ULTRA -> BatteryPreset.MAX_SAVER
            }
            store.save(preset.forEngine(store.snapshot(), direct = true))
        }
    }
}
