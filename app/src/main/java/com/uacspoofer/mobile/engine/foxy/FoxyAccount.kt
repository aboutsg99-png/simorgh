package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class FoxyNotSignedInException : IllegalStateException("Firefox account is not signed in")

enum class FoxyAccountState { UNKNOWN, SIGNED_OUT, SIGNED_IN, OFFLINE }

data class FoxyAccountStatus(
    val state: FoxyAccountState = FoxyAccountState.UNKNOWN,
    val email: String = "",
    val quotaRemaining: Long? = null,
    val quotaMax: Long? = null,
)

/**
 * Per-user Firefox account session. Every user signs in with their own Mozilla account
 * inside the app; nothing is shared or bundled. Tokens live only in [TokenStore]
 * (AES-GCM with an Android Keystore key). All calls are suspend + IO + bounded by timeouts.
 */
class FoxyAccount private constructor(context: Context) {
    private val appContext = context.applicationContext
    val tokenStore = TokenStore(appContext)
    val auth = FxaAuthRepository(tokenStore)
    private val profilePrefs by lazy { SecurePreferences(appContext, "foxy_profile") }
    private val mutable = MutableStateFlow(FoxyAccountStatus())
    val status: StateFlow<FoxyAccountStatus> = mutable.asStateFlow()

    fun loginUrl(): String = auth.getBrowserLoginUrl()

    fun hasStoredSession(): Boolean = runCatching { tokenStore.hasStoredSession() }.getOrDefault(false)

    /** Called from the WebView bridge once Mozilla hands over a session token. */
    suspend fun completeSignIn(sessionToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = try {
            // Covers email confirmation codes / 2FA: completeLogin polls up to ~5 minutes.
            withTimeout(6 * 60_000L) { auth.completeBrowserLogin(sessionToken) }
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(FxaApiError("Sign-in verification timed out", null, 408))
        }
        if (result.isSuccess) {
            refreshProfile()
        }
        result
    }

