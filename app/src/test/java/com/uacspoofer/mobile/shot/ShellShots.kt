package com.uacspoofer.mobile.shot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xxhdpi")
class ShellShots {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            SimorghShellTheme {
                val sx = LocalSx.current
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(sx.bgTop, sx.bgBottom)))) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private val pad = PaddingValues(18.dp, 30.dp, 18.dp, 110.dp)

    @Test fun home() = shot("home") { HomeTab(ConnectionState.DISCONNECTED, {}, {}, {}, {}, {}, {}, pad) }
    @Test fun homeConnecting() = shot("home-connecting") { HomeTab(ConnectionState.CONNECTING, {}, {}, {}, {}, {}, {}, pad) }
    @Test fun homeConnected() = shot("home-connected") { HomeTab(ConnectionState.CONNECTED, {}, {}, {}, {}, {}, {}, pad) }
    @Test fun guide() = shot("guide") { GuideTab({}, {}, pad) }
    @Test fun servers() = shot("servers") { ServersTab(ConnectionState.DISCONNECTED, {}, {}, pad) }
    @Test fun speed() = shot("speed") { SpeedTab(pad) }
    @Test fun engines() = shot("engines") { EnginesScreen({}, {}, pad) }
    @Test fun personalize() = shot("personalize") { PersonalizeScreen({}, pad) }
    @Test fun profile() = shot("profile") { ProfileTab({}, {}, pad) }
}
