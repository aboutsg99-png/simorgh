package com.uacspoofer.mobile.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.foxy.FoxyErrors
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyPing
import com.uacspoofer.mobile.engine.foxy.FoxyPingResult
import com.uacspoofer.mobile.engine.foxy.RECOMMENDED_COUNTRY_CODE
import com.uacspoofer.mobile.engine.foxy.ServerListClient
import com.uacspoofer.mobile.engine.foxy.VpnCountry
import com.uacspoofer.mobile.engine.foxy.displayName
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Real measurement state of one country: never estimated, null until measured. */
private sealed interface FoxyCountryPing {
    object Measuring : FoxyCountryPing
    data class Done(val best: FoxyPingResult?, val reachable: Int, val tested: Int) : FoxyCountryPing
}

/** Servers measured per country: enough to find a reachable edge without flooding the network. */
private const val PINGS_PER_COUNTRY = 2

@Composable
internal fun FoxyCountryScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val store = remember(context) { FoxyEngineStore.get(context) }
    val client = remember(context) { ServerListClient(context.applicationContext) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val account = remember(context) { FoxyAccount.get(context) }
    val accountStatus by account.status.collectAsStateWithLifecycle()
    val foxyStatus by FoxyStatusStore.status.collectAsStateWithLifecycle()
    var signingIn by rememberSaveable { mutableStateOf(false) }
    var accountBusy by remember { mutableStateOf(false) }
    var signInMessage by remember { mutableStateOf<String?>(null) }
    val connectionState by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val nameLocale = if (isPersian) Locale("fa") else Locale.ENGLISH
    var query by rememberSaveable { mutableStateOf("") }
    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    val pings = remember { mutableStateMapOf<String, FoxyCountryPing>() }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = UacColors.DisconnectedBlue
    val selectedCode = settings.countryCode

    fun localName(country: VpnCountry): String {
        val iso = country.code.trim().uppercase(Locale.ROOT)
        val localized = if (iso.length == 2) Locale("", iso).getDisplayCountry(nameLocale) else ""
        return localized.takeIf { it.isNotBlank() && !it.equals(iso, ignoreCase = true) } ?: country.displayName
    }

    fun notify(message: String) {
        snackbarJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch { snackbar.showSnackbar(message) }
    }

    fun measureAll(list: List<VpnCountry>) {
        pingJob?.cancel()
        pingJob = scope.launch {
            val targets = list.associate { country ->
                country.code to ServerListClient.candidatesForCountry(list, country.code)
                    .shuffled()
                    .take(PINGS_PER_COUNTRY)
            }
            targets.keys.forEach { pings[it] = FoxyCountryPing.Measuring }
            val ranked = FoxyPing.rank(targets.values.flatten(), parallel = 8)
            targets.forEach { (code, candidates) ->
                val results = ranked.filter { (candidate, _) -> candidate.countryCode == code }.map { it.second }
                pings[code] = FoxyCountryPing.Done(
                    best = results.filter { it.reachable }.minByOrNull { it.tlsMs ?: Int.MAX_VALUE },
                    reachable = results.count { it.reachable },
                    tested = candidates.size,
                )
            }
        }
    }

    fun load(forceNetwork: Boolean) {
        scope.launch {
            loading = true
            loadFailed = false
            val cached = withContext(Dispatchers.IO) { runCatching { client.loadCachedCountries() }.getOrDefault(emptyList()) }
            if (cached.isNotEmpty()) countries = cached
            val fresh = if (!forceNetwork && cached.isNotEmpty() && client.hasFreshCache()) {
                cached
            } else {
                try {
                    client.fetchCountries()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
            if (fresh != null) countries = fresh
            loadFailed = countries.isEmpty()
            loading = false
            if (countries.isNotEmpty()) measureAll(countries)
        }
    }

    fun persistCountry(code: String) {
        val next = settings.copy(countryCode = code).validated()
        if (next.countryCode == settings.countryCode) return
        store.save(next)
        val name = if (next.countryCode.isEmpty()) {
            if (isPersian) "سریع‌ترین سرور" else "Fastest server"
        } else {
            countries.firstOrNull { it.code.equals(next.countryCode, ignoreCase = true) }?.let { localName(it) }
                ?: next.countryCode
        }
        val live = connectionState == ConnectionState.CONNECTED
        if (live) VpnController.applyFoxyCountry(context)
        notify(
            when {
                live && isPersian -> "در حال اتصال مجدد به $name…"
                live -> "Reconnecting to $name…"
                isPersian -> "$name برای اتصال بعدی انتخاب شد"
                else -> "$name selected for the next connection"
            },
        )
    }

    LaunchedEffect(Unit) { load(forceNetwork = false) }
    LaunchedEffect(Unit) {
        accountBusy = true
        try {
            account.refreshStatus()
        } finally {
            accountBusy = false
        }
    }

    if (signingIn) {
        FoxySignInScreen(
            startUrl = remember { account.loginUrl() },
            busy = accountBusy,
            message = signInMessage,
            onSessionCaptured = { sessionToken ->
                if (!accountBusy) {
                    scope.launch {
                        accountBusy = true
                        signInMessage = null
                        val result = try {
                            account.completeSignIn(sessionToken)
                        } finally {
                            accountBusy = false
                        }
                        result
                            .onSuccess {
                                signingIn = false
                                notify(if (isPersian) "ورود به حساب فایرفاکس انجام شد" else "Signed in to Firefox account")
                            }
                            .onFailure { signInMessage = FoxyErrors.persian(it) }
                    }
                }
            },
            onCancel = {
                signingIn = false
                signInMessage = null
            },
        )
        return
    }
    LaunchedEffect(query) { listState.scrollToItem(0) }

    val visible = remember(countries, query, isPersian) {
        val q = query.trim()
        countries
            .filter { !it.code.equals(RECOMMENDED_COUNTRY_CODE, ignoreCase = true) }
            .filter { ServerListClient.candidatesForCountry(countries, it.code).isNotEmpty() }
            .filter { country ->
                q.isEmpty() || localName(country).contains(q, ignoreCase = true) ||
                    country.displayName.contains(q, ignoreCase = true) ||
                    country.code.equals(q, ignoreCase = true)
            }
            .sortedBy { localName(it) }
    }
    val sorted = visible.sortedWith(
        compareBy<VpnCountry>(
            { (pings[it.code] as? FoxyCountryPing.Done)?.best == null },
            { (pings[it.code] as? FoxyCountryPing.Done)?.best?.tlsMs ?: Int.MAX_VALUE },
        ),
    )
    val measuring = pings.values.any { it is FoxyCountryPing.Measuring }
    val selectedLabel = if (selectedCode.isEmpty()) {
        homeText("Fastest server", "سریع‌ترین سرور")
    } else {
        countries.firstOrNull { it.code.equals(selectedCode, ignoreCase = true) }?.let { localName(it) } ?: selectedCode
    }

    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
        ToolPageBackground(accent = accent) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                        ),
                ) {
                    WideSplitColumn(
                        headerPadding = 16.dp,
                        header = {
                            Spacer(Modifier.height(8.dp))
                            TorCountryTopBar(subtitle = selectedLabel, onMenuClick = onMenuClick)
                        },
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(15.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        keyboard?.hide()
                                        focusManager.clearFocus()
                                    },
                                ),
                                placeholder = {
                                    Text(
                                        homeText("Search country", "جست‌وجوی کشور"),
                                        color = UacColors.TextSecondary,
                                        fontSize = 14.sp,
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null, tint = UacColors.TextSecondary)
                                },
                                trailingIcon = if (query.isNotEmpty()) {
                                    {
                                        RemoteIconButton(onClick = { query = "" }) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = homeText("Clear search", "پاک‌کردن جست‌وجو"),
                                                tint = UacColors.TextSecondary,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = UacColors.TextPrimary,
                                    unfocusedTextColor = UacColors.TextPrimary,
                                    focusedBorderColor = accent,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                                    cursorColor = accent,
                                    focusedContainerColor = Color(0x1FFFFFFF),
                                    unfocusedContainerColor = Color(0x66101C29),
                                ),
                            )
                            RemoteIconButton(
                                onClick = {
                                    if (countries.isEmpty()) load(forceNetwork = true) else measureAll(countries)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0x1FFFFFFF), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                            ) {
                                if (measuring || loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = accent,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.NetworkCheck,
                                        contentDescription = homeText("Measure ping again", "اندازه‌گیری دوباره پینگ"),
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(
                                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                            ),
                        ) {
                            if (query.isBlank()) {
                                item(key = "account") {
                                    FoxyAccountCard(
                                        status = accountStatus,
                                        busy = accountBusy,
                                        onSignIn = {
                                            signInMessage = null
                                            signingIn = true
                                        },
                                        onSignOut = {
                                            scope.launch {
                                                accountBusy = true
                                                try {
                                                    account.signOut()
                                                } finally {
                                                    accountBusy = false
                                                }
                                            }
                                        },
                                    )
                                }
                                item(key = "settings") {
                                    FoxySettingsPanel(
                                        settings = settings,
                                        status = foxyStatus,
                                        onProtocol = { protocol -> store.update { it.copy(protocol = protocol) } },
                                        onFallback = { fallback -> store.update { it.copy(fallback = fallback) } },
                                    )
                                }
                                item(key = "fastest") {
                                    FoxyCountryRow(
                                        title = homeText("Fastest server", "سریع‌ترین سرور"),
                                        subtitle = homeText(
                                            "Picks the server with the lowest measured ping",
                                            "سروری با کمترین پینگ اندازه‌گیری‌شده انتخاب می‌شود",
                                        ),
                                        country = null,
                                        automatic = true,
                                        ping = null,
                                        selected = selectedCode.isEmpty(),
                                        onSelect = { persistCountry("") },
                                    )
                                }
                            }
                            when {
                                countries.isEmpty() && loading -> item(key = "loading") {
                                    FoxyInfoLine(
                                        homeText("Loading server list…", "در حال دریافت فهرست سرورها…"),
                                        loading = true,
                                    )
                                }
                                countries.isEmpty() && loadFailed -> item(key = "failed") {
                                    FoxyRetryRow(onRetry = { load(forceNetwork = true) })
                                }
                                sorted.isEmpty() -> item(key = "empty") {
                                    FoxyInfoLine(homeText("No country matches", "کشوری پیدا نشد"))
                                }
                                else -> {
                                    item(key = "label") {
                                        CountrySectionLabel(
                                            homeText(
                                                "Countries (${sorted.size}), fastest first",
                                                "کشورها (${faNumber(sorted.size)})، سریع‌ترین در بالا",
                                            ),
                                        )
                                    }
                                    items(sorted, key = { "c-${it.code}" }) { country ->
                                        val servers = ServerListClient.candidatesForCountry(countries, country.code).size
                                        val cities = country.cities.count { city -> city.servers.any { !it.quarantined } }
                                        FoxyCountryRow(
                                            title = localName(country),
                                            subtitle = homeText(
                                                "${country.code.uppercase(Locale.ROOT)}  •  $cities cities  •  $servers servers",
                                                "${country.code.uppercase(Locale.ROOT)}  •  ${faNumber(cities)} شهر  •  ${faNumber(servers)} سرور",
                                            ),
                                            country = CountryMetadata.resolve(country.code, country.displayName),
                                            automatic = false,
                                            ping = pings[country.code],
                                            selected = selectedCode.equals(country.code, ignoreCase = true),
                                            onSelect = { persistCountry(country.code) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun FoxyCountryRow(
    title: String,
    subtitle: String,
    country: CountryMetadata?,
    automatic: Boolean,
    ping: FoxyCountryPing?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val accent = if (selected) UacColors.ConnectedGreen else UacColors.DisconnectedBlue
    val shape = RoundedCornerShape(16.dp)
    val (interaction, remoteFocused) = rememberRemoteRowFocus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    remoteFocused -> UacColors.DisconnectedBlue.copy(alpha = 0.18f)
                    selected -> accent.copy(alpha = 0.075f)
                    else -> Color(0x1FFFFFFF)
                },
                shape,
            )
            .border(
                width = if (remoteFocused) 2.dp else 1.dp,
                color = if (remoteFocused) UacColors.DisconnectedBlue else accent.copy(alpha = if (selected) 0.48f else 0.13f),
                shape = shape,
            )
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onSelect)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badgeColor = if (country?.isKnown == true) Color(0xFF8D7CFF) else UacColors.DisconnectedBlue
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(badgeColor.copy(alpha = if (selected) 0.18f else 0.11f), RoundedCornerShape(13.dp))
                .border(1.dp, badgeColor.copy(alpha = 0.26f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                automatic -> Icon(Icons.Outlined.Bolt, null, tint = badgeColor, modifier = Modifier.size(22.dp))
                country?.isKnown == true -> CountryFlagIcon(country, size = 29.dp)
                else -> Icon(Icons.Outlined.Public, null, tint = badgeColor, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = if (selected) accent.copy(alpha = 0.92f) else UacColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!automatic) {
            Spacer(Modifier.size(8.dp))
            FoxyPingChip(ping)
        }
        if (selected) {
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier.size(30.dp).background(accent.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, homeText("Selected", "انتخاب‌شده"), tint = accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FoxyPingChip(ping: FoxyCountryPing?) {
    val shape = RoundedCornerShape(10.dp)
    val done = ping as? FoxyCountryPing.Done
    val ms = done?.best?.tlsMs
    val color = when {
        ms == null -> UacColors.TextSecondary
        ms < 250 -> UacColors.ConnectedGreen
        ms < 600 -> Color(0xFFFFC857)
        else -> Color(0xFFFF8A65)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), shape)
            .border(1.dp, color.copy(alpha = 0.25f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            ping == null -> Text("—", color = color, fontSize = 11.sp)
            ping is FoxyCountryPing.Measuring -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = UacColors.DisconnectedBlue,
                strokeWidth = 1.6.dp,
            )
            ms != null -> Text(
                homeText("$ms ms", "${faNumber(ms)} میلی‌ثانیه"),
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, tint = color, modifier = Modifier.size(13.dp))
                Spacer(Modifier.size(4.dp))
                Text(homeText("Unreachable", "در دسترس نیست"), color = color, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FoxyInfoLine(text: String, loading: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = UacColors.DisconnectedBlue, strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
        }
        Text(text, color = UacColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun FoxyRetryRow(onRetry: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x1FFFFFFF), shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.CloudOff, null, tint = UacColors.TextSecondary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            homeText(
                "Could not load the server list. Check your internet and try again.",
                "دریافت فهرست سرورها ممکن نشد. اینترنت را بررسی و دوباره تلاش کنید.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        RemoteIconButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, homeText("Retry", "تلاش دوباره"), tint = Color.White)
        }
    }
}

private fun faNumber(value: Int): String = String.format(Locale("fa"), "%d", value)