    /** Restores the session at screen open; refreshes the access token when needed. */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        if (!hasStoredSession()) {
            mutable.value = FoxyAccountStatus(FoxyAccountState.SIGNED_OUT)
            return@withContext
        }
        val session = withTimeoutOrNull(30_000L) {
            runCatching { auth.restoreSession() }.getOrDefault(SessionStatus.UNREACHABLE)
        } ?: SessionStatus.UNREACHABLE
        val email = runCatching { profilePrefs.getString(KEY_EMAIL, "") }.getOrNull().orEmpty()
        mutable.value = when (session) {
            SessionStatus.ACTIVE -> mutable.value.copy(state = FoxyAccountState.SIGNED_IN, email = email)
            SessionStatus.NEEDS_LOGIN -> FoxyAccountStatus(FoxyAccountState.SIGNED_OUT)
            SessionStatus.UNREACHABLE -> mutable.value.copy(state = FoxyAccountState.OFFLINE, email = email)
        }
        if (session == SessionStatus.ACTIVE && email.isBlank()) refreshProfile()
    }

    private suspend fun refreshProfile() {
        val email = withTimeoutOrNull(15_000L) {
            runCatching {
                val token = auth.currentAccessToken() ?: return@runCatching ""
                val request = Request.Builder()
                    .url(PROFILE_URL)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
                ControlPlaneHttp.client.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) return@runCatching ""
                    JSONObject(response.body?.readBoundedText(64 * 1024).orEmpty()).optString("email")
                }
            }.getOrNull()
        }.orEmpty()
        if (email.isNotBlank()) runCatching { profilePrefs.edit().putString(KEY_EMAIL, email).commit() }
        mutable.value = mutable.value.copy(state = FoxyAccountState.SIGNED_IN, email = email.ifBlank { mutable.value.email })
    }

    fun publishQuota(remaining: Long?, max: Long?) {
        mutable.value = mutable.value.copy(quotaRemaining = remaining, quotaMax = max)
    }

    /** Revokes the refresh token at Mozilla (best effort) and wipes every local trace. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val refresh = runCatching { tokenStore.loadAuth()?.refreshToken }.getOrNull()
        if (!refresh.isNullOrBlank()) {
            withTimeoutOrNull(8_000L) {
                runCatching {
                    val body = JSONObject().put("refresh_token", refresh).toString()
                        .toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(DESTROY_URL).applyFirefoxHeaders().post(body).build()
                    ControlPlaneHttp.client.newCall(request).awaitResponse().close()
                }
            }
        }
        runCatching { tokenStore.clear() }
        runCatching { profilePrefs.edit().clear().commit() }
        ControlPlaneHttp.cookieJar.clear()
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        mutable.value = FoxyAccountStatus(FoxyAccountState.SIGNED_OUT)
    }

    companion object {
        private const val KEY_EMAIL = "email"
        private const val PROFILE_URL = "https://profile.accounts.firefox.com/v1/profile"
        private const val DESTROY_URL = "https://oauth.accounts.firefox.com/v1/destroy"

        @Volatile private var instance: FoxyAccount? = null

        fun get(context: Context): FoxyAccount = instance ?: synchronized(this) {
            instance ?: FoxyAccount(context.applicationContext).also { instance = it }
        }
    }
}

/** Plain Persian explanation for anything the Firefox VPN path throws. */
object FoxyErrors {
    fun persian(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            error == null -> "خطای ناشناخته"
            error is FoxyNotSignedInException -> "اول با حساب فایرفاکس خودت وارد شو."
            error is FoxyBinaryMissingException -> "هسته‌ی روباه داخل این نسخه نیست. نسخه‌ی کامل را نصب کن."
            error is SecureStorageException -> "حافظه‌ی امن گوشی در دسترس نیست. قفل گوشی را باز کن و دوباره امتحان کن."
            error is QuotaExceededError -> "سهمیه‌ی رایگان این ماه حساب فایرفاکس تمام شده."
            error is FastlyBlockedException -> "سد ضدربات موزیلا درخواست را رد کرد (۴۰۶). شبکه را عوض کن (وای‌فای / دیتا) و دوباره امتحان کن."
            error is TokenInvalidError -> "موزیلا اجازه‌ی VPN را به این حساب نداد. ممکن است در کشور تو یا برای این حساب فعال نباشد."
            error is FxaRefreshFailure && error.permanent -> "نشست حساب منقضی شده. دوباره وارد شو."
            error is FxaApiError && error.isUnconfirmedSession -> "ورود هنوز تأیید نشده. کد ارسال‌شده به ایمیلت را در صفحه وارد کن."
            error is FxaApiError && error.statusCode == 408 -> "تأیید ورود طول کشید. دوباره امتحان کن."
            error is FxaApiError && (error.errno == 103 || message.contains("password", true)) -> "رمز عبور اشتباه است."
            error is FxaApiError -> "حساب فایرفاکس خطا داد: ${message.take(120)}"
            error is GuardianHttpError && error.statusCode == 429 -> "موزیلا موقتاً درخواست‌ها را محدود کرده. چند دقیقه بعد امتحان کن."
            error is GuardianHttpError && (error.statusCode == 451 || error.statusCode == 403) -> "سرویس VPN فایرفاکس برای این منطقه یا حساب در دسترس نیست."
            error is GuardianHttpError -> "سرور VPN موزیلا پاسخ نداد (${error.statusCode ?: "شبکه"})."
            error is FoxyUnusableException -> "هیچ مسیری به سرورهای روباه سرعت قابل‌قبول نداد."
            error is CancellationException -> "لغو شد."
            error is java.net.UnknownHostException -> "نام سرور پیدا نشد. اینترنت یا DNS را بررسی کن."
            error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException -> "زمان اتصال تمام شد. شبکه کند است یا مسدود شده."
            error is java.io.IOException -> "خطای شبکه: ${message.take(100)}"
            else -> message.take(140).ifBlank { "خطای ناشناخته" }
        }
    }
}
