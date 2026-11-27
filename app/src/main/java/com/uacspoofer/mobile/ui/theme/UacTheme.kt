package com.uacspoofer.mobile.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.ProvideFixedFontScale

internal data class UacPalette(
    val neon: Boolean,
    val backgroundTop: Color,
    val backgroundMiddle: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val disconnected: Color,
    val connecting: Color,
    val connected: Color,
    val disconnecting: Color,
    val error: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val cardBorder: Color,
    val divider: Color,
    val buttonCenter: Color,
    val buttonEdge: Color,
    val buttonInnerRing: Color,
    val neonMagenta: Color,
    val neonCyan: Color,
    val neonViolet: Color,
    val settingsSurface: Color,
    val drawerSurface: Color = Color.Unspecified,
    val surfaceOverride: Color = Color.Unspecified,
    val dividerOverride: Color = Color.Unspecified,
    // Neumorphic tokens: light and dark shadow tones and the two accent stops.
    val light: Boolean = false,
    val neoHi: Color = Color(0xFF2E333D),
    val neoLo: Color = Color(0xFF121418),
    val accentStart: Color = Color(0xFF4FE3C1),
    val accentEnd: Color = Color(0xFF3AA8FF),
    /** Flat cards (the app shell's design): no neumorphic shadows, glass or neon light. */
    val flat: Boolean = false,
    /** Card fill and the inset/raised-high fill used by flat surfaces. */
    val card: Color = Color.Unspecified,
    val cardHigh: Color = Color.Unspecified,
)

private val ClassicPalette = UacPalette(
    neon = false,
    backgroundTop = Color(0xFF20242B),
    backgroundMiddle = Color(0xFF20242B),
    backgroundBottom = Color(0xFF20242B),
    surface = Color(0xFF262A32),
    disconnected = Color(0xFF3AA8FF),
    connecting = Color(0xFF6CC9FF),
    connected = Color(0xFF4FE3C1),
    disconnecting = Color(0xFFF5B14C),
    error = Color(0xFFFF5D6C),
    textPrimary = Color(0xFFE7EBF1),
    textSecondary = Color(0xFF8A93A2),
    cardBorder = Color(0x14FFFFFF),
    divider = Color(0x0FFFFFFF),
    buttonCenter = Color(0xFF282C35),
    buttonEdge = Color(0xFF14171C),
    buttonInnerRing = Color(0xFF2F343D),
    neonMagenta = Color(0xFF4FE3C1),
    neonCyan = Color(0xFF3AA8FF),
    neonViolet = Color(0xFF6CC9FF),
    settingsSurface = Color(0xF0262A32),
)

/** Mist: the light neumorphic theme on a #E6E9EF base. */
private val MistPalette = UacPalette(
    neon = false,
    backgroundTop = Color(0xFFE6E9EF),
    backgroundMiddle = Color(0xFFE6E9EF),
    backgroundBottom = Color(0xFFE6E9EF),
    surface = Color(0xFFE9ECF2),
    disconnected = Color(0xFF2B8FE0),
    connecting = Color(0xFF2FA6E8),
    connected = Color(0xFF17B391),
    disconnecting = Color(0xFFDB8E1E),
    error = Color(0xFFE5485A),
    textPrimary = Color(0xFF1D2330),
    textSecondary = Color(0xFF687183),
    cardBorder = Color(0x14000000),
    divider = Color(0x0F000000),
    buttonCenter = Color(0xFFE6E9EF),
    buttonEdge = Color(0xFFB9C0CB),
    buttonInnerRing = Color(0xFFD5DAE2),
    neonMagenta = Color(0xFF17B391),
    neonCyan = Color(0xFF2B8FE0),
    neonViolet = Color(0xFF2FA6E8),
    settingsSurface = Color(0xF7ECEFF4),
    light = true,
    neoHi = Color(0xFFFFFFFF),
    neoLo = Color(0xFFA9B1BE),
    accentStart = Color(0xFF2BD1AE),
    accentEnd = Color(0xFF2E8FE8),
)

