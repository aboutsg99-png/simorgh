package com.uacspoofer.mobile.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** One guide topic: a question and its answer, optionally with a page to open. */
internal data class GuideItem(val q: String, val a: String, val action: Pair<String, Route>? = null)

internal data class GuideSection(val key: String, val title: String, val intro: String, val items: List<GuideItem>)

internal val GUIDE: List<GuideSection> = listOf(
    GuideSection(
        "start", "شروع سریع", "سه قدم تا اتصال امن.",
        listOf(
            GuideItem("۱. دکمه‌ی اتصال را بزنید", "در صفحه‌ی VPN دکمه‌ی بزرگ وسط را لمس کنید. بار اول اندروید اجازه‌ی ساخت وی‌پی‌ان را می‌پرسد؛ «تأیید» را بزنید. وقتی دایره سبز شد و «محافظت شده» نوشته شد، وصل هستید."),
            GuideItem("۲. مکان را انتخاب کنید", "کارت «مکان» زیر دکمه را لمس کنید. «مکان هوشمند» سریع‌ترین سرور اندازه‌گیری‌شده را برمی‌دارد. با ستاره، کشورهای دلخواه را در «علاقه‌مندی‌ها» نگه دارید."),
            GuideItem("۳. اگر وصل نشد", "موتور را روی Auto بگذارید تا بلک دی خودش موتورها را امتحان کند. اگر کانفیگ خودتان را دارید، از «سرورها و کانفیگ‌ها» آن را اضافه کنید.", "تنظیمات Auto" to Route.AUTO_SETTINGS),
        ),
    ),
    GuideSection(
        "engines", "موتورها", "هر موتور راه متفاوتی برای عبور از فیلتر است.",
        listOf(
            GuideItem("کدام موتور را انتخاب کنم؟", "اگر مطمئن نیستید Auto را روشن بگذارید. Auto موتورها را به ترتیبی که در تنظیمات Auto چیده‌اید امتحان می‌کند و موتوری که روی هر شبکه وصل شده را برای دفعه‌ی بعد به خاطر می‌سپارد.", "تنظیمات Auto" to Route.AUTO_SETTINGS),
            GuideItem("Xray", "با کانفیگ‌ها و لینک اشتراک خودتان کار می‌کند (VLESS، VMess، Trojan، Reality). معمولاً سریع‌ترین گزینه است.", "تنظیمات Xray" to Route.XRAY_SETTINGS),
            GuideItem("Cloudflare WARP و Psiphon", "بدون نیاز به کانفیگ. WARP تونل WireGuard/MASQUE کلودفلر است و Psiphon شبکه‌ی مقاوم Psiphon. «Psiphon + WARP» دو لایه است: کندتر اما در فیلترینگ سنگین پایدارتر.", "تنظیمات WARP و Psiphon" to Route.POW_SETTINGS),
            GuideItem("Firefox VPN", "با حساب فایرفاکس خودتان وارد می‌شوید (هیچ حساب مشترکی در برنامه نیست). بعد از ورود، کشورهای موزیلا با پینگ واقعی در فهرست مکان‌ها می‌آیند.", "Firefox VPN" to Route.FIREFOX),
            GuideItem("Tor + WebTunnel", "مسیر چندلایه‌ی Tor از روی پل‌های WebTunnel. بیشترین ناشناسی را دارد ولی کندتر است؛ کشور خروجی را می‌شود انتخاب کرد.", "تنظیمات Tor" to Route.TOR_SETTINGS),
            GuideItem("WireGuard", "کانفیگ WireGuard خودتان، یا Proton VPN و Windscribe با حساب خودتان.", "WireGuard" to Route.WIREGUARD),
            GuideItem("Direct", "بدون سرور: فقط بسته‌ها طوری فرستاده می‌شوند که فیلتر (DPI) آن‌ها را نشناسد. IP شما عوض نمی‌شود.", "تنظیمات Direct" to Route.DIRECT_SETTINGS),
        ),
    ),
    GuideSection(
        "configs", "کانفیگ و اشتراک", "افزودن، به‌روزرسانی و اشتراک‌گذاری.",
        listOf(
            GuideItem("افزودن کانفیگ", "در «سرورها و کانفیگ‌ها» دکمه‌ی + را بزنید و یکی را انتخاب کنید: از کلیپ‌بورد، از فایل، از تصویر QR، یا چسباندن لینک اشتراک.", "سرورها و کانفیگ‌ها" to Route.SERVERS),
            GuideItem("لینک اشتراک (Subscription)", "لینکی که با http یا https شروع می‌شود و چند کانفیگ را با هم می‌آورد. بلک دی آن را ذخیره می‌کند و با «به‌روزرسانی» کانفیگ‌های تازه را می‌گیرد. اگر سرور حجم و تاریخ انقضا را بفرستد، کنار اشتراک نشان داده می‌شود."),
            GuideItem("اشتراک‌گذاری با دیگران", "هر کانفیگ یا لینک اشتراک را می‌شود کپی کرد، به‌صورت QR نشان داد یا با برنامه‌های دیگر فرستاد.", "اشتراک‌گذاری" to Route.SHARE),
        ),
    ),
    GuideSection(
        "trouble", "حل مشکل", "رایج‌ترین مشکل‌ها و راه حل‌شان.",
        listOf(
            GuideItem("وصل نمی‌شود", "۱) Auto را روشن کنید. ۲) اگر کانفیگ دارید، در «سرورها» پینگ همه را بگیرید و سریع‌ترین را انتخاب کنید. ۳) شبکه را عوض کنید (وای‌فای ↔ موبایل). ۴) اگر باز هم نشد، از «گزارش مشکل» فایل لاگ را بفرستید.", "گزارش مشکل" to Route.REPORT),
            GuideItem("با خاموش شدن صفحه قطع می‌شود", "اندروید برنامه را برای صرفه‌جویی می‌بندد. در «باتری و مصرف»، بلک دی را از بهینه‌سازی باتری خارج کنید.", "باتری و مصرف" to Route.BATTERY),
            GuideItem("یک برنامه با وی‌پی‌ان کار نمی‌کند", "برنامه‌های بانکی یا ایرانی را خارج از تونل بگذارید تا مستقیم وصل شوند.", "برنامه‌های خارج از تونل" to Route.APP_BYPASS),
            GuideItem("سایت‌های ایرانی باز نمی‌شوند یا کندند", "در تنظیمات، «سایت‌های ایرانی مستقیم» را روشن کنید تا دامنه‌های ‎.ir‎ و IPهای ایران بدون تونل باز شوند.", "تنظیمات" to Route.SETTINGS),
            GuideItem("سرعت کم است", "در «تست سرعت» سرعت را با و بدون وی‌پی‌ان مقایسه کنید. موتورهای دولایه (Tor و Psiphon + WARP) کندترند؛ برای سرعت Xray یا WireGuard را امتحان کنید."),
        ),
    ),
    GuideSection(
        "privacy", "حریم خصوصی", "بلک دی چه چیزی را اندازه می‌گیرد.",
        listOf(
            GuideItem("پینگ‌ها واقعی‌اند؟", "بله. هیچ عددی تخمینی نیست: کانفیگ‌ها با تست واقعی تأخیر، سرورهای موزیلا با اتصال TCP و دست‌دادن TLS، و اتصال فعلی با درخواست واقعی از داخل تونل اندازه‌گیری می‌شوند."),
            GuideItem("کشور و IP روی نقشه از کجا می‌آید؟", "بعد از اتصال، IP خروجی از داخل تونل با DB-IP و Cloudflare بررسی می‌شود. اگر کشور خروجی واقعی با کشور انتخابی فرق داشته باشد، کشور واقعی نشان داده می‌شود."),
            GuideItem("چه داده‌ای فرستاده می‌شود؟", "بلک دی هیچ آماری از شما جمع نمی‌کند. فایل لاگ فقط وقتی خودتان آن را ذخیره یا ارسال کنید از گوشی خارج می‌شود."),
        ),
    ),
)

