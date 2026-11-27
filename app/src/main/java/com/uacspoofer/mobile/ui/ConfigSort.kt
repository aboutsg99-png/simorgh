package com.uacspoofer.mobile.ui

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.ui.theme.UacColors

/** Sort orders of the configs list (configs page, unit-tested in ConfigsSortTest). */
internal enum class ConfigLatencySort {
    DEFAULT,
    LATENCY_ASC,
    LATENCY_DESC,
    COUNTRY_ASC,
}

internal fun sortProfilesByLatency(
    profiles: List<ProxyProfile>,
    latencies: Map<String, Long>,
    order: ConfigLatencySort,
    countries: Map<String, CountryMetadata> = emptyMap(),
): List<ProxyProfile> {
    if (order == ConfigLatencySort.DEFAULT || profiles.size < 2) return profiles
    val indexed = profiles.withIndex()
    val comparator = when (order) {
        ConfigLatencySort.DEFAULT -> return profiles
        ConfigLatencySort.LATENCY_ASC -> compareBy<IndexedValue<ProxyProfile>> {
            latencies[it.value.id] == null
        }.thenBy {
            latencies[it.value.id] ?: Long.MAX_VALUE
        }.thenBy(IndexedValue<ProxyProfile>::index)
        ConfigLatencySort.LATENCY_DESC -> compareBy<IndexedValue<ProxyProfile>> {
            latencies[it.value.id] == null
        }.thenByDescending {
            latencies[it.value.id] ?: Long.MIN_VALUE
        }.thenBy(IndexedValue<ProxyProfile>::index)
        ConfigLatencySort.COUNTRY_ASC -> compareBy<IndexedValue<ProxyProfile>> {
            !(countries[it.value.id] ?: it.value.country).isKnown
        }.thenBy {
            (countries[it.value.id] ?: it.value.country).countryName
        }.thenBy {
            latencies[it.value.id] == null
        }.thenBy {
            latencies[it.value.id] ?: Long.MAX_VALUE
        }.thenBy(IndexedValue<ProxyProfile>::index)
    }
    return indexed.sortedWith(comparator).map(IndexedValue<ProxyProfile>::value)
}

/** Outlined text field colours in the shell palette (tool pages). */
@Composable
internal fun toolTextFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    unfocusedBorderColor = UacColors.CardBorder,
    errorBorderColor = UacColors.ErrorRed,
    focusedLabelColor = accent,
    unfocusedLabelColor = UacColors.TextSecondary,
    focusedTextColor = UacColors.TextPrimary,
    unfocusedTextColor = UacColors.TextPrimary,
    cursorColor = accent,
    focusedContainerColor = UacColors.Surface,
    unfocusedContainerColor = UacColors.Surface,
)
