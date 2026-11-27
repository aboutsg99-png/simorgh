package com.uacspoofer.mobile.ui.engines

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.gamedns.DnsRaceResult
import com.uacspoofer.mobile.engine.gamedns.GameDns
import com.uacspoofer.mobile.engine.gamedns.GameDnsRace
import com.uacspoofer.mobile.engine.gamedns.GameDnsStore
import com.uacspoofer.mobile.engine.gamedns.GameDnsVpnService
import com.uacspoofer.mobile.ui.PowCompactHeader
import com.uacspoofer.mobile.ui.ToolPageScaffold
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.launch

/** Game DNS: race public resolvers, then a DNS-only VPN so the game itself stays direct. */
@Composable
internal fun GameDnsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { GameDnsStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val running by GameDnsVpnService.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var racing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DnsRaceResult>>(emptyList()) }
    var userList by remember { mutableStateOf(settings.userList) }
    var domains by remember { mutableStateOf(settings.domains) }
    var message by remember { mutableStateOf<String?>(null) }
    val accent = UacColors.DisconnectedBlue

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) GameDnsVpnService.start(context) else message = "اجازهٔ VPN داده نشد"
    }

    fun turnOn() {
        if (settings.primary == null) { message = "اول مسابقه را اجرا کنید یا یک DNS انتخاب کنید"; return }
        // Auto would otherwise start its next engine and replace the DNS-only VPN.
        com.uacspoofer.mobile.simorgh.auto.AutoConnector.cancel()
        if (ConnectionStateStore.state.value != ConnectionState.DISCONNECTED) {
            ConnectionStateStore.tryBeginDisconnect()
            VpnController.stop(context)
        }
        val ask = VpnService.prepare(context)
        if (ask != null) permission.launch(ask) else GameDnsVpnService.start(context)
    }

    ToolPageScaffold(
        accent = accent,
        header = { PowCompactHeader(accent = accent, onBack = onBack, title = "DNS بازی", subtitle = "سریع‌ترین DNS برای بازی‌ها", icon = Icons.Outlined.SportsEsports) },
    ) {
        item(key = "state") {
            EngineCard {
                EngineTitle(if (running != null) "روشن · $running" else "خاموش")
                EngineBody(
                    "در این حالت فقط درخواست‌های DNS از سیمرغ رد می‌شوند و ترافیک خود بازی مستقیم می‌رود؛ برای همین پینگ بالا نمی‌رود. " +
                        "با روشن شدن، تونل اصلی سیمرغ قطع می‌شود (اندروید فقط یک VPN هم‌زمان دارد). " +
                        "اگر در «عبور انتخابی برنامه‌ها» فقط برخی برنامه‌ها را انتخاب کرده‌اید، همان‌ها از این DNS استفاده می‌کنند.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running == null) EnginePrimaryButton("روشن کردن") { turnOn() }
                    else EngineSecondaryButton("خاموش کردن") { GameDnsVpnService.stop(context) }
                }
                message?.let { EngineBody(it, color = UacColors.TextPrimary) }
            }
        }
        item(key = "race") {
            EngineCard {
                EngineTitle("مسابقهٔ DNS")
                EngineBody("به همهٔ DNSها هم‌زمان برای دامنه‌های بازی پرسش واقعی فرستاده می‌شود؛ سریع‌ترینی که درست جواب دهد انتخاب می‌شود. هیچ اطلاعاتی به جایی فرستاده نمی‌شود.")
                OutlinedTextField(domains, { domains = it.take(2_000) }, Modifier.fillMaxWidth().heightIn(min = 64.dp), label = { Text("دامنه‌های بازی (هر خط یکی)") })
                OutlinedTextField(userList, { userList = it.take(8_000) }, Modifier.fillMaxWidth().heightIn(min = 64.dp), label = { Text("DNSهای خودتان: «آی‌پی نام»، هر خط یکی") })
                EnginePrimaryButton(if (racing) "در حال مسابقه…" else "شروع مسابقه", enabled = !racing) {
                    racing = true
                    store.update { it.copy(userList = userList, domains = domains) }
                    scope.launch {
                        try {
                            val s = store.snapshot()
                            results = GameDns.rank(GameDnsRace.race(s.servers(), s.domainList()))
                            results.firstOrNull()?.let { best ->
                                store.update { it.copy(primary = best.server.ip, secondary = results.getOrNull(1)?.server?.ip) }
                                message = "بهترین: ${best.server.name} (${best.medianMs} ms)"
                            } ?: run { message = "هیچ DNS ای جواب نداد" }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            message = "مسابقه انجام نشد"
                        } finally {
                            racing = false
                        }
                    }
                }
                if (racing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        items(results, key = { it.server.ip }) { r ->
            EngineCard {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("${r.server.name} · ${r.server.ip}", color = UacColors.TextPrimary, fontSize = 14.sp)
                        Text("${r.server.category.fa} · ${r.medianMs} ms · ${(r.successRate * 100).toInt()}٪ پاسخ", color = UacColors.TextSecondary, fontSize = 11.5.sp)
                    }
                    EngineChip(if (settings.primary == r.server.ip) "انتخاب‌شده" else "انتخاب", settings.primary == r.server.ip) {
                        store.update { it.copy(primary = r.server.ip) }
                    }
                }
            }
        }
    }
}
