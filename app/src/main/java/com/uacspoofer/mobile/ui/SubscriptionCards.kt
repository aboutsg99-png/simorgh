package com.uacspoofer.mobile.ui

import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.profiles.Subscription
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Bytes as GB (two decimals) from 1 GB up, otherwise MB. */
internal fun formatSubscriptionBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f GB", gb)
    } else {
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatDate(millis: Long, persian: Boolean, withTime: Boolean): String {
    val pattern = if (withTime) "yyyy/MM/dd HH:mm" else "yyyy/MM/dd"
    val locale = if (persian) ULocale("fa_IR@calendar=persian") else ULocale("en_US")
    return runCatching { SimpleDateFormat(pattern, locale).format(Date(millis)) }
        .getOrElse { java.text.SimpleDateFormat(pattern, Locale.US).format(Date(millis)) }
}

@Composable
internal fun SubscriptionCard(
    subscription: Subscription,
    configCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val persian = LocalHomePersian.current
    val accent = UacColors.DisconnectedBlue
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (Neo.active) {
                    Modifier.neoSurface(Neo.RowShape, depth = 4.dp).clip(Neo.RowShape)
                } else {
                    Modifier
                        .background(Color(0x1FFFFFFF), shape)
                        .border(1.dp, accent.copy(alpha = 0.22f), shape)
                },
            )
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    subscription.name,
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    homeText("Subscription · $configCount configs", "اشتراک · $configCount کانفیگ"),
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(homeText("Update", "به‌روزرسانی"), color = accent, fontSize = 12.sp)
            }
            IconButton(onClick = onDelete, enabled = !refreshing) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = homeText("Delete subscription", "حذف اشتراک"),
                    tint = UacColors.TextSecondary,
                )
            }
        }

        val info = subscription.userInfo
        val total = info?.total
        if (info != null && !info.unlimited && total != null) {
            val used = info.used
            val remaining = info.remaining ?: 0L
            val fraction = (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            val barColor = when {
                fraction >= 0.9f -> UacColors.ErrorRed
                fraction >= 0.75f -> UacColors.DisconnectingAmber
                else -> UacColors.ConnectedGreen
            }
            InfoLine(
                Icons.Outlined.DataUsage,
                homeText(
                    "Remaining: ${formatSubscriptionBytes(remaining)}",
                    "باقی‌مانده: ${formatSubscriptionBytes(remaining)}",
                ),
                UacColors.TextPrimary,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = UacColors.Divider,
                strokeCap = StrokeCap.Round,
            )
            Text(
                homeText(
                    "Used ${formatSubscriptionBytes(used)} of ${formatSubscriptionBytes(total)}",
                    "مصرف‌شده ${formatSubscriptionBytes(used)} از ${formatSubscriptionBytes(total)}",
                ),
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
            )
        } else {
            InfoLine(
                Icons.Outlined.DataUsage,
                homeText("Data: Unlimited", "حجم: نامحدود"),
                UacColors.TextPrimary,
            )
        }

        val expire: Long = info?.expire?.takeIf { it > 0L } ?: 0L
        when {
            expire <= 0L ->
                InfoLine(Icons.Outlined.Event, homeText("Expiry: Unlimited", "انقضا: نامحدود"), UacColors.TextSecondary)
            info?.expired() == true ->
                InfoLine(Icons.Outlined.Event, homeText("Expired", "منقضی"), UacColors.ErrorRed)
            else -> {
                val millis = expire * 1000L
                val days = ceil((millis - System.currentTimeMillis()) / 86_400_000.0).toLong().coerceAtLeast(0L)
                val date = formatDate(millis, persian, withTime = false)
                InfoLine(
                    Icons.Outlined.Event,
                    homeText("Expires $date ($days days left)", "انقضا: $date ($days روز مانده)"),
                    if (days <= 3) UacColors.DisconnectingAmber else UacColors.TextSecondary,
                )
            }
        }

        if (subscription.unsupportedCount > 0) {
            InfoLine(
                Icons.Outlined.WarningAmber,
                homeText(
                    "${subscription.unsupportedCount} ss/hysteria2 links skipped (not supported yet)",
                    "${subscription.unsupportedCount} لینک ss/hysteria2 رد شد (هنوز پشتیبانی نمی‌شود)",
                ),
                UacColors.DisconnectingAmber,
            )
        }
        if (subscription.updatedAt > 0L) {
            Text(
                homeText(
                    "Last updated ${formatDate(subscription.updatedAt, persian, withTime = true)}",
                    "آخرین به‌روزرسانی: ${formatDate(subscription.updatedAt, persian, withTime = true)}",
                ),
                color = UacColors.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.85f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp)
    }
}
