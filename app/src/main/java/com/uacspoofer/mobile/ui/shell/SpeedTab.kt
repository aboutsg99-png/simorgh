package com.uacspoofer.mobile.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.countryName
import com.uacspoofer.mobile.simorgh.speed.SpeedHistoryStore
import com.uacspoofer.mobile.simorgh.speed.SpeedPhase
import com.uacspoofer.mobile.simorgh.speed.SpeedProvider
import com.uacspoofer.mobile.simorgh.speed.SpeedResult
import com.uacspoofer.mobile.simorgh.speed.SpeedTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

private val TICKS = listOf(0, 1, 5, 10, 50, 100, 250, 500, 1000)

/** Log scale 0..1000 Mbps -> 0..1, with 0 pinned to the start. */
private fun gaugeFraction(mbps: Double): Float {
    if (mbps <= 0.0) return 0f
    val v = log10(1.0 + mbps.coerceAtMost(1000.0)) / log10(1001.0)
    return v.toFloat().coerceIn(0f, 1f)
}

@Composable
fun SpeedTab(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val live by SpeedTestRunner.live.collectAsStateWithLifecycle()
    val history by remember(context) { SpeedHistoryStore.get(context) }.results.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val location by LocationState.current.collectAsStateWithLifecycle()
    var provider by rememberSaveable { mutableStateOf(SpeedProvider.OOKLA) }
    val running = live.phase in setOf(SpeedPhase.FINDING, SpeedPhase.PING, SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD)
    val tunnel = connection == ConnectionState.CONNECTED
    val last = history.firstOrNull { it.provider == provider }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title") { ScreenTitle("تست سرعت") }
        item(key = "tabs") {
            Row(Modifier.fillMaxWidth().sxCard(sx).padding(6.dp)) {
                SpeedProvider.entries.forEach { p ->
                    val on = p == provider
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (on) sx.accent.copy(alpha = 0.16f) else Color.Transparent)
                            .sxClick { if (!running) { provider = p; SpeedTestRunner.reset(p) } }
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (p == SpeedProvider.OOKLA) Icons.Outlined.Public else Icons.Outlined.Apartment, null, tint = if (on) sx.accent else sx.textDim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        SText(p.titleFa, size = 14.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) sx.accent else sx.textDim)
                    }
                }
            }
        }
        item(key = "server") {
            val server = live.server?.takeIf { live.provider == provider }
            Row(Modifier.fillMaxWidth().sxCard(sx).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Outlined.Storage)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SText(
                        server?.let { (if (provider == SpeedProvider.OOKLA) "Ookla · " else "") + it.label } ?: if (provider == SpeedProvider.OOKLA) "Ookla Speedtest" else "شاتل · speed-interior.shatel.ir",
                        size = 15.sp, weight = FontWeight.Bold,
                    )
                    SText(
                        if (provider == SpeedProvider.OOKLA) "انتخاب خودکار نزدیک‌ترین سرور با پینگ واقعی" else "سرور سرعت‌سنج داخلی شاتل (LibreSpeed)",
                        size = 12.sp, color = sx.textDim,
                    )
                }
                server?.latencyMs?.let { PingPill(it) }
            }
        }
        item(key = "gauge") {
            Gauge(
                value = if (running) live.liveMbps else (if (live.phase == SpeedPhase.DONE && live.provider == provider) live.downMbps ?: 0.0 else 0.0),
                phase = if (live.provider == provider) live.phase else SpeedPhase.IDLE,
                onStart = {
                    if (running) SpeedTestRunner.cancel() else SpeedTestRunner.start(context, provider) { SpeedHistoryStore.get(context).add(it) }
                },
            )
        }
        item(key = "route") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (tunnel) Icons.Outlined.GppGood else Icons.Outlined.GppMaybe, null, tint = if (tunnel) sx.accent else sx.warn, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                SText(
                    if (tunnel) "متصل از طریق ${location.engine.fa}؛ سرعت از داخل تونل اندازه‌گیری می‌شود" else "بدون تونل؛ سرعت اینترنت مستقیم شما اندازه‌گیری می‌شود",
                    size = 12.5.sp, color = sx.textDim, maxLines = 2,
                )
            }
            if (live.phase == SpeedPhase.FAILED && live.provider == provider) {
                SText(live.error, size = 12.5.sp, color = sx.bad, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        item(key = "result-head") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                SText("آخرین نتیجه", size = 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                last?.let { SText(timeLabel(it.atMs), size = 12.sp, color = sx.textDim) }
            }
        }
        item(key = "result-grid") {
            val showLive = live.provider == provider && live.phase != SpeedPhase.IDLE
            val down = if (showLive) live.downMbps else last?.downMbps
            val up = if (showLive) live.upMbps else last?.upMbps
            val ping = if (showLive) live.pingMs else last?.pingMs
            val jitter = if (showLive) live.jitterMs else last?.jitterMs
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric(Icons.Outlined.Download, "دانلود", mbps(down), "Mbps", Modifier.weight(1f), sx.accent)
                    Metric(Icons.Outlined.Upload, "آپلود", mbps(up), "Mbps", Modifier.weight(1f), Color(0xFF6AA8FF))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric(Icons.Outlined.SwapVert, "پینگ", ping?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "ms", Modifier.weight(1f), sx.textDim)
                    Metric(Icons.Outlined.Waves, "جیتر", jitter?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "ms", Modifier.weight(1f), sx.textDim)
                }
                Column(Modifier.fillMaxWidth().sxCard(sx)) {
                    val serverLabel = if (showLive) live.server?.label else last?.server
                    InfoRow("سرور", serverLabel ?: "—")
                    DividerLine()
                    val routeTunnel = if (showLive) live.viaTunnel else last?.viaTunnel == true
                    val routeEngine = if (showLive) live.engine?.fa else EngineMode.entries.firstOrNull { it.id == last?.engineId }?.fa
                    InfoRow(
                        "مسیر",
                        if (routeTunnel) "ایران ← تونل ${routeEngine ?: ""} ← ${countryName(location.connectedCode).ifEmpty { "خارج" }}" else "مستقیم (بدون تونل)",
                    )
                }
            }
        }
        if (history.isNotEmpty()) {
            item(key = "hist-head") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SText("نتایج قبلی", size = 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(8.dp).background(sx.accent, CircleShape)); Spacer(Modifier.width(4.dp))
                    SText("دانلود", size = 12.sp, color = sx.textDim); Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(8.dp).background(Color(0xFF6AA8FF), CircleShape)); Spacer(Modifier.width(4.dp))
                    SText("آپلود", size = 12.sp, color = sx.textDim)
                }
            }
            item(key = "chart") { HistoryChart(history.take(12).reversed()) }
            item(key = "hist-list") {
                Column(Modifier.fillMaxWidth().sxCard(sx)) {
                    history.take(10).forEachIndexed { i, r ->
                        if (i > 0) DividerLine()
                        HistoryRow(r)
                    }
                }
            }
            item(key = "clear") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).sxClick { SpeedHistoryStore.get(context).clear() }.padding(12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    SText("پاک کردن نتایج", size = 15.sp, color = sx.bad, weight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.DeleteOutline, null, tint = sx.bad)
                }
            }
        }
    }
}