private fun neoDark(
    bg: Color,
    hi: Color,
    lo: Color,
    text: Color,
    muted: Color,
    idle: Color,
    connecting: Color,
    connected: Color,
    accentStart: Color,
    accentEnd: Color,
) = ClassicPalette.copy(
    backgroundTop = bg,
    backgroundMiddle = bg,
    backgroundBottom = bg,
    surface = hi,
    disconnected = idle,
    connecting = connecting,
    connected = connected,
    textPrimary = text,
    textSecondary = muted,
    buttonCenter = bg,
    buttonEdge = lo,
    buttonInnerRing = hi,
    neonMagenta = connected,
    neonCyan = idle,
    neonViolet = connecting,
    settingsSurface = hi.copy(alpha = 0.94f),
    neoHi = hi,
    neoLo = lo,
    accentStart = accentStart,
    accentEnd = accentEnd,
)

/** Midnight: deep navy. */
private val MidnightPalette = neoDark(
    bg = Color(0xFF1B1F2E), hi = Color(0xFF282E44), lo = Color(0xFF0F121B),
    text = Color(0xFFE6E9F5), muted = Color(0xFF8B92AD),
    idle = Color(0xFF7C9CFF), connecting = Color(0xFF7DD3FC), connected = Color(0xFF5EE0D0),
    accentStart = Color(0xFF7C9CFF), accentEnd = Color(0xFF4FD1FF),
)

/** Sahar: warm sunset on a dusky base. */
private val SaharPalette = neoDark(
    bg = Color(0xFF2A2024), hi = Color(0xFF392C31), lo = Color(0xFF191215),
    text = Color(0xFFF5E9E4), muted = Color(0xFFA8918B),
    idle = Color(0xFFFF8A7A), connecting = Color(0xFFFFC48A), connected = Color(0xFFFFB067),
    accentStart = Color(0xFFFFA764), accentEnd = Color(0xFFFF5E7E),
)

/** Jangal: dark forest green. */
private val JangalPalette = neoDark(
    bg = Color(0xFF1C2420), hi = Color(0xFF28322C), lo = Color(0xFF101613),
    text = Color(0xFFE4EEE7), muted = Color(0xFF8AA195),
    idle = Color(0xFF5FD39A), connecting = Color(0xFFA7F3D0), connected = Color(0xFF6EE7A8),
    accentStart = Color(0xFF86EFAC), accentEnd = Color(0xFF22C55E),
)

/** Firouzeh: turquoise on deep teal. */
private val FirouzehPalette = neoDark(
    bg = Color(0xFF17262A), hi = Color(0xFF22363B), lo = Color(0xFF0C1619),
    text = Color(0xFFE3F2F3), muted = Color(0xFF86A5A9),
    idle = Color(0xFF38BDF8), connecting = Color(0xFF67E8F9), connected = Color(0xFF5EEAD4),
    accentStart = Color(0xFF5EEAD4), accentEnd = Color(0xFF06B6D4),
)

private val NeonPalette = UacPalette(
    neon = true,
    backgroundTop = Color(0xFF07010F),
    backgroundMiddle = Color(0xFF0E0420),
    backgroundBottom = Color(0xFF160628),
    surface = Color(0xFF1A0B2E),
    disconnected = Color(0xFF8B7CFF),
    connecting = Color(0xFF22E7FF),
    connected = Color(0xFFFF2BD6),
    disconnecting = Color(0xFFC084FC),
    error = Color(0xFFFF4D6A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA894C4),
    cardBorder = Color(0x8CFF2BD6),
    divider = Color(0x33FFFFFF),
    buttonCenter = Color(0xFF12081C),
    buttonEdge = Color(0xFF07010F),
    buttonInnerRing = Color(0xFF3A2458),
    neonMagenta = Color(0xFFFF2BD6),
    neonCyan = Color(0xFF22E7FF),
    neonViolet = Color(0xFF9B5CFF),
    settingsSurface = Color(0xE6140824),
)

