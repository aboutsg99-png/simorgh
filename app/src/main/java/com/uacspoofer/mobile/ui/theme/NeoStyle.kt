package com.uacspoofer.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.Calendar

enum class PowerButtonStyle { RING, ORB, LINE, PULSE }

enum class NeoBackdrop { PLAIN, GRADIENT, DOTS }

/** A named two-stop accent gradient for the neumorphic themes. */
@Immutable
internal data class NeoAccent(val id: String, val nameEn: String, val nameFa: String, val start: Color, val end: Color)

internal val NeoAccents: List<NeoAccent> = listOf(
    NeoAccent("theme", "Theme", "پیش‌فرض تم", Color.Unspecified, Color.Unspecified),
    NeoAccent("aqua", "Aqua", "آبی فیروزه‌ای", Color(0xFF4FE3C1), Color(0xFF3AA8FF)),
    NeoAccent("coral", "Coral", "مرجانی", Color(0xFFFF7A59), Color(0xFFFF4D8D)),
    NeoAccent("violet", "Violet", "بنفش", Color(0xFFA78BFA), Color(0xFF6366F1)),
    NeoAccent("amber", "Amber", "کهربایی", Color(0xFFFACC15), Color(0xFFF97316)),
    NeoAccent("leaf", "Leaf", "برگ", Color(0xFF86EFAC), Color(0xFF22C55E)),
    NeoAccent("sky", "Sky", "آسمانی", Color(0xFF7DD3FC), Color(0xFF3B82F6)),
    NeoAccent("rose", "Rose", "رز", Color(0xFFFDA4AF), Color(0xFFE11D48)),
    NeoAccent("saffron", "Saffron", "زعفرانی", Color(0xFFFDE68A), Color(0xFFF59E0B)),
)

internal const val CUSTOM_ACCENT_ID = "custom"

/** Everything the Appearance screen personalises on top of the theme palette. */
@Immutable
internal data class NeoStyle(
    val accentId: String = "theme",
    /** Hue in degrees for the custom accent. */
    val customHue: Float = 168f,
    val power: PowerButtonStyle = PowerButtonStyle.RING,
    /** 0 = pure neumorphic, 1 = strongest translucent glass; 0.5 is the default. */
    val glass: Float = 0.5f,
    /** Shadow depth, 0..1; 0.5 is the default. */
    val depth: Float = 0.5f,
    /** Corner radius, 0..1 maps to 14..34dp cards; 0.5 is 24dp. */
    val corner: Float = 0.5f,
    val compact: Boolean = false,
    val backdrop: NeoBackdrop = NeoBackdrop.PLAIN,
    val themeOfDay: Boolean = false,
    val dayThemes: Set<String> = DefaultDayThemes,
    val autoDarkLight: Boolean = false,
) {
    val glassScale: Float get() = (glass * 2f).coerceIn(0f, 2f)
    val depthScale: Float get() = 0.4f + depth.coerceIn(0f, 1f) * 1.2f
    val cardRadiusDp: Float get() = 14f + corner.coerceIn(0f, 1f) * 20f

    /** The chosen accent stops, or null to use the theme's own accent. */
    fun accentStops(): Pair<Color, Color>? {
        if (accentId == CUSTOM_ACCENT_ID) {
            val start = Color.hsv(customHue.mod(360f), 0.55f, 0.95f)
            val end = Color.hsv((customHue + 28f).mod(360f), 0.75f, 0.92f)
            return start to end
        }
        val accent = NeoAccents.firstOrNull { it.id == accentId } ?: return null
        if (accent.start == Color.Unspecified) return null
        return accent.start to accent.end
    }

    companion object {
        val DefaultDayThemes: Set<String> = setOf(
            AppTheme.CLASSIC.name, AppTheme.MIST.name, AppTheme.MIDNIGHT.name,
            AppTheme.SAHAR.name, AppTheme.JANGAL.name, AppTheme.FIROUZEH.name,
        )
    }
}

/** Snapshot state read by the neumorphic kit while drawing. */
internal object NeoStyleState {
    var style by mutableStateOf(NeoStyle())
}

/**
 * Theme actually shown: the user's pick, or today's theme from the rotation, then nudged to a
 * light theme by day and a dark one by night when auto dark/light is on. Neon themes are
 * never replaced.
 */
internal fun effectiveTheme(manual: AppTheme, style: NeoStyle, now: Calendar = Calendar.getInstance()): AppTheme {
    if (manual.isNeonFamily) return manual
    var pool: List<AppTheme> = if (style.themeOfDay) {
        AppTheme.entries.filter { !it.isNeonFamily && it.name in style.dayThemes }
    } else {
        listOf(manual)
    }
    if (pool.isEmpty()) pool = listOf(manual)
    if (style.autoDarkLight) {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val night = hour < 7 || hour >= 19
        val matching = pool.filter { paletteFor(it, tokens = null).light != night }
        pool = matching.ifEmpty {
            when {
                !night -> listOf(AppTheme.MIST)
                !paletteFor(manual, tokens = null).light -> listOf(manual)
                else -> listOf(AppTheme.CLASSIC)
            }
        }
    }
    if (pool.size == 1) return pool[0]
    val day = now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR)
    return pool[day % pool.size]
}