private fun timeLabel(ms: Long): String {
    val day = relativeDayFa(ms)
    return "$day " + SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
}

@Composable
private fun Gauge(value: Double, phase: SpeedPhase, onStart: () -> Unit) {
    val sx = LocalSx.current
    val target = gaugeFraction(value)
    val frac by animateFloatAsState(target, tween(if (sx.anim == AnimLevel.OFF) 0 else 250), label = "gauge")
    val running = phase in setOf(SpeedPhase.FINDING, SpeedPhase.PING, SpeedPhase.DOWNLOAD, SpeedPhase.UPLOAD)
    val color = if (phase == SpeedPhase.UPLOAD) Color(0xFF6AA8FF) else sx.accent
    BoxWithConstraints(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
        val track = sx.surfaceHigh
        val tickColor = sx.textFaint
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val r = minOf(size.width / 2.2f, size.height * 0.62f)
            val c = Offset(size.width / 2, size.height * 0.66f)
            val tl = Offset(c.x - r, c.y - r)
            // Gauge runs left (0) to right (1000) over the top: 180deg -> 360deg.
            drawArc(track, 180f, 180f, false, tl, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color), center = c),
                180f, 180f * frac, false, tl, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val a = Math.toRadians((180.0 + 180.0 * frac))
            drawCircle(color, stroke * 0.55f, Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat()))
            for (i in 0..60) {
                val f = i / 60f
                val ang = Math.toRadians(180.0 + 180.0 * f)
                val r1 = r - stroke * 1.2f
                val r2 = r1 - if (i % 5 == 0) 10.dp.toPx() else 5.dp.toPx()
                drawLine(
                    tickColor.copy(alpha = if (i % 5 == 0) 0.7f else 0.35f),
                    Offset(c.x + (r1 * kotlin.math.cos(ang)).toFloat(), c.y + (r1 * kotlin.math.sin(ang)).toFloat()),
                    Offset(c.x + (r2 * kotlin.math.cos(ang)).toFloat(), c.y + (r2 * kotlin.math.sin(ang)).toFloat()),
                    strokeWidth = 1.5f,
                )
            }
        }
        // Tick labels
        val w = maxWidth
        TICKS.forEach { t ->
            val f = gaugeFraction(t.toDouble())
            val ang = Math.toRadians(180.0 + 180.0 * f)
            val rr = (minOf(w.value / 2.2f, 250f * 0.62f) - 56f)
            val x = (rr * kotlin.math.cos(ang)).toFloat()
            val y = (250f * 0.66f - 125f) + (rr * kotlin.math.sin(ang)).toFloat()
            Box(Modifier.align(Alignment.Center).padding(0.dp).offsetDp(x, y)) {
                SText("$t", size = 10.5.sp, color = sx.textDim, ltr = true)
            }
        }
        Column(Modifier.align(Alignment.Center).offsetDp(0f, 28f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(128.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(color, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.25f))))
                    .sxClick(onStart),
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SText(mbps(value), size = 28.sp, weight = FontWeight.Bold, color = sx.onAccent, ltr = true)
                        SText(
                            when (phase) {
                                SpeedPhase.FINDING -> "یافتن سرور"
                                SpeedPhase.PING -> "پینگ"
                                SpeedPhase.DOWNLOAD -> "دانلود Mbps"
                                else -> "آپلود Mbps"
                            },
                            size = 11.sp, color = sx.onAccent,
                        )
                    }
                } else {
                    SText(if (phase == SpeedPhase.DONE) "دوباره" else "شروع", size = 26.sp, weight = FontWeight.Bold, color = sx.onAccent)
                }
            }
        }
    }
}

