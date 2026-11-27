package com.uacspoofer.mobile.ui.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.update.AppRelease
import com.uacspoofer.mobile.update.UpdateUiState
import kotlinx.coroutines.delay

/*
 * Dialogs in the page kit style: one card on a dim scrim, icon + title + close in the header.
 */

private const val EXIT_MS = 140

/** Card dialog with a header (icon, title, subtitle, close). [expanded] fills the panel height. */
@Composable
internal fun SDialog(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    expanded: Boolean = false,
    panelModifier: Modifier = Modifier.fillMaxWidth(),
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            delay(16L)
            shown = true
        } else if (mounted) {
            shown = false
            delay(EXIT_MS.toLong())
            mounted = false
        }
    }
    if (!mounted) return
    Dialog(
        onDismissRequest = { if (dismissible) onDismissRequest() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false),
    ) {
        run {
            val sx = LocalSx.current
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (sx.light) 0.42f else 0.62f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (dismissible) onDismissRequest() }
                    .padding(horizontal = 18.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visible = shown,
                    enter = fadeIn(tween(170)) + scaleIn(tween(210, easing = FastOutSlowInEasing), initialScale = 0.96f),
                    exit = fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS), targetScale = 0.98f),
                ) {
                    Column(
                        panelModifier.widthIn(max = 440.dp)
                            .clip(RoundedCornerShape(sx.radius.coerceIn(16.dp, 26.dp)))
                            .background(sx.surface)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .then(if (expanded) Modifier.fillMaxSize() else Modifier)
                            .padding(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) {
                                IconBox(icon, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                SText(title, size = 17.sp, weight = FontWeight.Bold, maxLines = 2)
                                if (!subtitle.isNullOrBlank()) SText(subtitle, size = 12.5.sp, color = sx.textDim, maxLines = 2)
                            }
                            if (dismissible) RoundIconButtonFlat(Icons.Outlined.Close, onDismissRequest, description = "بستن")
                        }
                        Spacer(Modifier.height(14.dp))
                        content()
                    }
                }
            }
        }
    }
}

/** Yes/no confirmation in the kit style. */
@Composable
internal fun SConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val sx = LocalSx.current
    SDialog(visible = true, title = title, onDismissRequest = onDismiss) {
        SParagraph(text, color = sx.textDim)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SButton(confirm, onConfirm, Modifier.weight(1f), color = if (destructive) sx.bad else null)
            SButton("انصراف", onDismiss, Modifier.weight(1f), primary = false)
        }
    }
}

/** Update prompt (the update checker itself lives in update/ and is unchanged). */
@Composable
internal fun AppUpdateDialog(state: UpdateUiState, onUpdate: (AppRelease) -> Unit, onDismiss: () -> Unit) {
    val release = when (state) {
        is UpdateUiState.Available -> state.release
        is UpdateUiState.Downloading -> state.release
        is UpdateUiState.Ready -> state.release
        is UpdateUiState.Error -> state.release
        else -> null
    } ?: return
    val downloading = state is UpdateUiState.Downloading
    val title = when (state) {
        is UpdateUiState.Downloading -> "در حال دانلود به‌روزرسانی"
        is UpdateUiState.Ready -> "آماده‌ی نصب"
        is UpdateUiState.Error -> "به‌روزرسانی متوقف شد"
        else -> "نسخه‌ی جدید آماده است"
    }
    val description = when (state) {
        is UpdateUiState.Downloading -> "چند لحظه برنامه را باز نگه دارید؛ نصب‌کننده‌ی ${ltr("Android")} خودکار باز می‌شود."
        is UpdateUiState.Ready -> updateMessageFa(state.message)
        is UpdateUiState.Error -> updateMessageFa(state.message)
        else -> "نسخه‌ی ${ltr(release.version)} منتشر شده. فایل از صفحه‌ی رسمی ${ltr("GitHub Release")} و امن دانلود می‌شود."
    }
    SDialog(visible = true, title = title, onDismissRequest = onDismiss, icon = if (downloading) Icons.Outlined.Download else Icons.Outlined.NewReleases, dismissible = !downloading) {
        val sx = LocalSx.current
        SParagraph(description, color = sx.textDim)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(sx.surfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp)) {
            SText("v${BuildConfig.VERSION_NAME}  →  v${release.version}", size = 13.sp, weight = FontWeight.SemiBold, ltr = true, latin = true)
        }
        if (state is UpdateUiState.Downloading) {
            Spacer(Modifier.height(16.dp))
            val p = state.progress
            if (p == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = sx.accent, trackColor = sx.surfaceHigh)
            } else {
                LinearProgressIndicator(progress = { p.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = sx.accent, trackColor = sx.surfaceHigh)
            }
            Spacer(Modifier.height(6.dp))
            SText("${p ?: 0}٪", size = 12.sp, color = sx.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state !is UpdateUiState.Ready) {
                    SButton(if (state is UpdateUiState.Error) "دوباره" else "دانلود و نصب", { onUpdate(release) }, Modifier.weight(1f), icon = Icons.Outlined.Download)
                }
                SButton(if (state is UpdateUiState.Ready) "بستن" else "بعداً", onDismiss, Modifier.weight(1f), primary = false)
            }
        }
    }
}

private fun ltr(value: String) = "\u2066$value\u2069"

private fun updateMessageFa(message: String): String = when {
    message == "Android installer opened. Confirm the update to finish." -> "نصب‌کننده‌ی ${ltr("Android")} باز شد؛ برای پایان، نصب را تأیید کنید."
    message == "Allow installs from this app; the installer will open when you return." -> "اجازه‌ی نصب از این برنامه را بدهید؛ وقتی برگردید نصب‌کننده باز می‌شود."
    message == "Android installer is unavailable" -> "نصب‌کننده‌ی ${ltr("Android")} در دسترس نیست."
    message == "Could not check GitHub Releases" -> "بررسی نسخه‌های جدید در ${ltr("GitHub")} انجام نشد."
    message == "The update could not be downloaded" -> "به‌روزرسانی دانلود نشد."
    message.startsWith("GitHub Releases returned HTTP ") -> "${ltr("GitHub")} با خطای ${ltr("HTTP " + message.substringAfterLast(' '))} پاسخ داد."
    message.startsWith("Download failed (code ") -> "دانلود انجام نشد ${ltr(message.substringAfter("Download failed "))}"
    message == "Download stopped unexpectedly" -> "دانلود ناگهان متوقف شد."
    message == "The downloaded APK could not be opened" -> "فایل ${ltr("APK")} دانلودشده باز نشد."
    message == "Downloaded APK URI is unavailable" -> "آدرس فایل ${ltr("APK")} در دسترس نیست."
    message == "The latest release has no readable version" -> "شماره‌ی نسخه‌ی جدید خوانا نیست."
    message.startsWith("Release ") && "does not include a signed APK" in message -> "این نسخه فایل ${ltr("APK")} امضاشده برای این گوشی ندارد."
    else -> "به‌روزرسانی انجام نشد. دوباره امتحان کنید."
}

internal fun openExternalLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
