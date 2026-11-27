package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.shell.LocalSx
import com.uacspoofer.mobile.ui.shell.PageHeader
import com.uacspoofer.mobile.ui.shell.SText
import com.uacspoofer.mobile.ui.shell.Vazir

/*
 * Chrome for the tool and engine pages, drawn with the page kit (ui/shell/ShellKit.kt): flat
 * page background, back arrow + title header, right-to-left body. The signatures stay so the
 * pages keep their bodies; [accent]/[icon] are accepted and ignored by the header.
 */

/** Flat page background in the shell palette. */
@Composable
internal fun ToolPageBackground(accent: Color, content: @Composable () -> Unit) {
    val sx = LocalSx.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(sx.bgTop)) { content() }
    }
}

@Composable
internal fun ToolPageScaffold(
    accent: Color,
    header: @Composable () -> Unit,
    verticalSpacing: Dp = 12.dp,
    listState: LazyListState = rememberLazyListState(),
    textStyle: TextStyle = homeLocalizedTextStyle(),
    content: LazyListScope.() -> Unit,
) {
    val wide = LocalWideShell.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ToolPageBackground(accent) {
        CompositionLocalProvider(LocalTextStyle provides textStyle.copy(fontFamily = Vazir)) {
            Column(Modifier.fillMaxSize().padding(top = top)) {
                Box(Modifier.fillMaxWidth()) { header() }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().then(if (wide) Modifier.widthIn(max = WideShell.PageContentMax) else Modifier),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 28.dp),
                        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolPageHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    navigationDescription: String = "بازگشت",
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        PageHeader(title, onMenuClick, subtitle, modifier)
    }
}

/** Small group label above a card. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    SText(text, size = 14.sp, weight = FontWeight.Bold, color = sx.textDim, modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp))
}

internal val ToolCardShape = RoundedCornerShape(18.dp)
internal val ToolCardBrush: Brush get() = SolidColor(com.uacspoofer.mobile.ui.theme.UacColors.Surface)
