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
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.shell.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ShellShots {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        // Skin is set before the first frame: switching it during composition re-keyed the map
        // raster mid-shot and the dark home could be captured before its bitmap landed.
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        ShellPrefsStore.get(app).update { it.copy(skin = if (dark) Skin.DARK else Skin.LIGHT) }
        rule.setContent {
            SimorghShellTheme {
                val sx = LocalSx.current
                Box(Modifier.fillMaxSize().background(sx.bgTop)) { content() }
            }
        }
        // Map asset parse + raster run on background dispatchers: give them several rounds.
        repeat(4) {
            rule.mainClock.advanceTimeBy(600)
            Thread.sleep(900)
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private val pad = PaddingValues(16.dp, 30.dp, 16.dp, 110.dp)
    private val homePad = PaddingValues(bottom = 24.dp)

    private fun home(state: ConnectionState) = @androidx.compose.runtime.Composable { HomeTab(state, {}, {}, {}, {}, homePad, 24.dp) }

    @Test fun home() = shot("home-light-off") { home(ConnectionState.DISCONNECTED)() }
    @Test fun homeConnecting() = shot("home-light-connecting") { home(ConnectionState.CONNECTING)() }
    @Test fun homeConnected() = shot("home-light-on") { home(ConnectionState.CONNECTED)() }
    @Test fun homeDark() = shot("home-dark-on", dark = true) { home(ConnectionState.CONNECTED)() }
    @Test fun homeDarkOff() = shot("home-dark-off", dark = true) { home(ConnectionState.DISCONNECTED)() }
    @Test fun locations() = shot("locations") { LocationsScreen(ConnectionState.DISCONNECTED, {}, {}, {}, 24.dp, 0.dp) }
    @Test fun engines() = shot("engines") { EnginesScreen({}, {}, pad) }
    @Test fun guide() = shot("guide") { GuideTab({}, {}, pad) }
    @Test fun servers() = shot("servers") { ServersTab(ConnectionState.DISCONNECTED, {}, {}, {}, pad) }
    @Test fun speed() = shot("speed") { SpeedTab(pad) }
    @Test fun personalize() = shot("personalize") { PersonalizeScreen({}, pad) }
    @Test fun profile() = shot("profile") { ProfileTab({}, {}, pad) }
}
