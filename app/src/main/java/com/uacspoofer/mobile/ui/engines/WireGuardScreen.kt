package com.uacspoofer.mobile.ui.engines

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.wireguard.AmneziaImport
import com.uacspoofer.mobile.engine.wireguard.ProtonVpn
import com.uacspoofer.mobile.engine.wireguard.WgProbe
import com.uacspoofer.mobile.engine.wireguard.WgResult
import com.uacspoofer.mobile.engine.wireguard.WgSource
import com.uacspoofer.mobile.engine.wireguard.WgStatusStore
import com.uacspoofer.mobile.engine.wireguard.WgTunnel
import com.uacspoofer.mobile.engine.wireguard.WgTunnelStore
import com.uacspoofer.mobile.engine.wireguard.Windscribe
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.ui.EngineCatalog
import com.uacspoofer.mobile.ui.PowCompactHeader
import com.uacspoofer.mobile.ui.ToolPageScaffold
import com.uacspoofer.mobile.ui.displayName
import com.uacspoofer.mobile.ui.subtitle
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WireGuard engine page: tunnels from every source, «خودکار» (Flux) or one picked by hand. */
@Composable
internal fun WireGuardScreen(onBack: () -> Unit, onProtonSignIn: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { WgTunnelStore.get(context) }
    val library by store.library.collectAsStateWithLifecycle()
    val status by WgStatusStore.status.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val accent = UacColors.DisconnectedBlue

    fun import(text: String, source: WgSource, nameHint: String? = null) {
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    val amnezia = AmneziaImport.expand(text)
                    if (amnezia == null) {
                        "${store.importText(text, source, nameHint).size} کانفیگ WireGuard اضافه شد"
                    } else {
                        val wg = amnezia.wireGuardConfigs.sumOf { store.importText(it, WgSource.AMNEZIA, amnezia.name).size }
                        val xray = amnezia.xrayLinks.takeIf { it.isNotEmpty() }
                            ?.let { ProfileStore(context).importText(it.joinToString("\n"), amnezia.name).importedCount } ?: 0
                        buildString {
                            append("امنزیا: $wg کانفیگ WireGuard")
                            if (xray > 0) append(" و $xray کانفیگ Xray")
                            append(" اضافه شد")
                            if (amnezia.unsupported.isNotEmpty()) append("؛ پشتیبانی‌نشده: ${amnezia.unsupported.joinToString()}")
                        }
                    }
                }
            }.getOrElse { it.message ?: "کانفیگ خوانده نشد" }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        scope.launch {
            var added = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.take(100).forEach { uri ->
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(256 * 1024).toByteArray().decodeToString() }.orEmpty()
                        val source = if (name.orEmpty().startsWith("Windscribe", true)) WgSource.WINDSCRIBE else WgSource.OWN
                        val hint = if (source == WgSource.WINDSCRIBE) Windscribe.nameFromFile(name) else name?.removeSuffix(".conf")
                        added += store.importText(text, source, hint).size
                    }.onFailure { errors += it.message.orEmpty() }
                }
            }
            message = if (errors.isEmpty()) "$added کانفیگ اضافه شد" else "$added کانفیگ اضافه شد؛ ${errors.size} فایل خوانده نشد: ${errors.first()}"
        }
    }

    ToolPageScaffold(
        accent = accent,
        header = {
            PowCompactHeader(
                accent = accent, onBack = onBack,
                title = EngineCatalog.WireGuard.displayName(), subtitle = EngineCatalog.WireGuard.subtitle(),
                icon = EngineCatalog.WireGuard.icon,
            )
        },
    ) {
        item(key = "status") {
            EngineCard {
                EngineTitle("وضعیت")
                EngineBody(
                    status.detail.ifBlank {
                        "WireGuard روی هستهٔ Xray همین برنامه اجرا می‌شود؛ بدون برنامهٔ جانبی و بدون دسترسی روت."
                    },
                )
                message?.let { EngineBody(it, color = UacColors.TextPrimary) }
            }
        }
        item(key = "auto") {
            EngineCard {
                EngineIconRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "خودکار (Flux)",
                    subtitle = "بهترین سرور برای این شبکه، کشور و نسخهٔ IP انتخابی؛ اگر جواب نداد سراغ بعدی می‌رود",
                    trailing = { SelectIcon(library.selectedId == null) },
                    onClick = { runCatching { store.select(null) } },
                )
            }
        }
        item(key = "providers") {
            EngineCard {
                EngineTitle("Proton VPN")
                EngineBody(
                    if (ProtonVpn.signedIn()) "وارد حساب Proton شده‌اید. سرورهای حساب شما (رایگان یا پولی) به‌صورت کانفیگ WireGuard این‌جا اضافه می‌شوند."
                    else "با حساب Proton خودتان (رایگان هم کافی است) وارد شوید؛ رمز عبور فقط در صفحهٔ خود Proton وارد می‌شود.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnginePrimaryButton(if (ProtonVpn.signedIn()) "به‌روزرسانی سرورها" else "ورود به Proton VPN", onClick = onProtonSignIn)
                    if (ProtonVpn.signedIn()) EngineSecondaryButton("خروج") {
                        scope.launch { withContext(Dispatchers.Main) { ProtonVpn.signOut(context) }; message = "از Proton خارج شدید" }
                    }
                }
            }
        }
        item(key = "windscribe") {
            EngineCard {
                EngineTitle("Windscribe")
                EngineBody(
                    "در حساب Windscribe خودتان بخش Config Generators → WireGuard را باز کنید، برای هر سرور فایل .conf بسازید و " +
                        "این‌جا با «افزودن فایل» وارد کنید. ورود مستقیم داخل برنامه به کتابخانهٔ بومی WSNet ویندسکرایب نیاز دارد که در بلک دی نیست.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineSecondaryButton("باز کردن سازندهٔ کانفیگ") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Windscribe.CONFIG_GENERATOR_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    EnginePrimaryButton("افزودن فایل") { picker.launch(arrayOf("*/*")) }
                }
            }
        }
        item(key = "import") {
            EngineCard {
                EngineTitle("افزودن کانفیگ WireGuard")
                EngineBody("متن فایل .conf، لینک wireguard:// یا کلید vpn:// امنزیا (فقط نوع WireGuard ساده یا Xray) را بچسبانید.")
                OutlinedTextField(
                    value = pasted, onValueChange = { pasted = it.take(200_000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    placeholder = { Text("[Interface]\nPrivateKey = …", fontSize = 12.sp) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnginePrimaryButton("افزودن", enabled = pasted.isNotBlank()) { import(pasted, WgSource.OWN); pasted = "" }
                    EngineSecondaryButton("از فایل") { picker.launch(arrayOf("*/*")) }
                }
            }
        }
        item(key = "list-head") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EngineTitle("سرورها (${library.tunnels.size})", Modifier.weight(1f))
                val busy = connection == ConnectionState.CONNECTED && status.tunnelId != null
                EngineSecondaryButton(if (testing) "در حال تست…" else "تست پینگ واقعی", enabled = !testing && library.tunnels.isNotEmpty()) {
                    testing = true
                    scope.launch {
                        try {
                            val active = if (busy) status.tunnelId else null
                            WgProbe.measureAll(context, library.tunnels.filter { it.id != active }.take(40)) { o ->
                                runCatching { store.recordResult(o.tunnelId, WgResult(o.latencyMs != null, o.latencyMs, System.currentTimeMillis(), o.error)) }
                            }
                        } finally {
                            testing = false
                        }
                    }
                }
            }
        }
        if (library.storageError) {
            item(key = "storage") {
                EngineCard {
                    EngineBody("فضای امن کانفیگ‌ها خوانده نشد (کلید امن دستگاه عوض شده). برای شروع دوباره همه را پاک کنید.", color = UacColors.ErrorRed)
                    EngineSecondaryButton("پاک کردن همه") { store.wipe() }
                }
            }
        }
        items(library.tunnels, key = { it.id }) { t ->
            TunnelRow(
                tunnel = t, selected = library.selectedId == t.id, result = library.results[t.id],
                running = connection == ConnectionState.CONNECTED && status.tunnelId == t.id,
                onSelect = { runCatching { store.select(t.id) } },
                onDelete = { runCatching { store.delete(setOf(t.id)) } },
            )
        }
    }
}

@Composable
private fun SelectIcon(selected: Boolean) {
    Icon(
        if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
        tint = if (selected) UacColors.ConnectedGreen else UacColors.TextSecondary,
    )
}

@Composable
private fun TunnelRow(tunnel: WgTunnel, selected: Boolean, result: WgResult?, running: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    EngineCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(flagOf(tunnel.countryCode).ifEmpty { "🌐" }, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tunnel.name, color = UacColors.TextPrimary, fontSize = 14.sp, maxLines = 1)
                val ping = when {
                    running -> "متصل"
                    result == null -> "تست نشده"
                    result.ok -> "${result.latencyMs} ms"
                    else -> "جواب نداد"
                }
                Text("${tunnel.source.label} · ${tunnel.primary.endpointHost} · $ping", color = UacColors.TextSecondary, fontSize = 11.5.sp, maxLines = 1)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "حذف", tint = UacColors.TextSecondary) }
            IconButton(onClick = onSelect) { SelectIcon(selected) }
        }
    }
}
