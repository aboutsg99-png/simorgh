package com.uacspoofer.mobile.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.uacspoofer.mobile.engine.foxy.FirefoxAccountHeaders
import com.uacspoofer.mobile.ui.theme.UacColors
import java.lang.ref.WeakReference
import org.json.JSONObject

/*
 * Firefox account sign-in, adapted from Vauth/FoxyVPN BrowserLoginScreen (MIT).
 *
 * How it works: Mozilla's own accounts.firefox.com page runs in a WebView in the
 * Firefox-desktop "WebChannel" context. The user types their email, password and any
 * verification / two-step code on Mozilla's page; Blackdey never sees the password. When
 * Mozilla finishes, the page sends `fxaccounts:login` over the WebChannel with a session
 * token; Blackdey exchanges it for OAuth tokens scoped to the VPN and destroys the session.
 */
private const val FXA_CHANNEL_ID = "account_updates"
private const val CMD_FXA_STATUS = "fxaccounts:fxa_status"
private const val CMD_CAN_LINK_ACCOUNT = "fxaccounts:can_link_account"
private const val CMD_LOGIN = "fxaccounts:login"
private val SESSION_TOKEN_HEX = Regex("^[0-9a-f]{64}$")

private fun isMozillaAccountsHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "accounts.firefox.com" || h.endsWith(".firefox.com")
}

private val WEBCHANNEL_HOOK_JS = """
(function() {
  if (window.__smgChannelHooked) return;
  window.__smgChannelHooked = true;
  var send = function(o) {
    try {
      var host = window.location.hostname;
      if (host !== 'accounts.firefox.com' && !host.endsWith('.firefox.com')) return;
      var payload = typeof o === 'string' ? o : JSON.stringify(o);
      smgWebChannel.onMessage(payload, window.location.href);
    } catch (e) {}
  };
  var onChannel = function(e) {
    var d = e.detail;
    if (typeof d === 'string') { try { d = JSON.parse(d); } catch (x) { return; } }
    if (d && d.message) send(d);
  };
  window.addEventListener('WebChannelMessageToChrome', onChannel);
  document.addEventListener('WebChannelMessageToChrome', onChannel);
  window.__smgReply = function(id, messageId, command, data) {
    var detail = { id: id, message: { messageId: messageId, command: command, data: data } };
    window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
    document.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
  };
})();
""".trimIndent()

internal class FoxyWebChannelBridge(
    webView: WebView,
    private val onSessionToken: (String) -> Unit,
) {
    private val webViewRef = WeakReference(webView)
    @Volatile private var lastSessionToken: String? = null

    @JavascriptInterface
    fun onMessage(payload: String, originUrl: String) {
        runCatching { handle(payload, originUrl) }
    }

    private fun handle(payload: String, originUrl: String) {
        if (!isMozillaAccountsHost(runCatching { Uri.parse(originUrl).host }.getOrNull())) return
        if (payload.length > 64 * 1024) return
        val envelope = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val channelId = envelope.optString("id").ifBlank { FXA_CHANNEL_ID }
        val message = envelope.optJSONObject("message") ?: JSONObject()
        val command = message.optString("command")
        val data = message.optJSONObject("data") ?: JSONObject()
        val messageId = message.optString("messageId")
        when (command) {
            CMD_FXA_STATUS -> reply(channelId, messageId, command, """{"capabilities":{},"signedInUser":null}""")
            CMD_CAN_LINK_ACCOUNT -> reply(channelId, messageId, command, """{"ok":true}""")
            CMD_LOGIN, "fxaccounts:oauth_login" -> {
                val token = data.optString("sessionToken", "").trim().ifBlank { data.optString("token", "").trim() }
                if (SESSION_TOKEN_HEX.matches(token) && token != lastSessionToken) {
                    lastSessionToken = token
                    runCatching { CookieManager.getInstance().flush() }
                    val view = webViewRef.get()
                    if (view != null) view.post { onSessionToken(token) } else onSessionToken(token)
                }
                if (messageId.isNotBlank()) reply(channelId, messageId, command, """{"ok":true}""")
            }
            else -> if (messageId.isNotBlank()) reply(channelId, messageId, command, """{"ok":true}""")
        }
    }

    private fun reply(channelId: String, messageId: String, command: String, dataJson: String) {
        val view = webViewRef.get() ?: return
        val script = "window.__smgReply && window.__smgReply(" +
            "${JSONObject.quote(channelId)}, ${JSONObject.quote(messageId)}, ${JSONObject.quote(command)}, $dataJson);"
        view.post { runCatching { view.evaluateJavascript(script, null) } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun FoxySignInScreen(
    startUrl: String,
    busy: Boolean,
    message: String?,
    onSessionCaptured: (String) -> Unit,
    onCancel: () -> Unit,
    /** Sees every page the WebView opens; returns true when it consumed the URL (Mozilla VPN sign-in code). */
    onUrl: ((String) -> Boolean)? = null,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val currentOnSession by rememberUpdatedState(onSessionCaptured)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnUrl by rememberUpdatedState(onUrl)

    BackHandler(enabled = true) {
        if (webView?.canGoBack() == true) webView?.goBack() else currentOnCancel()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B121B))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() else currentOnCancel() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = homeText("Back", "بازگشت"),
                    tint = UacColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    homeText("Sign in with Firefox", "ورود با حساب فایرفاکس"),
                    color = UacColors.TextPrimary,
                    fontSize = 16.sp,
                )
                Text(
                    homeText(
                        "Mozilla's own page. Your password stays with Mozilla.",
                        "صفحه‌ی خود موزیلا است؛ رمز عبورت فقط به موزیلا می‌رسد.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        if (busy || progress in 1..99) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (!message.isNullOrBlank()) {
            Text(
                message,
                color = if (busy) UacColors.TextSecondary else UacColors.ErrorRed,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.userAgentString = FirefoxAccountHeaders.browserUserAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        val bridge = FoxyWebChannelBridge(this) { token -> currentOnSession(token) }
                        addJavascriptInterface(bridge, "smgWebChannel")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (currentOnUrl?.invoke(request.url.toString()) == true) return true
                                val scheme = request.url.scheme?.lowercase().orEmpty()
                                if (scheme != "http" && scheme != "https") return true
                                val host = request.url.host?.lowercase().orEmpty()
                                if (isMozillaAccountsHost(host) || host == "mozilla.org" || host.endsWith(".mozilla.org")) {
                                    return false
                                }
                                runCatching {
                                    view.context.startActivity(
                                        Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (url != null && currentOnUrl?.invoke(url) == true) { runCatching { view.stopLoading() }; return }
                                runCatching { view.evaluateJavascript(WEBCHANNEL_HOOK_JS, null) }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                runCatching { CookieManager.getInstance().flush() }
                                runCatching { view.evaluateJavascript(WEBCHANNEL_HOOK_JS, null) }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    webView = null
                    runCatching {
                        view.stopLoading()
                        view.removeJavascriptInterface("smgWebChannel")
                        view.destroy()
                    }
                },
            )
        }
    }
}
