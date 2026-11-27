package com.uacspoofer.mobile.simorgh

import android.content.Context
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.wireguard.WgTunnelStore
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.simorgh.auto.AutoConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An exact way out the user picked: engine plus country (or config). */
data class Target(val engine: EngineMode, val countryCode: String = "", val profileId: String = "")

/**
 * Applies a picked server everywhere at once (engine store, the engine's own country
 * setting, selected config), then connects or switches the live tunnel. The home card,
 * notification, widget and map all follow because they read [LocationState].
 */
object SelectionActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun select(context: Context, target: Target) {
        val app = context.applicationContext
        val code = target.countryCode.trim().uppercase()
        runCatching {
            when (target.engine) {
                EngineMode.XRAY_CF -> if (target.profileId.isNotEmpty()) ProfileStore(app).select(target.profileId)
                EngineMode.FOXY_FIREFOX -> FoxyEngineStore.get(app).update { it.copy(countryCode = code) }
                EngineMode.TOR_WEBTUNNEL -> {
                    val store = TorEngineStore.get(app)
                    store.save(store.snapshot().copy(exitCountryCode = code.lowercase(), exitStrict = code.isNotEmpty()))
                }
                EngineMode.UAC_POW -> {
                    val store = PowEngineStore.get(app)
                    store.save(store.snapshot().copy(exitCountryCode = code))
                }
                EngineMode.UAC_DIRECT -> if (code.isNotEmpty()) VpnController.selectDirectExit(app, code)
                // The store throws when its Keystore-backed file is unreadable; never crash a tap on that.
                EngineMode.WIREGUARD -> runCatching { WgTunnelStore.get(app).select(target.profileId.takeIf { it.isNotEmpty() }) }
            }
        }
        LocationState.bumpProfiles()
    }

    /**
     * [connect] is the activity's connect entry (it asks for VPN permission when needed).
     */
    fun apply(context: Context, target: Target, connect: () -> Unit) {
        val app = context.applicationContext
        val engines = EngineModeStore.get(app)
        select(app, target)
        val state = ConnectionStateStore.state.value
        val sameEngine = engines.snapshot() == target.engine
        when (state) {
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                engines.setMode(target.engine)
                AutoConnector.bypassOnce = true
                connect()
            }
            ConnectionState.CONNECTED -> if (sameEngine && !AutoConnector.running()) {
                when (target.engine) {
                    EngineMode.XRAY_CF -> VpnController.switchProfile(app)
                    EngineMode.FOXY_FIREFOX -> VpnController.applyFoxyCountry(app)
                    EngineMode.TOR_WEBTUNNEL -> VpnController.applyTorExit(app)
                    EngineMode.UAC_POW -> VpnController.applyPowExit(app)
                    EngineMode.UAC_DIRECT -> Unit // selectDirectExit already applied it live
                    EngineMode.WIREGUARD -> restartWith(app, target.engine, connect)
                }
            } else {
                restartWith(app, target.engine, connect)
            }
            ConnectionState.CONNECTING -> restartWith(app, target.engine, connect)
            ConnectionState.DISCONNECTING -> scope.launch {
                withTimeoutOrNull(12_000L) { ConnectionStateStore.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.ERROR } }
                engines.setMode(target.engine)
                AutoConnector.bypassOnce = true
                connect()
            }
        }
    }

    private fun restartWith(app: Context, engine: EngineMode, connect: () -> Unit) {
        scope.launch {
            AutoConnector.cancel()
            if (ConnectionStateStore.tryBeginDisconnect()) runCatching { VpnController.stop(app) }
            val done = withTimeoutOrNull(12_000L) {
                ConnectionStateStore.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.ERROR }
            }
            if (done == null) return@launch
            delay(400L)
            EngineModeStore.get(app).setMode(engine)
            AutoConnector.bypassOnce = true
            connect()
        }
    }
}