private val SolarisPalette = NeonPalette.copy(
    backgroundTop = Color(0xF7050217),
    backgroundMiddle = Color(0xFF070F33),
    backgroundBottom = Color(0xFF0D0E15),
    surface = Color(0xFF10142E),
    disconnected = Color(0xFF90C8F8),
    connecting = Color(0xFF90C8F8),
    connected = Color(0xFFF06808),
    disconnecting = Color(0xFFE86810),
    textSecondary = Color(0xFFA894C4),
    cardBorder = Color(0x66F06808),
    buttonCenter = Color(0xFF12081C),
    buttonEdge = Color(0xFF070F33),
    neonMagenta = Color(0xFFF06808),
    neonCyan = Color(0xFF90C8F8),
    neonViolet = Color(0xFF90C8F8),
    settingsSurface = Color(0xE610142E),
)

internal fun Color.orDefault(default: Color): Color = if (isSpecified) this else default

internal fun paletteFor(theme: AppTheme, tokens: NeonTokens? = styleTokens(theme)): UacPalette {
    val base = when (theme) {
        AppTheme.CLASSIC -> ClassicPalette
        AppTheme.MIST -> MistPalette
        AppTheme.MIDNIGHT -> MidnightPalette
        AppTheme.SAHAR -> SaharPalette
        AppTheme.JANGAL -> JangalPalette
        AppTheme.FIROUZEH -> FirouzehPalette
        AppTheme.NEON -> NeonPalette
        AppTheme.SOLARIS -> SolarisPalette
    }
    val custom = tokens?.pageColors ?: return base
    return base.copy(
        backgroundTop = custom.pageTop.orDefault(base.backgroundTop),
        backgroundMiddle = custom.pageMiddle.orDefault(base.backgroundMiddle),
        backgroundBottom = custom.pageBottom.orDefault(base.backgroundBottom),
        surface = custom.pageSurface.orDefault(base.surface),
        surfaceOverride = custom.pageSurface,
        settingsSurface = custom.settingsSurface.orDefault(base.settingsSurface),
        drawerSurface = custom.drawerSurface,
        textPrimary = custom.pageText.orDefault(base.textPrimary),
        textSecondary = custom.pageMutedText.orDefault(base.textSecondary),
        cardBorder = custom.pageBorder.orDefault(base.cardBorder),
        divider = custom.pageDivider.orDefault(base.divider),
        dividerOverride = custom.pageDivider,
        disconnected = custom.idleAccent.orDefault(base.disconnected),
        connecting = custom.connectingAccent.orDefault(base.connecting),
        connected = custom.connectedAccent.orDefault(base.connected),
        disconnecting = custom.disconnectingAccent.orDefault(base.disconnecting),
        error = custom.errorAccent.orDefault(base.error),
        neonMagenta = custom.pageAccent.orDefault(base.neonMagenta),
        neonCyan = custom.secondaryAccent.orDefault(base.neonCyan),
        neonViolet = custom.tertiaryAccent.orDefault(base.neonViolet),
    )
}

internal object UacThemeState {
    var theme by mutableStateOf(AppTheme.CLASSIC)
    /** Set by the app shell: every page then draws with the shell's colours. */
    var override by mutableStateOf<UacPalette?>(null)
    val palette by derivedStateOf(structuralEqualityPolicy()) { override ?: paletteFor(theme) }
    val isNeon: Boolean get() = palette.neon
    val isLight: Boolean get() = palette.light
}

object UacColors {
    val BackgroundTop: Color get() = UacThemeState.palette.backgroundTop
    val BackgroundMiddle: Color get() = UacThemeState.palette.backgroundMiddle
    val BackgroundBottom: Color get() = UacThemeState.palette.backgroundBottom
    val Surface: Color get() = UacThemeState.palette.surface
    val SurfaceOverride: Color get() = UacThemeState.palette.surfaceOverride
    val DividerOverride: Color get() = UacThemeState.palette.dividerOverride
    val SettingsSurface: Color get() = UacThemeState.palette.settingsSurface
    val DrawerSurface: Color get() = UacThemeState.palette.drawerSurface
    val DisconnectedBlue: Color get() = UacThemeState.palette.disconnected
    val ConnectingCyan: Color get() = UacThemeState.palette.connecting
    val ConnectedGreen: Color get() = UacThemeState.palette.connected
    val DisconnectingAmber: Color get() = UacThemeState.palette.disconnecting
    val ErrorRed: Color get() = UacThemeState.palette.error
    val TextPrimary: Color get() = UacThemeState.palette.textPrimary
    val TextSecondary: Color get() = UacThemeState.palette.textSecondary
    val CardBorder: Color get() = UacThemeState.palette.cardBorder
    val Divider: Color get() = UacThemeState.palette.divider
    val ButtonCenter: Color get() = UacThemeState.palette.buttonCenter
    val ButtonEdge: Color get() = UacThemeState.palette.buttonEdge
    val ButtonInnerRing: Color get() = UacThemeState.palette.buttonInnerRing
    val NeonMagenta: Color get() = UacThemeState.palette.neonMagenta
    val NeonCyan: Color get() = UacThemeState.palette.neonCyan
    val NeonViolet: Color get() = UacThemeState.palette.neonViolet
}

