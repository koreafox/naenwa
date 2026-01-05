package com.naenwa.remote.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Claude OAuth 인증 핸들러
 * Pro/Max 구독자가 CLI처럼 로그인할 수 있게 함
 */
class ClaudeOAuth(private val context: Context) {

    companion object {
        private const val TAG = "ClaudeOAuth"

        // Claude CLI의 OAuth 설정
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val AUTHORIZE_URL = "https://console.anthropic.com/oauth/authorize"
        private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"

        // 수동 코드 입력 방식 (CLI와 동일)
        private const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"

        private const val PREFS_NAME = "claude_oauth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CODE_VERIFIER = "code_verifier"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * OAuth 로그인 시작 - 브라우저로 인증 페이지 열기
     */
    fun startLogin(): Intent {
        // PKCE code verifier 생성
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // 저장 (callback에서 사용)
        prefs.edit().putString(KEY_CODE_VERIFIER, codeVerifier).apply()

        // OAuth URL 생성
        val authUrl = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "user:inference")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        Log.d(TAG, "Opening OAuth URL: $authUrl")

        return Intent(Intent.ACTION_VIEW, authUrl)
    }

    /**
     * OAuth callback 처리 - auth code를 token으로 교환 (URI에서)
     */
    suspend fun handleCallback(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code")
        if (code == null) {
            Log.e(TAG, "No code in callback URI")
            return@withContext false
        }
        exchangeCodeForToken(code)
    }

    /**
     * 수동으로 입력한 인증 코드를 토큰으로 교환
     */
    suspend fun exchangeCodeForToken(code: String): Boolean = withContext(Dispatchers.IO) {
        val codeVerifier = prefs.getString(KEY_CODE_VERIFIER, null)
        if (codeVerifier == null) {
            Log.e(TAG, "No code verifier saved")
            return@withContext false
        }

        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", codeVerifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Token response: $responseBody")

                if (!response.isSuccessful) {
                    Log.e(TAG, "Token exchange failed: ${response.code}")
                    return@withContext false
                }

                val json = JSONObject(responseBody ?: "{}")

                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token", "")
                val expiresIn = json.optLong("expires_in", 3600)

                if (accessToken.isEmpty()) {
                    Log.e(TAG, "No access token in response")
                    return@withContext false
                }

                // 토큰 저장
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, accessToken)
                    .putString(KEY_REFRESH_TOKEN, refreshToken)
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
                    .remove(KEY_CODE_VERIFIER)
                    .apply()

                Log.i(TAG, "OAuth login successful!")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            false
        }
    }

    /**
     * 저장된 액세스 토큰 가져오기
     */
    fun getAccessToken(): String? {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (System.currentTimeMillis() > expiresAt) {
            Log.w(TAG, "Access token expired")
            return null
        }
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * 로그인 상태 확인
     */
    fun isLoggedIn(): Boolean = getAccessToken() != null

    /**
     * 로그아웃
     */
    fun logout() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Logged out")
    }

    // PKCE code verifier 생성
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // PKCE code challenge 생성 (SHA-256)
    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
