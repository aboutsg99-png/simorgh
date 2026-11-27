package com.uacspoofer.mobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.ui.LocalWideShell
import com.uacspoofer.mobile.ui.rememberWideShell

/**
 * Every full-screen page of the app. The shell keeps a back stack of these; Back pops one.
 * There is no navigation drawer and no second dashboard: each page returns to where it was
 * opened from.
 */
enum class Route {
    // Shell pages
    LOCATIONS, SERVERS, PERSONALIZE, SETTINGS, AUTO_SETTINGS,
    // Engine pages
    XRAY_SETTINGS, POW_SETTINGS, TOR_SETTINGS, DIRECT_SETTINGS, FIREFOX, WIREGUARD, PROTON_SIGN_IN,
    TOR_COUNTRIES, POW_COUNTRIES, FLUX, CLEAN_IP, GAME_DNS,
    // Configs and tools
    CONFIGS, SHARE, APP_BYPASS, SNI_MAKER, ROUTE_TEST, LIVE_LOGS, BATTERY, DIAGNOSTICS,
    // About
    SUPPORT, REPORT,
}

/** The settings page of one engine (the gear on the engines page). */
internal fun settingsRouteFor(choiceMode: EngineMode?): Route = when (choiceMode) {
    null -> Route.AUTO_SETTINGS
    EngineMode.XRAY_CF -> Route.XRAY_SETTINGS
    EngineMode.UAC_POW -> Route.POW_SETTINGS
    EngineMode.TOR_WEBTUNNEL -> Route.TOR_SETTINGS
    EngineMode.UAC_DIRECT -> Route.DIRECT_SETTINGS
    EngineMode.FOXY_FIREFOX -> Route.FIREFOX
    EngineMode.WIREGUARD -> Route.WIREGUARD
}

/** Stack as a comma list so it survives process death through rememberSaveable. */
internal fun encodeStack(stack: List<Route>): String = stack.joinToString(",") { it.name }
internal fun decodeStack(raw: String): List<Route> =
    raw.split(',').mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }

/**
 * Renders one route. Pages written for the shell use [SubPage]; the engine settings and tool
 * pages keep their own bodies and draw with the shell palette (see UacThemeState.override), in
 * the layout direction they were built for.
 */
@Composable
internal fun RouteHost(
    route: Route,
    state: ConnectionState,
    onBack: () -> Unit,
    onOpen: (Route) -> Unit,
    onConnect: () -> Unit,
    onSwitchProfile: () -> Unit,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    val sx = LocalSx.current
    val pagePadding = remember(topInset, bottomInset) {
        PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 8.dp, bottom = bottomInset + 24.dp)
    }
    Box(Modifier.fillMaxSize().background(sx.bgTop)) {
        when (route) {
            Route.LOCATIONS -> LocationsScreen(state, onBack, onConnect, onManage = { onOpen(Route.SERVERS) }, topInset = topInset, bottomInset = bottomInset)
            Route.SERVERS -> ServersTab(state = state, onConnect = onConnect, onOpen = onOpen, onBack = onBack, contentPadding = pagePadding)
            Route.PERSONALIZE -> PersonalizeScreen(onBack = onBack, contentPadding = pagePadding)
            Route.SETTINGS -> com.uacspoofer.mobile.ui.SettingsScreen(onBack = onBack, onOpen = onOpen)
            Route.AUTO_SETTINGS -> AutoSettingsPage(onBack = onBack)
            Route.SUPPORT -> SupportPage(onBack = onBack, onOpen = onOpen)
            Route.REPORT -> ReportPage(onBack = onBack, onOpen = onOpen)
            Route.SHARE -> SharePage(onBack = onBack)
            Route.BATTERY -> BatteryPage(onBack = onBack)
            Route.CONFIGS -> {
                val context = LocalContext.current
                val store = remember(context) { ProfileStore(context) }
                val connected = state == ConnectionState.CONNECTED
                ConfigsPage(onBack, state, if (connected) runCatching { store.activeProfile()?.id }.getOrNull() else null, onSwitchProfile)
            }
            Route.APP_BYPASS -> SplitTunnelPage(onBack = onBack)
            Route.LIVE_LOGS -> LiveLogPage(onBack = onBack)
            else -> LegacyBody { LegacyRoute(route, state, onBack, onOpen, onSwitchProfile) }
        }
    }
}

/** Pages with their own bodies: shell palette (flat theme bridge), right-to-left like every page. */
@Composable
private fun LegacyBody(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalWideShell provides rememberWideShell(),
        content = content,
    )
}

@Composable
private fun LegacyRoute(
    route: Route,
    state: ConnectionState,
    onBack: () -> Unit,
    onOpen: (Route) -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val context = LocalContext.current
    when (route) {
        Route.XRAY_SETTINGS -> com.uacspoofer.mobile.ui.XraySettingsScreen(onBack = onBack)
        Route.POW_SETTINGS -> com.uacspoofer.mobile.ui.PowSettingsScreen(onBack = onBack)
        Route.TOR_SETTINGS -> com.uacspoofer.mobile.ui.TorSettingsScreen(onBack = onBack)
        Route.DIRECT_SETTINGS -> {
            var unlocked by rememberSaveable { mutableStateOf(false) }
            com.uacspoofer.mobile.ui.DirectSettingsScreen(onBack = onBack, unlocked = unlocked, onUnlock = { unlocked = true })
        }
        Route.FIREFOX -> com.uacspoofer.mobile.ui.FoxyCountryScreen(onMenuClick = onBack)
        Route.TOR_COUNTRIES -> com.uacspoofer.mobile.ui.TorCountryScreen(onMenuClick = onBack)
        Route.POW_COUNTRIES -> com.uacspoofer.mobile.ui.PowCountryScreen(onMenuClick = onBack)
        Route.WIREGUARD -> com.uacspoofer.mobile.ui.engines.WireGuardScreen(onBack = onBack, onProtonSignIn = { onOpen(Route.PROTON_SIGN_IN) })
        Route.PROTON_SIGN_IN -> com.uacspoofer.mobile.ui.engines.ProtonSignInScreen(onBack = onBack)
        Route.FLUX -> com.uacspoofer.mobile.ui.engines.FluxSettingsScreen(onBack = onBack)
        Route.CLEAN_IP -> com.uacspoofer.mobile.ui.engines.CleanIpScreen(onBack = onBack)
        Route.GAME_DNS -> com.uacspoofer.mobile.ui.engines.GameDnsScreen(onBack = onBack)
        Route.SNI_MAKER -> {
            val controller = remember(context.applicationContext) { com.uacspoofer.mobile.ui.SniMakerController(context) }
            androidx.compose.runtime.DisposableEffect(controller) { onDispose { controller.close() } }
            com.uacspoofer.mobile.ui.SniMakerScreen(onMenuClick = onBack, controller = controller)
        }
        Route.ROUTE_TEST -> {
            val controller = remember(context.applicationContext) { com.uacspoofer.mobile.ui.RouteSpeedTestController.get(context) }
            com.uacspoofer.mobile.ui.RouteSpeedTestScreen(controller = controller, onBackClick = onBack)
        }
        Route.DIAGNOSTICS -> com.uacspoofer.mobile.ui.RuntimeDiagnosticsScreen(onClose = onBack)
        else -> Unit
    }
}

/** Engine in the store right now (pages that differ per engine read it). */
@Composable
internal fun currentEngineMode(): EngineMode {
    val context = LocalContext.current
    val mode by remember(context) { com.uacspoofer.mobile.engine.EngineModeStore.get(context) }.mode.collectAsStateWithLifecycle()
    return mode
}
