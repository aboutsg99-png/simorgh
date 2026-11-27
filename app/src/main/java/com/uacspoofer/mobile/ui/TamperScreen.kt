package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.ui.shell.openExternalLink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.uacspoofer.mobile.ui.theme.UacColors

/** Shown instead of the app when the integrity check fails. */
@Composable
internal fun TamperScreen() {
    val context = LocalContext.current
    GlassBackdrop(accent = UacColors.ErrorRed) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.GppBad, null, tint = UacColors.ErrorRed, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(18.dp))
            Text("این نسخه‌ی رسمی Blackdey نیست", color = UacColors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "این برنامه تغییر داده شده یا زیر ابزار نفوذ اجرا می‌شود و برای امنیت تو اتصال غیرفعال است. نسخه‌ی اصلی را فقط از پشتیبانی بگیر.",
                color = UacColors.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "پشتیبانی در تلگرام: @theluciferee",
                color = UacColors.NeonMagenta,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .glassSurface(RoundedCornerShape(16.dp))
                    .clickable { openExternalLink(context, "https://t.me/theluciferee") }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}
