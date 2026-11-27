package com.uacspoofer.mobile.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import com.uacspoofer.mobile.core.PowerControl
import com.uacspoofer.mobile.core.TapDebouncer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PowerSettingsNew
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

enum class ShellPage { PERSONALIZE, ENGINES, LOCATIONS, SERVERS }

private data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val NAV = listOf(
    NavItem("VPN", Icons.Outlined.PowerSettingsNew, Icons.Filled.PowerSettingsNew),
    NavItem("موتورها", Icons.Outlined.StarOutline, Icons.Filled.Star),
    NavItem("تست سرعت", Icons.Outlined.Speed, Icons.Filled.Speed),
    NavItem("راهنما", Icons.AutoMirrored.Outlined.HelpOutline, Icons.AutoMirrored.Filled.HelpOutline),
    NavItem("پروفایل", Icons.Outlined.Person, Icons.Filled.Person),
)

private const val TAB_HOME = 0
private const val TAB_ENGINES = 1

/**
 * App shell, ExpressVPN layout: five tabs (VPN, engines, speed test, guide, profile) with a
 * flat bottom bar. Only the visible tab is composed; the others keep their state in a
 * SaveableStateHolder. Tabs switch instantly (no cross-fade: composing two heavy tabs at
 * once was a visible hitch). The location picker and personalisation open as pages over the
 * tabs; engine settings, configs, logs and the rest still open through the old MainScreen.
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
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var page by rememberSaveable { mutableStateOf<ShellPage?>(null) }
    UpdateCheckOnStart()

    val auto by AutoConnector.progress.collectAsStateWithLifecycle()
    val shown = shownState(state, auto.active)
    // One clear action per state, and one action per tap: double taps and taps that land
    // while the previous one is still being handled are dropped.
    val gate = remember { TapDebouncer() }
    val latestShown by rememberUpdatedState(shown)
    val latestConnect by rememberUpdatedState(onConnect)
    val latestDisconnect by rememberUpdatedState(onDisconnect)
    val onPrimary: () -> Unit = remember {
        { PowerControl.dispatchTap(latestShown, gate, { latestConnect() }, { latestDisconnect() }); Unit }
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
    val openLegacy: (DrawerDestination) -> Unit = remember { { legacy = it.name } }
    val openPage: (ShellPage) -> Unit = remember {
        { p -> if (p == ShellPage.ENGINES) { page = null; tab = TAB_ENGINES } else page = p }
    }
    val closePage: () -> Unit = remember { { page = null } }

    SimorghShellTheme {
        val sx = LocalSx.current
        SystemBarsFor(sx.light)
        var backArmed by remember { mutableStateOf(false) }
        BackHandler(enabled = page != null) { page = null }
        BackHandler(enabled = page == null && tab != TAB_HOME) { tab = TAB_HOME }
        BackHandler(enabled = page == null && tab == TAB_HOME) {
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
        val navHeight = 72.dp
        val content = remember(top, bottom) { PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + navHeight + 16.dp) }
        // Home draws its hero under the status bar itself.
        val homePadding = remember(bottom) { PaddingValues(bottom = bottom + navHeight + 16.dp) }
        Box(Modifier.fillMaxSize().background(sx.bgTop)) {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    TAB_HOME -> HomeTab(
                        state = shown,
                        onPrimary = onPrimary,
                        onConnect = onConnect,
                        onOpenLocations = { page = ShellPage.LOCATIONS },
                        onOpenEngines = { tab = TAB_ENGINES },
                        contentPadding = homePadding,
                        topInset = top,
                    )
                    TAB_ENGINES -> EnginesScreen(onBack = { tab = TAB_HOME }, onOpenLegacy = openLegacy, contentPadding = content)
                    2 -> SpeedTab(contentPadding = content)
                    3 -> GuideTab(onOpenLegacy = openLegacy, onOpenEngines = { tab = TAB_ENGINES }, contentPadding = content)
                    else -> ProfileTab(onOpenLegacy = openLegacy, onOpenPage = openPage, contentPadding = content)
                }
            }
            val pageDur = if (sx.anim == AnimLevel.OFF) 0 else 220
            AnimatedVisibility(
                visible = page != null,
                enter = slideInHorizontally(tween(pageDur)) { -it / 4 } + fadeIn(tween(pageDur)),
                exit = slideOutHorizontally(tween(pageDur)) { -it / 4 } + fadeOut(tween(pageDur)),
                modifier = Modifier.zIndex(5f),
            ) {
                // Keep showing the page that is animating out.
                var shownPage by remember { mutableStateOf(page) }
                if (page != null) shownPage = page
                Box(Modifier.fillMaxSize().background(sx.bgTop)) {
                    val pagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + 24.dp)
                    when (shownPage) {
                        ShellPage.PERSONALIZE -> PersonalizeScreen(onBack = closePage, contentPadding = pagePadding)
                        ShellPage.LOCATIONS -> LocationsScreen(
                            state = shown,
                            onBack = closePage,
                            onConnect = onConnect,
                            onManage = { page = ShellPage.SERVERS },
                            topInset = top,
                            bottomInset = bottom,
                        )
                        ShellPage.SERVERS -> ServersTab(state = shown, onConnect = onConnect, onOpenLegacy = openLegacy, contentPadding = pagePadding)
                        ShellPage.ENGINES, null -> Unit
                    }
                }
            }
            if (page == null) {
                BottomNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter), bottom, navHeight)
            }
            AnimatedVisibility(
                visible = backArmed,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + navHeight + 16.dp),
            ) {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(sx.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SText("برای خروج، دوباره «بازگشت» را بزنید", size = 13.sp)
                }
            }
        }
    }
}

/** While Auto walks its list the service is briefly stopped between engines: keep showing CONNECTING. */
internal fun shownState(state: ConnectionState, autoActive: Boolean): ConnectionState = PowerControl.shownState(state, autoActive)

/** Dark status/nav bar icons on the light theme, light icons on dark. */
@Composable
private fun SystemBarsFor(light: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier, bottomInset: Dp, height: Dp) {
    val sx = LocalSx.current
    Column(modifier.fillMaxWidth().background(sx.nav)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(sx.stroke))
        Row(
            Modifier.fillMaxWidth().height(height).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NAV.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null) { onSelect(i) }
                        .semantics { this.selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) sx.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (on) item.selectedIcon else item.icon, null, tint = if (on) sx.text else sx.textDim, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    SText(item.label, size = 11.5.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) sx.text else sx.textDim)
                }
            }
        }
        Spacer(Modifier.height(bottomInset))
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
