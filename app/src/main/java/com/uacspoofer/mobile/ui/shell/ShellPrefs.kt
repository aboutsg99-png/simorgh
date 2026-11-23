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
enum class HomeCard(val fa: String) {
    MAP("نقشه"), LOCATION("مکان انتخابی"), SHORTCUTS("میان‌برهای هوشمند و اخیر"),
    USAGE("مصرف داده"), TIME("زمان محافظت"), ENGINE("موتور"), AUTOCONNECT("اتصال خودکار"),
}

@Immutable
data class ShellPrefs(
    val skin: Skin = Skin.SYSTEM,
    val accent: Long = DEFAULT_ACCENT,
    val glass: Float = 0.6f,
    val radius: Int = 16,
    val textStep: Int = 1,
    val compact: Boolean = false,
    val button: ButtonStyle = ButtonStyle.SOFT,
    val anim: AnimLevel = AnimLevel.FULL,
    val cards: List<HomeCard> = HomeCard.entries.toList(),
    val hidden: Set<HomeCard> = emptySet(),
) {
    val textScale: Float get() = TEXT_SCALES[textStep.coerceIn(0, 3)]

    companion object {
        const val DEFAULT_ACCENT = 0xFF3ED6B0
        val ACCENTS = listOf(0xFF3ED6B0, 0xFF4F8DF7, 0xFF8B6CF6, 0xFFF0588A, 0xFFF98D3A, 0xFFF2C14E, 0xFF3CC6E8, 0xFF9CCB5A)
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

    @Synchronized
    fun update(transform: (ShellPrefs) -> ShellPrefs) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit()
            .putString("skin", next.skin.name)
            .putLong("accent", next.accent)
            .putFloat("glass", next.glass)
            .putInt("radius", next.radius)
            .putInt("text", next.textStep)
            .putBoolean("compact", next.compact)
            .putString("button", next.button.name)
            .putString("anim", next.anim.name)
            .putString("cards", next.cards.joinToString(",") { it.name })
            .putString("hidden", next.hidden.joinToString(",") { it.name })
            .apply()
    }

    fun reset() = update { ShellPrefs(anim = if (lowRam) AnimLevel.REDUCED else AnimLevel.FULL) }

    private fun read(): ShellPrefs = runCatching {
        val cards = prefs.getString("cards", null)?.split(',')?.mapNotNull { n -> HomeCard.entries.firstOrNull { it.name == n } }
            ?.let { (it + HomeCard.entries).distinct() } ?: HomeCard.entries.toList()
        ShellPrefs(
            skin = enumOr(prefs.getString("skin", null), Skin.SYSTEM),
            accent = prefs.getLong("accent", ShellPrefs.DEFAULT_ACCENT),
            glass = prefs.getFloat("glass", 0.6f).coerceIn(0f, 1f),
            radius = prefs.getInt("radius", 16).coerceIn(0, 28),
            textStep = prefs.getInt("text", 1).coerceIn(0, 3),
            compact = prefs.getBoolean("compact", false),
            button = enumOr(prefs.getString("button", null), ButtonStyle.SOFT),
            anim = enumOr(prefs.getString("anim", null), if (lowRam) AnimLevel.REDUCED else AnimLevel.FULL),
            cards = cards,
            hidden = prefs.getString("hidden", "").orEmpty().split(',').mapNotNull { n -> HomeCard.entries.firstOrNull { it.name == n } }.toSet(),
        )
    }.getOrDefault(ShellPrefs())

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        @Volatile private var instance: ShellPrefsStore? = null
        fun get(context: Context): ShellPrefsStore = instance ?: synchronized(this) {
            instance ?: ShellPrefsStore(context.applicationContext).also { instance = it }
        }
    }
}