private fun Modifier.offsetDp(x: Float, y: Float): Modifier = this.absoluteOffset(x.dp, y.dp)

@Composable
private fun Metric(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier, tint: Color) {
    val sx = LocalSx.current
    Column(modifier.sxCard(sx).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText(label, size = 13.sp, color = sx.textDim)
            Spacer(Modifier.width(6.dp))
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            SText(value, size = 28.sp, weight = FontWeight.Bold, ltr = true)
            Spacer(Modifier.width(6.dp))
            SText(unit, size = 13.sp, color = sx.textDim)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SText(label, size = 13.sp, color = sx.textDim, modifier = Modifier.width(56.dp))
        SText(value, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

@Composable
private fun HistoryChart(points: List<SpeedResult>) {
    val sx = LocalSx.current
    val upColor = Color(0xFF6AA8FF)
    Box(Modifier.fillMaxWidth().height(150.dp).sxCard(sx).padding(16.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (points.isEmpty()) return@Canvas
            val maxV = max(points.maxOf { max(it.downMbps, it.upMbps) }, 1.0)
            val n = points.size
            fun x(i: Int) = if (n == 1) size.width / 2 else size.width - i * size.width / (n - 1) // RTL: newest on the left
            fun y(v: Double) = (size.height - (v / maxV * size.height * 0.9)).toFloat()
            for (g in 1..3) drawLine(sx.stroke, Offset(0f, size.height * g / 4f), Offset(size.width, size.height * g / 4f), 1f)
            for ((series, color) in listOf(points.map { it.downMbps } to sx.accent, points.map { it.upMbps } to upColor)) {
                val path = Path()
                series.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(color, 4.dp.toPx(), Offset(x(n - 1), y(series.last())))
            }
        }
    }
}

@Composable
private fun HistoryRow(r: SpeedResult) {
    val sx = LocalSx.current
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBox(Icons.Outlined.Speed, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SText((if (r.provider == SpeedProvider.OOKLA) "Ookla · " else "") + r.server, size = 14.sp, weight = FontWeight.Bold)
            val route = if (r.viaTunnel) EngineMode.entries.firstOrNull { it.id == r.engineId }?.fa ?: "تونل" else "بدون تونل"
            SText("${timeLabel(r.atMs)} · $route", size = 12.sp, color = sx.textDim)
        }
        Column(horizontalAlignment = Alignment.End) {
            SText("↓ ${mbps(r.downMbps)}", size = 14.sp, weight = FontWeight.Bold, color = sx.accent, ltr = true)
            SText("↑ ${mbps(r.upMbps)} Mbps", size = 12.sp, color = sx.textDim, ltr = true)
        }
    }
}
