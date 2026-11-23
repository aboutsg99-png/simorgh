package com.uacspoofer.mobile.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.simorgh.auto.AutoConnector
import com.uacspoofer.mobile.ui.AppUpdateDialog
import com.uacspoofer.mobile.ui.DrawerDestination
import com.uacspoofer.mobile.ui.MainScreen
import com.uacspoofer.mobile.update.AppRelease
import com.uacspoofer.mobile.update.AppUpdateManager
import com.uacspoofer.mobile.update.UpdateCheckResult
import com.uacspoofer.mobile.update.UpdateUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ShellPage { PERSONALIZE, ENGINES }

private data class NavItem(val label: String, val icon: ImageVector)

private val NAV = listOf(
    NavItem("VPN", Icons.Outlined.PowerSettingsNew),
    NavItem("سرورها", Icons.Outlined.Public),
    NavItem("تست سرعت", Icons.Outlined.Speed),
    NavItem("راهنما", Icons.Outlined.HelpOutline),
    NavItem("پروفایل", Icons.Outlined.Person),
)

/**
 * 1.0.2 app shell: five tabs with a floating pill bar. Only the visible tab is composed
 * (state of the others is kept by a SaveableStateHolder). Engine settings, configs, logs
 * and the rest of the existing pages open full screen through the existing MainScreen.
 */
@Composable
internal fun SimorghShell(
    state: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchProfile: () -> Unit,
    onMinimize: () -> Unit,
    onCloseApp: () -> Unit,
) {
    var legacy by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<ShellPage?>(null) }
    UpdateCheckOnStart()

    val auto by AutoConnector.progress.collectAsStateWithLifecycle()
    val shown = if (auto.active && state != ConnectionState.CONNECTED && state != ConnectionState.DISCONNECTING) ConnectionState.CONNECTING else state
    val onPrimary: () -> Unit = when (shown) {
        ConnectionState.CONNECTED, ConnectionState.CONNECTING -> onDisconnect
        ConnectionState.DISCONNECTING -> ({})
        else -> onConnect
    }

    val legacyDest = legacy?.let { name -> DrawerDestination.entries.firstOrNull { it.name == name } }
    if (legacyDest != null) {
        MainScreen(
            state = state,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSwitchProfile = onSwitchProfile,
            onMinimize = onMinimize,
            onCloseApp = onCloseApp,
            startDestination = legacyDest,
            onExitToShell = { legacy = null },
            checkUpdatesOnStart = false,
        )
        return
    }
    val openLegacy: (DrawerDestination) -> Unit = { legacy = it.name }

    SimorghShellTheme {
        val sx = LocalSx.current
        var backArmed by remember { mutableStateOf(false) }
        BackHandler(enabled = page != null) { page = null }
        BackHandler(enabled = page == null && tab != 0) { tab = 0 }
        BackHandler(enabled = page == null && tab == 0) {
            if (state == ConnectionState.CONNECTED) {
                onMinimize()
            } else if (backArmed) {
                onCloseApp()
            } else {
                backArmed = true
            }
        }
        LaunchedEffect(backArmed) {
            if (backArmed) {
                delay(2_000L)
                backArmed = false
            }
        }
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val content = PaddingValues(start = 18.dp, end = 18.dp, top = top + 8.dp, bottom = bottom + 104.dp)
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(sx.bgTop, sx.bgBottom))),
        ) {
            val holder = rememberSaveableStateHolder()
            val dur = if (sx.anim == AnimLevel.OFF) 0 else 200
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(dur)) togetherWith fadeOut(tween(dur / 2)) },
                label = "tabs",
            ) { t ->
                holder.SaveableStateProvider(t) {
                    when (t) {
                        0 -> HomeTab(
                            state = shown,
                            onPrimary = onPrimary,
                            onConnect = onConnect,
                            onOpenServers = { tab = 1 },
                            onOpenPersonalize = { page = ShellPage.PERSONALIZE },
                            onOpenSettings = { openLegacy(DrawerDestination.SETTINGS) },
                            onOpenEngines = { page = ShellPage.ENGINES },
                            contentPadding = content,
                        )
                        1 -> ServersTab(state = shown, onConnect = onConnect, onOpenLegacy = openLegacy, contentPadding = content)
                        2 -> SpeedTab(contentPadding = content)
                        3 -> GuideTab(onOpenLegacy = openLegacy, onOpenEngines = { page = ShellPage.ENGINES }, contentPadding = content)
                        else -> ProfileTab(
                            onOpenLegacy = openLegacy,
                            onOpenPage = { page = it },
                            contentPadding = content,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = page != null,
                enter = slideInHorizontally(tween(if (dur == 0) 0 else 240)) { -it / 3 } + fadeIn(tween(dur)),
                exit = slideOutHorizontally(tween(if (dur == 0) 0 else 200)) { -it / 3 } + fadeOut(tween(dur)),
                modifier = Modifier.zIndex(5f),
            ) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(sx.bgTop, sx.bgBottom)))) {
                    val pagePadding = PaddingValues(start = 18.dp, end = 18.dp, top = top + 8.dp, bottom = bottom + 24.dp)
                    when (page) {
                        ShellPage.PERSONALIZE -> PersonalizeScreen(onBack = { page = null }, contentPadding = pagePadding)
                        ShellPage.ENGINES -> EnginesScreen(onBack = { page = null }, onOpenLegacy = openLegacy, contentPadding = pagePadding)
                        null -> Unit
                    }
                }
            }
            if (page == null) {
                FloatingNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 12.dp, start = 16.dp, end = 16.dp))
            }
            AnimatedVisibility(
                visible = backArmed,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 100.dp),
            ) {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SText("برای خروج، دوباره «بازگشت» را بزنید", size = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sx = LocalSx.current
    Row(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(32.dp), clip = false)
            .clip(RoundedCornerShape(32.dp))
            .background(if (sx.light) sx.surface.copy(alpha = 0.97f) else sx.bgBottom.copy(alpha = 0.94f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NAV.forEachIndexed { i, item ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) sx.accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                    .sxClick { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, item.label, tint = if (on) sx.accent else sx.textDim, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                SText(item.label, size = 11.5.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) sx.accent else sx.textDim)
            }
        }
    }
}

/** Same update check the old dashboard ran at start (the checker itself is unchanged). */
@Composable
private fun UpdateCheckOnStart() {
    val context = LocalContext.current
    val manager = remember(context) { AppUpdateManager(context.applicationContext) }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(manager) {
        updateState = try {
            when (val result = manager.checkForUpdate()) {
                UpdateCheckResult.Current -> UpdateUiState.UpToDate
                is UpdateCheckResult.Available -> {
                    visible = true
                    UpdateUiState.Available(result.release)
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            UpdateUiState.Error(error.message ?: "Could not check GitHub Releases")
        }
    }
    val begin: (AppRelease) -> Unit = { release ->
        if (updateState !is UpdateUiState.Downloading) {
            scope.launch {
                updateState = UpdateUiState.Downloading(release, 0)
                updateState = try {
                    manager.downloadAndInstall(release) { p -> updateState = UpdateUiState.Downloading(release, p) }
                    UpdateUiState.Ready(release, "Download finished. Tap the download notification to install.")
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    UpdateUiState.Error(error.message ?: "The update could not be downloaded", release)
                }
            }
        }
    }
    if (visible) AppUpdateDialog(state = updateState, onUpdate = begin, onDismiss = { visible = false })
}
