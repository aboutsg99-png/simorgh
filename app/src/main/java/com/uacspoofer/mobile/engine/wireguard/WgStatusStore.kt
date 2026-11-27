package com.uacspoofer.mobile.engine.wireguard

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WgPhase { IDLE, TESTING, CONNECTED, FAILED }

@Immutable
data class WgStatus(
    val phase: WgPhase = WgPhase.IDLE,
    val detail: String = "",
    val tunnelId: String? = null,
    val tunnelName: String = "",
    val source: WgSource? = null,
    val countryCode: String? = null,
    val latencyMs: Long? = null,
)

object WgStatusStore {
    private val mutable = MutableStateFlow(WgStatus())
    val status: StateFlow<WgStatus> = mutable.asStateFlow()

    /** Loopback SOCKS of the running WireGuard core (0 when none); read by the exit / ping checks. */
    @Volatile var socksPort: Int = 0

    fun update(transform: (WgStatus) -> WgStatus) { mutable.value = transform(mutable.value) }
    fun reset() { mutable.value = WgStatus() }
}
