package com.uacspoofer.mobile.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.DrawerDestination

private val FAQ = listOf(
    "کدام موتور را انتخاب کنم؟" to "اگر مطمئن نیستی «خودکار» را روشن کن. سیمرغ موتورها را به ترتیبی که در صفحه‌ی موتورها چیده‌ای امتحان می‌کند و موتوری که روی هر شبکه وصل شده را برای دفعه‌ی بعد به خاطر می‌سپارد.",
    "پرواز (Xray) چیست؟" to "با کانفیگ‌ها و لینک اشتراک خودت کار می‌کند. از سرورها ← + کانفیگ را از کلیپ‌بورد، فایل یا تصویر QR وارد کن.",
    "روباه (فایرفاکس) چطور کار می‌کند؟" to "با حساب فایرفاکس خودت وارد می‌شوی (هیچ حساب مشترکی در برنامه نیست). بعد از ورود، کشورهای موزیلا با پینگ واقعی به فهرست پرسرعت‌ها اضافه می‌شوند.",
    "سایه و شاهپر" to "سایه مسیر چندلایه‌ی Tor با پل‌های WebTunnel است و کشور خروجی را می‌شود انتخاب کرد. شاهپر زنجیره‌ی نور (WARP)، ابر (Psiphon) و پل است. پینگ این دو فقط بعد از اتصال قابل اندازه‌گیری واقعی است.",
    "پینگ‌ها واقعی‌اند؟" to "بله. هیچ عددی تخمینی نیست: کانفیگ‌ها با تست واقعی تأخیر، سرورهای موزیلا با اتصال TCP و دست‌دادن TLS، و اتصال فعلی با درخواست واقعی از داخل تونل اندازه‌گیری می‌شوند.",
    "کشور و IP روی نقشه از کجا می‌آید؟" to "بعد از اتصال، IP خروجی از داخل تونل با DB-IP (همان داده‌ی browserleaks) و Cloudflare بررسی می‌شود. اگر کشور خروجی واقعی با کشور انتخابی فرق داشته باشد، کشور واقعی نشان داده می‌شود.",
    "تست سرعت" to "بین‌الملل با سرورهای Ookla (نزدیک‌ترین سرور با پینگ واقعی) و داخلی با سرور شاتل انجام می‌شود. وقتی وصل هستی، سرعت از داخل تونل اندازه‌گیری می‌شود.",
    "مصرف باتری" to "از پروفایل ← حالت باتری، «کم‌مصرف» یا «فوق‌کم‌مصرف» را انتخاب کن تا بررسی‌های دوره‌ای و انیمیشن‌ها کم یا خاموش شوند.",
)

@Composable
internal fun GuideTab(onOpenLegacy: (DrawerDestination) -> Unit, onOpenEngines: () -> Unit, contentPadding: PaddingValues) {
    val sx = LocalSx.current
    var open by rememberSaveable { mutableIntStateOf(-1) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "t") { ScreenTitle("راهنما") }
        itemsIndexed(FAQ, key = { i, _ -> "q$i" }) { i, (q, a) ->
            Column(Modifier.fillMaxWidth().sxCard(sx).sxClick { open = if (open == i) -1 else i }.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(q, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (open == i) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = sx.textDim)
                }
                AnimatedVisibility(open == i) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        SText(a, size = 13.5.sp, color = sx.textDim, maxLines = 10)
                    }
                }
            }
        }
        item(key = "more") {
            Column(Modifier.fillMaxWidth().sxCard(sx)) {
                MenuRow(SubEngines.AUTO_ICON, "صفحه‌ی موتورها", "وضعیت، ترتیب و خودکار") { onOpenEngines() }
                DividerLine()
                MenuRow(Icons.Outlined.SupportAgent, "پشتیبانی", "ارتباط و گزارش مشکل") { onOpenLegacy(DrawerDestination.SUPPORT) }
            }
        }
    }
}
