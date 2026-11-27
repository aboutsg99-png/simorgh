package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.logging.AppLogEntry
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.BrandText
import com.uacspoofer.mobile.logging.LogLevel
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.ui.LogExportSheet

/** Live log: the in-memory event stream of the app, the VPN service and every engine. */
@Composable
internal fun LiveLogPage(onBack: () -> Unit) {
    val sx = LocalSx.current
    val logs by AppLogRepository.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var exportVisible by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<LogSource?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val visible = remember(logs, source) { source?.let { s -> logs.filter { it.source == s } } ?: logs }
    LaunchedEffect(visible.lastOrNull()?.id, follow) {
        if (follow && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(2_000); notice = null } }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(sx.bgTop).padding(top = top)) {
        PageHeader("لاگ زنده", onBack, "رویدادهای برنامه، سرویس VPN و موتورها، لحظه به لحظه") {
            LiveDot()
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).sxCard(sx).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SText("${visible.size} رویداد", size = 15.sp, weight = FontWeight.Bold)
                SText(if (follow) "دنبال کردن خودکار روشن" else "دنبال کردن متوقف", size = 12.sp, color = sx.textDim)
            }
            RoundIconButtonFlat(if (follow) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, { follow = !follow }, description = if (follow) "توقف دنبال کردن" else "دنبال کردن", tint = if (follow) sx.accent else sx.text)
            RoundIconButtonFlat(Icons.Outlined.IosShare, { exportVisible = true }, description = "ذخیره‌ی فایل لاگ")
            RoundIconButtonFlat(Icons.Outlined.ContentCopy, {
                clipboard.setText(AnnotatedString(AppLogRepository.snapshotText()))
                notice = "لاگ کپی شد"
            }, description = "کپی لاگ")
            RoundIconButtonFlat(Icons.Outlined.DeleteSweep, { AppLogRepository.clear() }, description = "پاک کردن لاگ", tint = sx.bad)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SChip("همه", selected = source == null) { source = null }
            LogSource.entries.forEach { s ->
                SChip(BrandText.apply(s.label), selected = source == s) { source = if (source == s) null else s }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).sxCard(sx)) {
            if (visible.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.ListAlt, null, tint = sx.textFaint, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(8.dp))
                    SText("هنوز رویدادی ثبت نشده", size = 13.5.sp, color = sx.textDim)
                }
            } else {
                // Log lines are English and technical: read them left to right.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible, key = { it.id }, contentType = { "log" }) { LogLine(it) }
                    }
                }
            }
            notice?.let {
                Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).sxCard(sx, highlight = true).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    SText(it, size = 13.sp)
                }
            }
        }
        SText(
            "فقط در حافظه · ${logs.size} از ۲۰۰۰ رویداد آخر",
            size = 11.5.sp, color = sx.textFaint,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = bottom + 12.dp),
        )
    }
    if (exportVisible) LogExportSheet(onDismiss = { exportVisible = false })
}

@Composable
private fun LiveDot() {
    val sx = LocalSx.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(sx.good, CircleShape))
        Spacer(Modifier.width(6.dp))
        SText("زنده", size = 12.5.sp, weight = FontWeight.Bold, color = sx.good)
    }
}

@Composable
internal fun logLevelColor(level: LogLevel): Color {
    val sx = LocalSx.current
    return when (level) {
        LogLevel.DEBUG -> sx.textFaint
        LogLevel.INFO -> sx.accent
        LogLevel.SUCCESS -> sx.good
        LogLevel.WARNING -> sx.warn
        LogLevel.ERROR -> sx.bad
    }
}

@Composable
private fun LogLine(entry: AppLogEntry) {
    val sx = LocalSx.current
    val tone = logLevelColor(entry.level)
    Row(
        Modifier.fillMaxWidth().background(sx.surfaceHigh.copy(alpha = if (sx.light) 0.45f else 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).background(tone, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.timestamp, color = sx.textDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(BrandText.apply(entry.source.label), color = tone, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                BrandText.apply(entry.message),
                color = sx.text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp,
                maxLines = 8, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