/** Builds the shared page palette from the shell's colours (see ui/shell/ShellTheme). */
internal fun shellPalette(
    light: Boolean,
    bg: Color,
    surface: Color,
    surfaceHigh: Color,
    stroke: Color,
    text: Color,
    textDim: Color,
    accent: Color,
    good: Color,
    warn: Color,
    bad: Color,
): UacPalette = UacPalette(
    neon = false,
    backgroundTop = bg,
    backgroundMiddle = bg,
    backgroundBottom = bg,
    surface = surface,
    disconnected = accent,
    connecting = accent,
    connected = good,
    disconnecting = warn,
    error = bad,
    textPrimary = text,
    textSecondary = textDim,
    cardBorder = stroke,
    divider = stroke,
    buttonCenter = surface,
    buttonEdge = stroke,
    buttonInnerRing = surfaceHigh,
    neonMagenta = accent,
    neonCyan = accent,
    neonViolet = accent,
    settingsSurface = surface,
    drawerSurface = surface,
    surfaceOverride = surface,
    dividerOverride = stroke,
    light = light,
    neoHi = surface,
    neoLo = stroke,
    accentStart = accent,
    accentEnd = accent,
    flat = true,
    card = surface,
    cardHigh = surfaceHigh,
)

data class UiStateColors(val accent: Color)

fun colorsFor(state: ConnectionState): UiStateColors = when (state) {
    ConnectionState.DISCONNECTED -> UiStateColors(UacColors.DisconnectedBlue)
    ConnectionState.CONNECTING -> UiStateColors(UacColors.ConnectingCyan)
    ConnectionState.CONNECTED -> UiStateColors(UacColors.ConnectedGreen)
    ConnectionState.DISCONNECTING -> UiStateColors(UacColors.DisconnectingAmber)
    ConnectionState.ERROR -> UiStateColors(UacColors.ErrorRed)
}

@Composable
fun UacSniSpooferTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val themeStore = remember(context) { AppThemeStore.get(context) }
    val style by themeStore.style.collectAsState()
    LaunchedEffect(style.themeOfDay, style.autoDarkLight) {
        if (!style.themeOfDay && !style.autoDarkLight) return@LaunchedEffect
        while (true) {
            themeStore.refreshAutomatic()
            delay(5 * 60_000L)
        }
    }
    val palette = UacThemeState.palette
    val scheme = if (palette.light) lightColorScheme(
        primary = palette.connected,
        secondary = palette.disconnected,
        background = palette.backgroundTop,
        surface = palette.surface,
        onPrimary = Color.White,
        onBackground = palette.textPrimary,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        surfaceVariant = palette.settingsSurface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface,
        outline = palette.cardBorder,
        outlineVariant = palette.divider,
        error = palette.error,
    ) else darkColorScheme(
        primary = palette.connected,
        secondary = palette.disconnected,
        background = palette.backgroundTop,
        surface = palette.surface,
        onPrimary = palette.backgroundTop,
        onBackground = palette.textPrimary,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        surfaceVariant = palette.settingsSurface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface,
        outline = palette.cardBorder,
        outlineVariant = palette.divider,
        error = palette.error,
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = palette.light
                controller.isAppearanceLightNavigationBars = palette.light
            }
        }
    }
    ProvideFixedFontScale {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
