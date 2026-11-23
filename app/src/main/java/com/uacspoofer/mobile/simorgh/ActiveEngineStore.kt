package com.uacspoofer.mobile.simorgh

import com.uacspoofer.mobile.engine.EngineMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engine the VPN service is actually running. It differs from the selected engine
 * after a Rubah fallback or while Auto walks its list. Published by UacVpnService.
 */
object ActiveEngineStore {
    private val mutable = MutableStateFlow<EngineMode?>(null)
    val engine: StateFlow<EngineMode?> = mutable.asStateFlow()

    fun publish(mode: EngineMode?) {
        if (mutable.value != mode) mutable.value = mode
    }
}
