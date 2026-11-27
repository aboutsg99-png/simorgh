package com.uacspoofer.mobile.ui.shell

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Skin(val fa: String) { SYSTEM("سیستم"), LIGHT("روشن"), DARK("تیره"), AMOLED("مشکی"), MONO("سیاه‌وسفید") }
enum class ButtonStyle(val fa: String) { SOFT("نرم"), RING("حلقه"), FILLED("توپر"), PLAIN("ساده") }
enum class AnimLevel(val fa: String) { FULL("کامل"), REDUCED("کم"), OFF("خاموش") }
/** Hero look behind the power button. */
enum class HeroStyle(val fa: String) { ARCS("سه کمان"), SOLID("یک‌رنگ"), MINIMAL("ساده") }
enum class HomeCard(val fa: String) {
    LOCATION("مکان انتخابی"), SHORTCUTS("مکان هوشمند و اخیر"), MAP("نقشه"),
    TIME("زمان محافظت"), ENGINE("موتور اتصال"), USAGE("مصرف داده"), AUTOCONNECT("اتصال خودکار"),
}

@Immutable
data class ShellPrefs(
    val skin: Skin = Skin.LIGHT,
    val accent: Long = DEFAULT_ACCENT,
    val glass: Float = 0.6f,
    val radius: Int = 14,
    val textStep: Int = 1,
    val compact: Boolean = false,
    val button: ButtonStyle = ButtonStyle.SOFT,
    val anim: AnimLevel = AnimLevel.FULL,
    val hero: HeroStyle = HeroStyle.ARCS,
    /** Map pan/pulse. Still capped by the power mode (SAVER: pan only, ULTRA: static). */
    val mapAnim: Boolean = true,
    val cards: List<HomeCard> = HomeCard.entries.toList(),
    /** Usage and auto-connect live in other screens now; they stay optional on home. */
    val hidden: Set<HomeCard> = DEFAULT_HIDDEN,
) {
    val textScale: Float get() = TEXT_SCALES[textStep.coerceIn(0, 3)]

    companion object {
        /** ExpressVPN-style green; the dark theme uses its bright variant. */
        const val DEFAULT_ACCENT = 0xFF1F7A5C
        /** The 1.0.2 default; stored prefs with it move to the new default. */
        const val OLD_DEFAULT_ACCENT = 0xFF3ED6B0
        val DEFAULT_HIDDEN = setOf(HomeCard.USAGE, HomeCard.AUTOCONNECT)
        val ACCENTS = listOf(DEFAULT_ACCENT, 0xFF4F8DF7, 0xFF8B6CF6, 0xFFF0588A, 0xFFF98D3A, 0xFFF2C14E, 0xFF3CC6E8, 0xFF9CCB5A)
        val TEXT_SCALES = listOf(0.92f, 1f, 1.08f, 1.16f)
    }
}

class ShellPrefsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simorgh_shell_v1", Context.MODE_PRIVATE)
    private val lowRam = runCatching {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true ||
            (context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256) <= 192
    }.getOrDefault(false)
    private val mutable = MutableStateFlow(read())
    val prefsFlow: StateFlow<ShellPrefs> = mutable.asStateFlow()

    fun snapshot() = mutable.value

    /**
     * Applies at once in memory (the UI reads the flow), persists on a background thread.
     * Rapid changes (slider drags) coalesce into one write of the latest value.
     */
    @Synchronized
    fun update(transform: (ShellPrefs) -> ShellPrefs) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        mutable.value = next
        if (writePending.compareAndSet(false, true)) {
            writer.execute {
                writePending.set(false)
                persist(mutable.value)
            }
        }
    }

    private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun persist(next: ShellPrefs) {
        prefs.edit()
            .putString(KEY_SKIN, next.skin.name)
            .putLong("accent", next.accent)
            .putFloat("glass", next.glass)
            .putInt(KEY_RADIUS, next.radius)
            .putInt("text", next.textStep)
            .putBoolean("compact", next.compact)
            .putString("button", next.button.name)
            .putString("anim", next.anim.name)
            .putString(KEY_HERO, next.hero.name)
            .putBoolean(KEY_MAP_ANIM, next.mapAnim)
            .putString(KEY_CARDS, next.cards.joinToString(",") { it.name })
            .putString(KEY_HIDDEN, next.hidden.joinToString(",") { it.name })
            .apply()
    }

    fun reset() = update { ShellPrefs(anim = if (lowRam) AnimLevel.REDUCED else AnimLevel.FULL) }

    private fun read(): ShellPrefs = runCatching {
        val cards = prefs.getString(KEY_CARDS, null)?.split(',')?.mapNotNull { n -> HomeCard.entries.firstOrNull { it.name == n } }
            ?.let { (it + HomeCard.entries).distinct() } ?: HomeCard.entries.toList()
        ShellPrefs(
            skin = enumOr(prefs.getString(KEY_SKIN, null), Skin.LIGHT),
            accent = prefs.getLong("accent", ShellPrefs.DEFAULT_ACCENT).let { if (it == ShellPrefs.OLD_DEFAULT_ACCENT) ShellPrefs.DEFAULT_ACCENT else it },
            glass = prefs.getFloat("glass", 0.6f).coerceIn(0f, 1f),
            radius = prefs.getInt(KEY_RADIUS, 14).coerceIn(0, 28),
            textStep = prefs.getInt("text", 1).coerceIn(0, 3),
            compact = prefs.getBoolean("compact", false),
            button = enumOr(prefs.getString("button", null), ButtonStyle.SOFT),
            anim = enumOr(prefs.getString("anim", null), if (lowRam) AnimLevel.REDUCED else AnimLevel.FULL),
            hero = enumOr(prefs.getString(KEY_HERO, null), HeroStyle.ARCS),
            mapAnim = prefs.getBoolean(KEY_MAP_ANIM, true),
            cards = cards,
            hidden = prefs.getString(KEY_HIDDEN, null)?.split(',')?.mapNotNull { n -> HomeCard.entries.firstOrNull { it.name == n } }?.toSet()
                ?: ShellPrefs.DEFAULT_HIDDEN,
        )
    }.getOrDefault(ShellPrefs())

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    /** The palette button on home: light <-> dark (system/amoled/mono count as dark). */
    fun toggleTheme() = update { it.copy(skin = if (isLight(it.skin)) Skin.DARK else Skin.LIGHT) }

    private fun isLight(skin: Skin): Boolean = skin == Skin.LIGHT

    companion object {
        // 1.1 (ExpressVPN layout) keys: the old skin/cards/radius defaults do not carry over.
        private const val KEY_SKIN = "skin2"
        private const val KEY_CARDS = "cards2"
        private const val KEY_HIDDEN = "hidden2"
        private const val KEY_RADIUS = "radius2"
        private const val KEY_HERO = "hero"
        private const val KEY_MAP_ANIM = "map_anim"
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "shell-prefs").apply { isDaemon = true } }
        @Volatile private var instance: ShellPrefsStore? = null
        fun get(context: Context): ShellPrefsStore = instance ?: synchronized(this) {
            instance ?: ShellPrefsStore(context.applicationContext).also { instance = it }
        }
    }
}
