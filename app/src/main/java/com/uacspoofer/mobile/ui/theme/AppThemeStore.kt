package com.uacspoofer.mobile.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AppTheme {
    CLASSIC,
    NEON,
    SOLARIS,
    MIST,
    MIDNIGHT,
    SAHAR,
    JANGAL,
    FIROUZEH,
    ;

    /** Neon and Solaris keep the original glow look and the token editor. */
    val isNeonFamily: Boolean get() = this == NEON || this == SOLARIS
}

internal data class SavedAppTheme(
    val id: String,
    val name: String,
    val base: AppTheme,
    val tokens: NeonTokens,
)

class AppThemeStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val theme: StateFlow<AppTheme> = mutable.asStateFlow()
    private val mutableSaved = MutableStateFlow(readSaved())
    internal val savedThemes = mutableSaved.asStateFlow()
    private val mutableSelectedId = MutableStateFlow(prefs.getString(SAVED_ID, null)?.let { id ->
        mutableSaved.value.firstOrNull { it.id == id && it.base == mutable.value }?.id
    })
    internal val selectedId = mutableSelectedId.asStateFlow()
    private val mutableStyle = MutableStateFlow(readStyle())
    internal val style = mutableStyle.asStateFlow()

    /** Persist the Appearance personalisation and apply it right away. */
    internal fun updateStyle(block: (NeoStyle) -> NeoStyle) {
        val next = block(mutableStyle.value)
        if (next == mutableStyle.value) return
        mutableStyle.value = next
        NeoStyleState.style = next
        prefs.edit()
            .putString(STYLE_ACCENT, next.accentId)
            .putFloat(STYLE_HUE, next.customHue)
            .putString(STYLE_POWER, next.power.name)
            .putFloat(STYLE_GLASS, next.glass)
            .putFloat(STYLE_DEPTH, next.depth)
            .putFloat(STYLE_CORNER, next.corner)
            .putBoolean(STYLE_COMPACT, next.compact)
            .putString(STYLE_BACKDROP, next.backdrop.name)
            .putBoolean(STYLE_DAY, next.themeOfDay)
            .putStringSet(STYLE_DAY_THEMES, next.dayThemes)
            .putBoolean(STYLE_AUTO_DARK, next.autoDarkLight)
            .apply()
        refreshAutomatic()
    }

    /** Re-evaluates theme of the day and auto dark/light; cheap, safe to call often. */
    internal fun refreshAutomatic() {
        if (mutableSelectedId.value != null) return
        val shown = effectiveTheme(mutable.value, mutableStyle.value)
        if (UacThemeState.theme != shown) UacThemeState.theme = shown
    }

    private fun readStyle(): NeoStyle {
        val defaults = NeoStyle()
        return NeoStyle(
            accentId = prefs.getString(STYLE_ACCENT, defaults.accentId) ?: defaults.accentId,
            customHue = prefs.getFloat(STYLE_HUE, defaults.customHue),
            power = PowerButtonStyle.entries.firstOrNull { it.name == prefs.getString(STYLE_POWER, null) }
                ?: defaults.power,
            glass = prefs.getFloat(STYLE_GLASS, defaults.glass),
            depth = prefs.getFloat(STYLE_DEPTH, defaults.depth),
            corner = prefs.getFloat(STYLE_CORNER, defaults.corner),
            compact = prefs.getBoolean(STYLE_COMPACT, defaults.compact),
            backdrop = NeoBackdrop.entries.firstOrNull { it.name == prefs.getString(STYLE_BACKDROP, null) }
                ?: defaults.backdrop,
            themeOfDay = prefs.getBoolean(STYLE_DAY, defaults.themeOfDay),
            dayThemes = prefs.getStringSet(STYLE_DAY_THEMES, null)?.toSet() ?: defaults.dayThemes,
            autoDarkLight = prefs.getBoolean(STYLE_AUTO_DARK, defaults.autoDarkLight),
        ).also { NeoStyleState.style = it }
    }

    fun set(theme: AppTheme) {
        prefs.edit().putString(KEY, theme.name).remove(SAVED_ID).apply()
        applySelection(theme, null)
    }

    internal fun selectSaved(id: String) {
        val saved = mutableSaved.value.firstOrNull { it.id == id } ?: return
        prefs.edit().putString(KEY, saved.base.name).putString(SAVED_ID, saved.id).apply()
        applySelection(saved.base, saved)
    }

    internal fun saveTheme(name: String, base: AppTheme, tokens: NeonTokens): Boolean {
        val title = name.trim()
        if (title.isBlank() || title.length > MAX_NAME_LENGTH || !base.isNeonFamily ||
            mutableSaved.value.any { it.name.equals(title, ignoreCase = true) }
        ) return false
        val saved = SavedAppTheme(UUID.randomUUID().toString(), title, base, tokens)
        saveThemes(mutableSaved.value + saved, saved)
        return true
    }

    internal fun updateSelectedTheme(tokens: NeonTokens) {
        val current = mutableSaved.value.firstOrNull { it.id == mutableSelectedId.value } ?: return
        val updated = current.copy(tokens = tokens)
        saveThemes(mutableSaved.value.map { if (it.id == updated.id) updated else it }, updated)
    }

    private fun saveThemes(themes: List<SavedAppTheme>, selected: SavedAppTheme) {
        val json = JSONArray()
        for (item in themes) {
            json.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("base", item.base.name).put("tokens", ThemeTokenCodec.encode(item.tokens)))
        }
        prefs.edit().putString(SAVED_THEMES, json.toString())
            .putString(KEY, selected.base.name).putString(SAVED_ID, selected.id).apply()
        mutableSaved.value = themes
        applySelection(selected.base, selected)
    }

    private fun applySelection(theme: AppTheme, saved: SavedAppTheme?) {
        val tokens = saved?.tokens
        when (theme) {
            AppTheme.NEON -> NeonLive.tokens = tokens ?: NeonTokens()
            AppTheme.SOLARIS -> SolarisLive.tokens = tokens ?: SolarisTokens
            else -> Unit
        }
        mutable.value = theme
        mutableSelectedId.value = saved?.id
        UacThemeState.theme = if (saved == null) effectiveTheme(theme, mutableStyle.value) else theme
    }

    private fun readSaved(): List<SavedAppTheme> {
        val json = runCatching { JSONArray(prefs.getString(SAVED_THEMES, "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val base = when (item.optString("base")) {
                AppTheme.NEON.name -> AppTheme.NEON
                AppTheme.SOLARIS.name -> AppTheme.SOLARIS
                else -> return@mapNotNull null
            }
            val tokens = item.optJSONObject("tokens") ?: return@mapNotNull null
            SavedAppTheme(id, name, base, ThemeTokenCodec.decode(tokens,
                if (base == AppTheme.SOLARIS) SolarisTokens else NeonTokens()))
        }
    }

    private fun read(): AppTheme {
        val stored = prefs.getString(KEY, AppTheme.CLASSIC.name)
        if (stored == "FIRE") return AppTheme.CLASSIC
        return AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.CLASSIC
    }

    companion object {
        private const val PREFS = "uac_app_theme"
        private const val KEY = "theme"
        private const val SAVED_THEMES = "saved_themes"
        private const val SAVED_ID = "saved_theme_id"
        internal const val MAX_NAME_LENGTH = 48
        private const val STYLE_ACCENT = "neo_accent"
        private const val STYLE_HUE = "neo_custom_hue"
        private const val STYLE_POWER = "neo_power_style"
        private const val STYLE_GLASS = "neo_glass"
        private const val STYLE_DEPTH = "neo_depth"
        private const val STYLE_CORNER = "neo_corner"
        private const val STYLE_COMPACT = "neo_compact"
        private const val STYLE_BACKDROP = "neo_backdrop"
        private const val STYLE_DAY = "neo_theme_of_day"
        private const val STYLE_DAY_THEMES = "neo_day_themes"
        private const val STYLE_AUTO_DARK = "neo_auto_dark_light"

        @Volatile
        private var instance: AppThemeStore? = null

        fun get(context: Context): AppThemeStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AppThemeStore(context.applicationContext).also {
                    it.applySelection(it.theme.value, it.savedThemes.value.firstOrNull { saved ->
                        saved.id == it.selectedId.value
                    })
                    instance = it
                }
            }
        }
    }
}

