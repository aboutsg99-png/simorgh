package com.uacspoofer.mobile.ui.engines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.glassSurface
import com.uacspoofer.mobile.ui.theme.UacColors

/*
 * Small building blocks for the engine pages for the extra engines (WireGuard, Flux,
 * clean-IP scanner, game DNS). Kept in their own package and on the shared theme colours so the
 * shell/theme rework can restyle them without touching engine code.
 */

private val CardShape = RoundedCornerShape(18.dp)

@Composable
internal fun EngineCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().glassSurface(CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun EngineTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = UacColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

@Composable
internal fun EngineBody(text: String, color: Color = UacColors.TextSecondary, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 12.5.sp, lineHeight = 19.sp, modifier = modifier)

@Composable
internal fun EnginePrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = UacColors.DisconnectedBlue, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 13.5.sp) }
}

@Composable
internal fun EngineSecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, fontSize = 13.sp, color = UacColors.TextPrimary)
    }
}

@Composable
internal fun EngineToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) EngineBody(subtitle)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = UacColors.DisconnectedBlue))
    }
}

@Composable
internal fun EngineChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) UacColors.DisconnectedBlue else UacColors.TextSecondary.copy(alpha = 0.12f)
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, fontSize = 12.5.sp, color = if (selected) Color.White else UacColors.TextPrimary) }
}

@Composable
internal fun EngineStatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

@Composable
internal fun EngineIconRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = UacColors.DisconnectedBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (subtitle.isNotBlank()) Text(subtitle, color = UacColors.TextSecondary, fontSize = 11.5.sp, maxLines = 2)
        }
        trailing()
    }
}

/** ISO code -> flag emoji ("" for unknown). */
internal fun flagOf(code: String?): String {
    val c = code?.uppercase()?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } } ?: return ""
    return buildString { c.forEach { appendCodePoint(0x1F1E6 + (it - 'A')) } }
}
