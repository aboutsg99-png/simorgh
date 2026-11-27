package com.uacspoofer.mobile.ui.engines

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.uacspoofer.mobile.engine.wireguard.ProtonVpn
import com.uacspoofer.mobile.engine.wireguard.WgSource
import com.uacspoofer.mobile.engine.wireguard.WgTunnelStore
import com.uacspoofer.mobile.ui.ToolPageBackground
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Proton's own sign-in page (account.protonvpn.com). The password, 2FA and captcha happen on
 * Proton's page; Simorgh only reads the session cookie it leaves, then fetches the account's
 * WireGuard servers ([ProtonVpn.sync]). Other sites open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ProtonSignInScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("با حساب Proton خودتان وارد شوید. بعد از ورود، سرورها خودکار اضافه می‌شوند.") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun trySync(view: WebView) {
        if (busy || done || !ProtonVpn.signedIn()) return
        busy = true
        message = "ورود انجام شد؛ دریافت سرورهای WireGuard حساب شما…"
        scope.launch {
            val version = suspendCancellableCoroutine<String> { c ->
                runCatching {
                    view.evaluateJavascript("(window.SENTRY_RELEASE&&SENTRY_RELEASE.id)||''") { raw ->
                        if (c.isActive) c.resume(raw.orEmpty().trim('"').takeIf { it.matches(Regex("[0-9.]{3,20}")) }.orEmpty())
                    }
                }.onFailure { if (c.isActive) c.resume("") }
            }
            val ua = view.settings.userAgentString.orEmpty()
            val result = try {
                ProtonVpn.sync(view.context.applicationContext, version, ua)
            } catch (e: kotlinx.coroutines.CancellationException) {
                busy = false
                throw e
            } catch (e: Throwable) {
                ProtonVpn.Outcome.Failed(e.message.orEmpty().ifBlank { "network" })
            }
            message = when (val out = result) {
                is ProtonVpn.Outcome.Got -> {
                    runCatching { WgTunnelStore.get(view.context).replaceSource(WgSource.PROTON, out.tunnels) }
                    done = true
                    val countries = out.tunnels.mapNotNull { it.countryCode }.distinct().size
                    "${out.tunnels.size} سرور Proton VPN در $countries کشور اضافه شد. می‌توانید برگردید."
                }
                ProtonVpn.Outcome.NotSignedIn -> "هنوز وارد نشده‌اید."
                ProtonVpn.Outcome.Expired -> "نشست Proton منقضی شد؛ دوباره وارد شوید."
                is ProtonVpn.Outcome.Failed -> "دریافت سرورها نشد: ${out.detail.take(120)}"
            }
            busy = false
        }
    }

    ToolPageBackground(UacColors.DisconnectedBlue) {
        Column(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "برگشت", tint = UacColors.TextPrimary) }
                Text("ورود به Proton VPN", color = UacColors.TextPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EngineBody(message, color = if (done) UacColors.ConnectedGreen else UacColors.TextSecondary)
                if (ProtonVpn.signedIn() && !busy) {
                    EngineSecondaryButton(if (done) "دریافت دوباره" else "دریافت سرورها") { done = false; webView?.let(::trySync) }
                }
                if (progress in 1..99 || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).padding(top = 8.dp)) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme?.lowercase().orEmpty()
                                    if (scheme != "https") return true
                                    val host = request.url.host?.lowercase().orEmpty()
                                    if (PROTON_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                                    runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    return true
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    runCatching { CookieManager.getInstance().flush() }
                                    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                                    if (host == ProtonVpn.DASHBOARD_HOST && url?.contains("/login") == false) trySync(view)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                            }
                            loadUrl(ProtonVpn.SIGN_IN_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view -> webView = null; runCatching { view.stopLoading(); view.destroy() } },
                )
            }
        }
    }
}

private val PROTON_HOSTS = listOf("protonvpn.com", "proton.me")