/** «راهنما»: quick start first, then engines, configs, troubleshooting and privacy, each as its own section. */
@Composable
internal fun GuideTab(onOpen: (Route) -> Unit, onOpenEngines: () -> Unit, contentPadding: PaddingValues) {
    val sx = LocalSx.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf("") }
    // Item index of each section header (title row + chips row come first).
    val headerIndex = remember(GUIDE) {
        var i = 2
        GUIDE.associate { s -> (s.key to i).also { i += 1 + s.items.size } }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "t") { ScreenTitle("راهنما") }
        item(key = "chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GUIDE, key = { it.key }) { s ->
                    SChip(s.title, onClick = { scope.launch { listState.animateScrollToItem(headerIndex[s.key] ?: 0) } })
                }
            }
        }
        GUIDE.forEachIndexed { si, section ->
            item(key = "h:" + section.key) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(sx.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        SText("${si + 1}", size = 13.sp, weight = FontWeight.Bold, color = sx.accent)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        SText(section.title, size = 17.sp, weight = FontWeight.Bold)
                        SText(section.intro, size = 12.5.sp, color = sx.textDim)
                    }
                }
            }
            section.items.forEachIndexed { ii, it ->
                val id = section.key + ii
                item(key = "q:$id") {
                    GuideCard(it, open == id, onToggle = { open = if (open == id) "" else id }, onOpen = onOpen)
                }
            }
        }
        item(key = "more") {
            Column(Modifier.padding(top = 14.dp)) {
                SGroup("هنوز سؤال دارید؟") {
                    SRow(Icons.AutoMirrored.Outlined.Send, "کانال رسمی تلگرام", SIMORGH_TELEGRAM_HANDLE) { openTelegramChannel(context) }
                    RowDivider()
                    SRow(Icons.Outlined.BugReport, "گزارش مشکل", "فایل لاگ و مشخصات دستگاه را بفرستید") { onOpen(Route.REPORT) }
                }
            }
        }
    }
}

@Composable
private fun GuideCard(item: GuideItem, expanded: Boolean, onToggle: () -> Unit, onOpen: (Route) -> Unit) {
    val sx = LocalSx.current
    Column(Modifier.fillMaxWidth().sxCard(sx).sxClick(onToggle).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText(item.q, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = sx.textDim)
        }
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(8.dp))
                SParagraph(item.a)
                item.action?.let { (label, route) ->
                    Spacer(Modifier.height(10.dp))
                    SChip(label, selected = true, onClick = { onOpen(route) })
                }
            }
        }
    }
}
