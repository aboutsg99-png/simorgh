package com.uacspoofer.mobile.ui.engines

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.scanner.CleanIpProbe
import com.uacspoofer.mobile.engine.scanner.CleanIpRanges
import com.uacspoofer.mobile.engine.scanner.CleanIpResult
import com.uacspoofer.mobile.engine.scanner.CleanIpScoring
import com.uacspoofer.mobile.engine.scanner.CleanIpStore
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.ui.PowCompactHeader
import com.uacspoofer.mobile.ui.ToolPageScaffold
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean-IP scanner: samples Cloudflare's ranges (or the user's), measures each address with the
 * SNI of the user's own Cloudflare config, ranks them, and hands the best to Parvaz as this
 * network's connect pool for every Cloudflare-fronted config.
 */
@Composable
internal fun CleanIpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { CleanIpStore(context) }
    val scope = rememberCoroutineScope()
    val defaultSni = remember(context) {
        store.frontableProfiles().firstNotNullOfOrNull { p -> DirectCompatProfileParser.parseIdentity(p)?.let { it.sni.ifBlank { it.host } } }
            ?: "speed.cloudflare.com"
    }
    var sni by remember { mutableStateOf(store.last()?.sni ?: defaultSni) }
    var ranges by remember { mutableStateOf(store.userRanges()) }
    var count by remember { mutableIntStateOf(120) }
    var ipv6 by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<CleanIpResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val connected = ConnectionStateStore.state.value == ConnectionState.CONNECTED
    val accent = UacColors.DisconnectedBlue

    fun scan() {
        job = scope.launch {
            message = null
            store.saveUserRanges(ranges)
            val user = CleanIpRanges.parseUser(ranges)
            val ips = buildList {
                addAll(CleanIpRanges.sampleV4(user.ifEmpty { CleanIpRanges.V4 }, count))
                if (ipv6) addAll(CleanIpRanges.sampleV6(count / 4))
            }
            total = ips.size; done = 0; results = emptyList()
            val samples = CleanIpProbe.measureAll(ips, CleanIpProbe.Options(sni = sni.trim().ifBlank { "speed.cloudflare.com" }), parallel = 16) {
                done++
            }
            results = CleanIpScoring.rank(samples).take(30)
            store.save(results.map { it.sample.ip }, sni)
            message = if (results.isEmpty()) "هیچ آی‌پی سالمی با این SNI پیدا نشد. SNI دیگری امتحان کنید." else "${results.size} آی‌پی سالم پیدا شد."
            job = null
        }
    }

    ToolPageScaffold(
        accent = accent,
        header = {
            PowCompactHeader(accent = accent, onBack = onBack, title = "اسکنر آی‌پی تمیز", subtitle = "Cloudflare · با SNI کانفیگ خودتان", icon = Icons.Outlined.CleaningServices)
        },
    ) {
        item(key = "intro") {
            EngineCard {
                EngineBody(
                    "آی‌پی‌ها مستقیم از خط خودتان سنجیده می‌شوند: اتصال TCP، دست‌دهی TLS با همان SNI کانفیگ، و پاسخ واقعی کلودفلر. " +
                        "آی‌پی‌ای که TCP دارد ولی TLS را فیلتر می‌کند تمیز حساب نمی‌شود." +
                        if (connected) "\nتونل وصل است؛ اسکن همچنان از خط مستقیم انجام می‌شود (سیمرغ از تونل خودش مستثناست)." else "",
                )
                OutlinedTextField(sni, { sni = it.trim().take(253) }, Modifier.fillMaxWidth(), label = { Text("SNI") }, singleLine = true)
                OutlinedTextField(
                    ranges, { ranges = it.take(20_000) }, Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    label = { Text("رنج‌ها یا آی‌پی‌های خودتان (اختیاری، هر خط یکی)") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 120, 300).forEach { n -> EngineChip("$n آی‌پی", count == n) { count = n } }
                    EngineChip("IPv6 هم", ipv6) { ipv6 = !ipv6 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job == null) EnginePrimaryButton("شروع اسکن") { scan() }
                    else EngineSecondaryButton("توقف") { job?.cancel(); job = null }
                }
                if (job != null && total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    EngineBody("$done از $total")
                }
                message?.let { EngineBody(it, color = UacColors.TextPrimary) }
            }
        }
        if (results.isNotEmpty()) {
            item(key = "apply") {
                EngineCard {
                    EngineTitle("استفاده")
                    EngineBody("بهترین آی‌پی‌ها برای همهٔ کانفیگ‌های CDN (WS / gRPC / XHTTP / HTTPUpgrade با TLS) روی همین شبکه به‌کار می‌روند. کانفیگ‌های REALITY و ECH دست نمی‌خورند.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnginePrimaryButton("استفاده برای کانفیگ‌هایم") {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { store.applyToConfigs(results.map { it.sample.ip }) }
                                message = if (n == 0) "کانفیگ CDN ای در Xray نیست." else "برای $n کانفیگ روی این شبکه تنظیم شد."
                            }
                        }
                        EngineSecondaryButton("کپی") {
                            copy(context, results.joinToString("\n") { it.sample.ip })
                            message = "کپی شد"
                        }
                    }
                }
            }
        }
        items(results, key = { it.sample.ip }) { r ->
            EngineCard {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(r.sample.ip, color = UacColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            "${r.sample.medianMs} ms · پایداری ${(r.sample.successRate * 100).toInt()}٪" +
                                (r.sample.jitterMs?.let { " · نوسان $it ms" } ?: "") + (r.sample.colo?.let { " · $it" } ?: ""),
                            color = UacColors.TextSecondary, fontSize = 11.5.sp,
                        )
                    }
                    Text("${r.score.toInt()}", color = UacColors.ConnectedGreen, fontSize = 16.sp)
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("clean ips", text))
    }
}
