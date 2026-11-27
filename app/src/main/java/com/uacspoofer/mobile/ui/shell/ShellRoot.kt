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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import kotlinx.coroutines.delay

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
 * SaveableStateHolder. Every other screen (location picker, settings, engine settings, tools,
 * support) opens as a full-screen page on a back stack over the tabs.
 */
@Composable
internal fun SimorghShell(
    state: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchProfile: () -> Unit,
    onMinimize: () -> Unit,
    onCloseApp: () -> Unit,
    initialTab: Int = TAB_HOME,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var stackRaw by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackRaw) { decodeStack(stackRaw) }
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
    var forward by remember { mutableStateOf(true) }
    val open: (Route) -> Unit = remember {
        { r -> val cur = decodeStack(stackRaw); if (cur.lastOrNull() != r) { forward = true; stackRaw = encodeStack(cur + r) } }
    }
    val back: () -> Unit = remember { { forward = false; stackRaw = encodeStack(decodeStack(stackRaw).dropLast(1)) } }
    val top = stack.lastOrNull()

    SimorghShellTheme {
        val sx = LocalSx.current
        SystemBarsFor(sx.light)
        var backArmed by remember { mutableStateOf(false) }
        BackHandler(enabled = top != null) { back() }
        BackHandler(enabled = top == null && tab != TAB_HOME) { tab = TAB_HOME }
        BackHandler(enabled = top == null && tab == TAB_HOME) {
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
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val navHeight = 72.dp
        val content = remember(topInset, bottom) { PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 8.dp, bottom = bottom + navHeight + 16.dp) }
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
                        onOpenLocations = { open(Route.LOCATIONS) },
                        onOpenEngines = { tab = TAB_ENGINES },
                        contentPadding = homePadding,
                        topInset = topInset,
                    )
                    TAB_ENGINES -> EnginesScreen(onBack = { tab = TAB_HOME }, onOpen = open, contentPadding = content)
                    2 -> SpeedTab(contentPadding = content)
                    3 -> GuideTab(onOpen = open, onOpenEngines = { tab = TAB_ENGINES }, contentPadding = content)
                    else -> ProfileTab(onOpen = open, onOpenEngines = { tab = TAB_ENGINES }, contentPadding = content)
                }
            }
            // Status bar scrim so scrolled content never runs under the clock and icons.
            if (tab != TAB_HOME) {
                Box(Modifier.fillMaxWidth().height(topInset).background(sx.bgTop).zIndex(2f))
            }
            BottomNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter), bottom, navHeight)
            val pageDur = if (sx.anim == AnimLevel.OFF) 0 else 220
            AnimatedContent(
                targetState = top,
                transitionSpec = {
                    if (forward) {
                        (slideInHorizontally(tween(pageDur)) { -it / 4 } + fadeIn(tween(pageDur))) togetherWith fadeOut(tween(pageDur / 2))
                    } else {
                        fadeIn(tween(pageDur / 2)) togetherWith (slideOutHorizontally(tween(pageDur)) { -it / 4 } + fadeOut(tween(pageDur)))
                    }
                },
                modifier = Modifier.zIndex(5f),
                label = "page",
            ) { route ->
                if (route != null) {
                    holder.SaveableStateProvider("page:" + route.name) {
                        RouteHost(
                            route = route,
                            state = shown,
                            onBack = back,
                            onOpen = open,
                            onConnect = onConnect,
                            onSwitchProfile = onSwitchProfile,
                            topInset = topInset,
                            bottomInset = bottom,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = backArmed,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + navHeight + 16.dp).zIndex(6f),
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

/** Same update check as before (the checker itself is unchanged); the result is shared with Support. */
@Composable
private fun UpdateCheckOnStart() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { UpdateUi.check(context, scope, showDialog = true) }
    if (UpdateUi.dialogVisible) {
        AppUpdateDialog(state = UpdateUi.state, onUpdate = { UpdateUi.download(context, scope, it) }, onDismiss = { UpdateUi.dialogVisible = false })
    }
}
