package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom sheet behind the + on the servers page: every way to add a config or subscription. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddConfigSheet(
    onDismiss: () -> Unit,
    onClipboard: () -> Unit,
    onFile: () -> Unit,
    onQr: () -> Unit,
    onText: (String) -> Unit,
    onManage: () -> Unit,
    onShare: () -> Unit,
) {
    val sx = LocalSx.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var link by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = sx.bgTop,
        contentColor = sx.text,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(sx.textFaint.copy(alpha = 0.5f)))
        },
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                SText("افزودن کانفیگ یا اشتراک", size = 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                SText("VLESS، VMess، Trojan، Shadowsocks، WireGuard یا لینک اشتراک", size = 12.5.sp, color = sx.textDim, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                Spacer(Modifier.height(10.dp))
                SGroup {
                    SRow(Icons.Outlined.ContentPaste, "از کلیپ‌بورد", "متنی که کپی کرده‌اید وارد می‌شود") { onDismiss(); onClipboard() }
                    RowDivider()
                    SRow(Icons.Outlined.Description, "از فایل", "فایل متنی یا خروجی برنامه‌های دیگر") { onDismiss(); onFile() }
                    RowDivider()
                    SRow(Icons.Outlined.QrCodeScanner, "از تصویر QR", "عکس یا اسکرین‌شات کد QR") { onDismiss(); onQr() }
                }
                Spacer(Modifier.height(12.dp))
                SGroup("چسباندن لینک") {
                    Column(Modifier.padding(12.dp)) {
                        SField(link, { link = it }, "vless://…  یا  https://…/sub", ltr = true, singleLine = false, minHeight = 56.dp)
                        Spacer(Modifier.height(10.dp))
                        SButton("افزودن", { val t = link; onDismiss(); onText(t) }, enabled = link.isNotBlank(), icon = Icons.Outlined.Link, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(12.dp))
                SGroup {
                    SRow(Icons.AutoMirrored.Outlined.ListAlt, "مدیریت کانفیگ‌ها", "ویرایش، حذف، مرتب‌سازی و تست تأخیر") { onDismiss(); onManage() }
                    RowDivider()
                    SRow(Icons.Outlined.Share, "اشتراک‌گذاری", "کپی یا QR کانفیگ‌ها و اشتراک‌ها") { onDismiss(); onShare() }
                }
            }
        }
    }
}

