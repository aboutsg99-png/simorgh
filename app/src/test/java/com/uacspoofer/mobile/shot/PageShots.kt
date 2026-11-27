package com.uacspoofer.mobile.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProfileLatencyCache
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.Subscription
import com.uacspoofer.mobile.profiles.SubscriptionStore
import com.uacspoofer.mobile.profiles.SubscriptionUserInfo
import com.uacspoofer.mobile.simorgh.CurrentLocation
import com.uacspoofer.mobile.simorgh.ExitCheck
import com.uacspoofer.mobile.simorgh.ExitPhase
import com.uacspoofer.mobile.simorgh.LocationState
import com.uacspoofer.mobile.simorgh.RecentLocation
import com.uacspoofer.mobile.simorgh.RecentLocations
import com.uacspoofer.mobile.simorgh.Selection
import com.uacspoofer.mobile.simorgh.servers.FastRow
import com.uacspoofer.mobile.simorgh.servers.FastestRepository
import com.uacspoofer.mobile.simorgh.servers.FastestState
import com.uacspoofer.mobile.simorgh.servers.RowSource
import com.uacspoofer.mobile.ui.shell.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every screen of the app in the light and the dark skin, with sample data (two private
 * configs, a subscription with usage and expiry, Mozilla countries, recent places).
 * Output: the `simorgh.shots.dir` system property (Gradle `-PshotsDir=`), default build/shots/pages.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PageShots {
    @get:Rule val rule = createComposeRule()

    private val dir = System.getProperty("simorgh.shots.dir") ?: "build/shots/pages"
    private val page = PaddingValues(16.dp, 30.dp, 16.dp, 40.dp)

    private fun seed(connected: Boolean) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val store = ProfileStore(app)
        if (store.snapshot().customProfiles.isEmpty()) {
            store.importText(
                "vless://5b1c9a5e-1f0e-4c2b-9d9e-2f3c4b5a6d7e@de1.example.net:443?type=ws&security=tls&sni=de1.example.net&path=%2Fws#Frankfurt%20WS\n" +
                    "trojan://secret-pass@nl.example.org:443?type=tcp&security=tls&sni=nl.example.org#Amsterdam%20Trojan\n" +
                    "vmess://eyJ2IjoiMiIsInBzIjoiU3ViIFRva3lvIiwiYWRkIjoianAuZXhhbXBsZS5jb20iLCJwb3J0IjoiNDQzIiwiaWQiOiIxMjM0NTY3OC0xMjM0LTEyMzQtMTIzNC0xMjM0NTY3ODkwYWIiLCJhaWQiOiIwIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJqcC5leGFtcGxlLmNvbSIsInBhdGgiOiIvIiwidGxzIjoidGxzIn0=",
            )
        }
        val lib = store.snapshot()
        fun codeOf(name: String) = when { name.contains("Frankfurt") -> "DE"; name.contains("Amsterdam") -> "NL"; else -> "JP" }
        lib.customProfiles.forEach { p -> runCatching { store.updateCountry(p.id, CountryMetadata(codeOf(p.name), codeOf(p.name))) } }
        val cache = ProfileLatencyCache(app)
        lib.customProfiles.forEachIndexed { i, p -> cache.put(p.id, 88L + i * 57L) }
        val subs = SubscriptionStore(app)
        if (subs.all().isEmpty()) {
            val now = System.currentTimeMillis()
            subs.upsert(
                Subscription(
                    id = "sub1", url = "https://panel.example.com/sub/abc123", name = "اشتراک ماهانه",
                    profileIds = lib.customProfiles.filter { it.name.contains("Tokyo") }.map { it.id },
                    userInfo = SubscriptionUserInfo(upload = 1_200_000_000L, download = 18_300_000_000L, total = 50_000_000_000L, expire = now / 1000 + 12 * 86_400L),
                    updatedAt = now - 3 * 3_600_000L, updateIntervalHours = 12,
                ),
            )
        }
        val rec = RecentLocations.get(app)
        if (rec.items.value.isEmpty()) {
            rec.add(RecentLocation(EngineMode.FOXY_FIREFOX, "CH", "CH", "", "", System.currentTimeMillis() - 86_400_000L))
            rec.add(RecentLocation(EngineMode.XRAY_CF, "DE", "", lib.customProfiles.firstOrNull()?.id.orEmpty(), "Frankfurt WS", System.currentTimeMillis() - 3_600_000L))
        }
        val cfg = lib.customProfiles.mapIndexed { i, p ->
            FastRow("c:" + p.id, EngineMode.XRAY_CF, if (p.name.contains("Tokyo")) RowSource.SUBSCRIPTION else RowSource.CONFIG, codeOf(p.name), p.name, "Xray", profileId = p.id, pingMs = 88L + i * 57L)
        }
        val moz = listOf("CH" to 142L, "SE" to 171L, "US" to 236L, "GB" to 189L, "FR" to 160L).map { (c, ms) ->
            FastRow("f:$c", EngineMode.FOXY_FIREFOX, RowSource.RUBAH, c, c, "Firefox VPN", pingMs = ms)
        }
        FastestRepository.setForPreview(FastestState(rows = cfg + moz, rubahSignedIn = true, lastRunMs = System.currentTimeMillis() - 120_000L))
        LocationState.setForPreview(
            if (connected) CurrentLocation(
                connection = ConnectionState.CONNECTED,
                selection = Selection(EngineMode.XRAY_CF, "DE", lib.customProfiles.firstOrNull()?.id.orEmpty(), "Frankfurt WS"),
                runningEngine = EngineMode.XRAY_CF,
                exit = ExitCheck(phase = ExitPhase.DONE, viaTunnel = true, engine = EngineMode.XRAY_CF, ip = "203.0.113.24", countryCode = "DE", countryName = "Germany", city = "Frankfurt"),
            ) else CurrentLocation(selection = Selection(EngineMode.XRAY_CF, "DE", lib.customProfiles.firstOrNull()?.id.orEmpty(), "Frankfurt WS")),
        )
    }

    private fun shot(name: String, dark: Boolean, connected: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        ShellPrefsStore.get(app).update { it.copy(skin = if (dark) Skin.DARK else Skin.LIGHT) }
        seed(connected)
        rule.setContent {
            SimorghShellTheme {
                val sx = LocalSx.current
                Box(Modifier.fillMaxSize().background(sx.bgTop)) { content() }
            }
        }
        repeat(4) {
            rule.mainClock.advanceTimeBy(600)
            Thread.sleep(700)
        }
        rule.waitForIdle()
        if (name.contains("sheet")) captureScreenRoboImage("$dir/$name.png") else rule.onRoot().captureRoboImage("$dir/$name.png")
    }

    @Test fun homeOffLight() = shot("home-off-light", dark = false, connected = false) { HomeTab(ConnectionState.DISCONNECTED, {}, {}, {}, {}, PaddingValues(bottom = 24.dp), 24.dp) }
    @Test fun homeOffDark() = shot("home-off-dark", dark = true, connected = false) { HomeTab(ConnectionState.DISCONNECTED, {}, {}, {}, {}, PaddingValues(bottom = 24.dp), 24.dp) }
    @Test fun homeOnLight() = shot("home-on-light", dark = false, connected = true) { HomeTab(ConnectionState.CONNECTED, {}, {}, {}, {}, PaddingValues(bottom = 24.dp), 24.dp) }
    @Test fun homeOnDark() = shot("home-on-dark", dark = true, connected = true) { HomeTab(ConnectionState.CONNECTED, {}, {}, {}, {}, PaddingValues(bottom = 24.dp), 24.dp) }
    @Test fun shellNavHomeLight() = shot("shell-nav-home-light", dark = false, connected = false) { SimorghShell(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, initialTab = 0) }
    @Test fun shellNavHomeDark() = shot("shell-nav-home-dark", dark = true, connected = false) { SimorghShell(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, initialTab = 0) }
    @Test fun tabEnginesDark() = shot("tab-engines-dark", dark = true, connected = false) { SimorghShell(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, initialTab = 1) }
    @Test fun tabSpeedDark() = shot("tab-speed-dark", dark = true, connected = false) { SimorghShell(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, initialTab = 2) }
    @Test fun tabGuideDark() = shot("tab-guide-dark", dark = true, connected = false) { SimorghShell(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, initialTab = 3) }
    @Test fun tabProfileDark() = shot("tab-profile-dark", dark = true, connected = false) { SimorghShell(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, initialTab = 4) }
    @Test fun locationsAllDark() = shot("locations-all-dark", dark = true, connected = false) { LocationsScreen(ConnectionState.DISCONNECTED, {}, {}, {}, 24.dp, 0.dp, initialTab = 0) }
    @Test fun locationsFavouritesDark() = shot("locations-favourites-dark", dark = true, connected = false) { LocationsScreen(ConnectionState.DISCONNECTED, {}, {}, {}, 24.dp, 0.dp, initialTab = 1) }
    @Test fun locationsRecentDark() = shot("locations-recent-dark", dark = true, connected = false) { LocationsScreen(ConnectionState.DISCONNECTED, {}, {}, {}, 24.dp, 0.dp, initialTab = 2) }
    @Test fun serversFastestDark() = shot("servers-fastest-dark", dark = true, connected = false) { ServersTab(ConnectionState.DISCONNECTED, {}, {}, {}, page, initialTab = 0) }
    @Test fun serversAllDark() = shot("servers-all-dark", dark = true, connected = false) { ServersTab(ConnectionState.DISCONNECTED, {}, {}, {}, page, initialTab = 1) }
    @Test fun serversAddSheetDark() = shot("servers-add-sheet-dark", dark = true, connected = false) { AddConfigSheet({}, {}, {}, {}, {}, {}, {}) }
    @Test fun pageSettingsDark() = shot("page-settings-dark", dark = true, connected = false) { RouteHost(Route.SETTINGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pagePersonalizeDark() = shot("page-personalize-dark", dark = true, connected = false) { RouteHost(Route.PERSONALIZE, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageAutoSettingsDark() = shot("page-auto-settings-dark", dark = true, connected = false) { RouteHost(Route.AUTO_SETTINGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageXraySettingsDark() = shot("page-xray-settings-dark", dark = true, connected = false) { RouteHost(Route.XRAY_SETTINGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pagePowSettingsDark() = shot("page-pow-settings-dark", dark = true, connected = false) { RouteHost(Route.POW_SETTINGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageTorSettingsDark() = shot("page-tor-settings-dark", dark = true, connected = false) { RouteHost(Route.TOR_SETTINGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageDirectSettingsDark() = shot("page-direct-settings-dark", dark = true, connected = false) { RouteHost(Route.DIRECT_SETTINGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageFirefoxCountriesDark() = shot("page-firefox-countries-dark", dark = true, connected = false) { RouteHost(Route.FIREFOX, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageWireguardDark() = shot("page-wireguard-dark", dark = true, connected = false) { RouteHost(Route.WIREGUARD, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageProtonSignInDark() = shot("page-proton-sign-in-dark", dark = true, connected = false) { RouteHost(Route.PROTON_SIGN_IN, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageTorCountriesDark() = shot("page-tor-countries-dark", dark = true, connected = false) { RouteHost(Route.TOR_COUNTRIES, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageWarpPsiphonCountriesDark() = shot("page-warp-psiphon-countries-dark", dark = true, connected = false) { RouteHost(Route.POW_COUNTRIES, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageFluxDark() = shot("page-flux-dark", dark = true, connected = false) { RouteHost(Route.FLUX, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageCleanIpDark() = shot("page-clean-ip-dark", dark = true, connected = false) { RouteHost(Route.CLEAN_IP, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageGameDnsDark() = shot("page-game-dns-dark", dark = true, connected = false) { RouteHost(Route.GAME_DNS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageConfigsDark() = shot("page-configs-dark", dark = true, connected = false) { RouteHost(Route.CONFIGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageShareDark() = shot("page-share-dark", dark = true, connected = false) { RouteHost(Route.SHARE, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageSplitTunnelDark() = shot("page-split-tunnel-dark", dark = true, connected = false) { RouteHost(Route.APP_BYPASS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageSniBuilderDark() = shot("page-sni-builder-dark", dark = true, connected = false) { RouteHost(Route.SNI_MAKER, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageRouteTestDark() = shot("page-route-test-dark", dark = true, connected = false) { RouteHost(Route.ROUTE_TEST, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageLiveLogDark() = shot("page-live-log-dark", dark = true, connected = false) { RouteHost(Route.LIVE_LOGS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageBatteryDark() = shot("page-battery-dark", dark = true, connected = false) { RouteHost(Route.BATTERY, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageDiagnosticsDark() = shot("page-diagnostics-dark", dark = true, connected = false) { RouteHost(Route.DIAGNOSTICS, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageSupportDark() = shot("page-support-dark", dark = true, connected = false) { RouteHost(Route.SUPPORT, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun pageReportDark() = shot("page-report-dark", dark = true, connected = false) { RouteHost(Route.REPORT, ConnectionState.DISCONNECTED, {}, {}, {}, {}, 24.dp, 0.dp) }
}
