package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.update.AppUpdateManager

/** Legal notices required by AGPL-3.0 and bundled third-party licenses, read from app assets. */
private val LICENSE_FILES = listOf(
    "NOTICE.md" to "Notice",
    "THIRD_PARTY_NOTICES.md" to "Third-party",
    "AGPL-3.0.txt" to "AGPL-3.0",
)

@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(0) }
    val text = remember(selected) {
        runCatching {
            context.assets.open("licenses/" + LICENSE_FILES[selected].first).bufferedReader().use { it.readText() }
        }.getOrElse { "Not available." }
    }
    val shape = RoundedCornerShape(22.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .background(Color(0xF2262A32), shape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                homeText("Licenses and source code", "مجوزها و کد منبع"),
                color = UacColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = homeLocalizedFont(),
            )
            Text(
                homeText(
                    "Simorgh is free software under AGPL-3.0. Full source:",
                    "سیمرغ نرم‌افزار آزاد با مجوز AGPL-3.0 است. کد کامل:",
                ),
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = homeLocalizedFont(),
            )
            Text(
                AppUpdateManager.REPOSITORY_URL,
                color = UacColors.DisconnectedBlue,
                fontSize = 12.sp,
                modifier = Modifier.clickable { openExternalLink(context, AppUpdateManager.REPOSITORY_URL) },
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LICENSE_FILES.forEachIndexed { index, (_, label) ->
                    val chip = RoundedCornerShape(50)
                    Text(
                        label,
                        color = if (index == selected) Color(0xFF0E1116) else UacColors.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(if (index == selected) UacColors.ConnectedGreen else Color.White.copy(alpha = 0.06f), chip)
                            .clickable { selected = index }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                text,
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                Text(homeText("Close", "بستن"), color = UacColors.ConnectedGreen, fontFamily = homeLocalizedFont())
            }
        }
    }
}
