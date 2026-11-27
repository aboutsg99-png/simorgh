package com.uacspoofer.mobile.ui.engines

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.uacspoofer.mobile.ui.DrawerDestination
import com.uacspoofer.mobile.ui.theme.UacColors

/** Entry rows on the Engines page for the pages for the extra engines. */
@Composable
internal fun EngineToolsCard(onOpen: (DrawerDestination) -> Unit) {
    EngineCard {
        EngineTitle("هوشمندی و ابزارها")
        EngineIconRow(Icons.Outlined.AutoAwesome, "Flux", "کشور و نسخهٔ IP را شما بگویید؛ Flux بهترین کانفیگ را برای هر شبکه انتخاب می‌کند", trailing = { Arrow() }) {
            onOpen(DrawerDestination.FLUX_SETTINGS)
        }
        EngineIconRow(Icons.Outlined.CleaningServices, "اسکنر آی‌پی تمیز", "آی‌پی‌های سالم کلودفلر روی همین شبکه، برای کانفیگ‌های CDN", trailing = { Arrow() }) {
            onOpen(DrawerDestination.CLEAN_IP_SCANNER)
        }
        EngineIconRow(Icons.Outlined.SportsEsports, "DNS بازی", "سریع‌ترین DNS عمومی؛ فقط DNS از سیمرغ می‌گذرد و پینگ بازی پایین می‌ماند", trailing = { Arrow() }) {
            onOpen(DrawerDestination.GAME_DNS)
        }
    }
}

@Composable
private fun Arrow() = Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = UacColors.TextSecondary)
