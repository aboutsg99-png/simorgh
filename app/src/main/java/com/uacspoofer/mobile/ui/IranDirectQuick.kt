package com.uacspoofer.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.ui.theme.UacColors

/**
 * Quick access to the "Iranian sites direct" switch on every engine settings page. It writes
 * the same NetworkGuardStore flag as the main Settings row, which all four engines read.
 */
@Composable
internal fun IranDirectQuickCard() {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    PowCard {
        PowSwitchRow(
            icon = Icons.AutoMirrored.Outlined.AltRoute,
            title = homeText("Iranian sites direct", "سایت‌های ایرانی مستقیم"),
            subtitle = homeText(
                ".ir domains and Iranian IPs skip the tunnel",
                "دامنه‌های .ir و IPهای ایران بدون عبور از تونل",
            ),
            checked = settings.iranDirect,
            accent = UacColors.ConnectedGreen,
            onCheckedChange = { store.setIranDirect(it) },
        )
    }
}
