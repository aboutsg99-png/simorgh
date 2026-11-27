package com.uacspoofer.mobile.engine.gamedns

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class GameDnsSettings(
    val primary: String? = null,
    val secondary: String? = null,
    val userList: String = "",
    val domains: String = GameDns.DEFAULT_DOMAINS.joinToString("\n"),
    val disabledDefaults: Set<String> = emptySet(),
) {
    fun servers(): List<DnsServer> =
        (GameDns.DEFAULTS.filterNot { it.ip in disabledDefaults } + GameDns.parseUserList(userList)).distinctBy { it.ip }

    fun domainList(): List<String> = domains.lineSequence().map { it.trim().lowercase() }
        .filter { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }.distinct().take(6).toList()
        .ifEmpty { GameDns.DEFAULT_DOMAINS }
}

class GameDnsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simorgh_game_dns_v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<GameDnsSettings> = mutable.asStateFlow()

    fun snapshot() = mutable.value

    @Synchronized
    fun update(transform: (GameDnsSettings) -> GameDnsSettings) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit()
            .putString("primary", next.primary).putString("secondary", next.secondary)
            .putString("user", next.userList.take(8_000)).putString("domains", next.domains.take(2_000))
            .putStringSet("disabled", next.disabledDefaults)
            .apply()
    }

    private fun read() = GameDnsSettings(
        primary = prefs.getString("primary", null),
        secondary = prefs.getString("secondary", null),
        userList = prefs.getString("user", "").orEmpty(),
        domains = prefs.getString("domains", null) ?: GameDns.DEFAULT_DOMAINS.joinToString("\n"),
        disabledDefaults = prefs.getStringSet("disabled", emptySet()).orEmpty(),
    )

    companion object {
        @Volatile private var instance: GameDnsStore? = null
        fun get(context: Context): GameDnsStore = instance ?: synchronized(this) {
            instance ?: GameDnsStore(context.applicationContext).also { instance = it }
        }
    }
}
