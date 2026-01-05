package com.naenwa.remote.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Claude OAuth 토큰 관리
 * SharedPreferences에 토큰을 저장하고 자동 갱신 처리
 */
class ClaudeTokenManager(context: Context) {

    companion object {
        private const val TAG = "ClaudeTokenManager"
        private const val PREFS_NAME = "claude_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        // Claude OAuth 설정
        private const val TOKEN_ENDPOINT = "https://console.anthropic.com/api/oauth/token"
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

        // 토큰 만료 여유 시간 (5분 전에 갱신)
        private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 토큰 저장
     */
    fun saveTokens(accessToken: String, refreshToken: String?, expiresIn: Long) {
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            putLong(KEY_EXPIRES_AT, expiresAt)
            apply()
        }

        Log.i(TAG, "Tokens saved. Expires at: $expiresAt")
    }

    /**
     * Access Token 가져오기
     */
    fun getAccessToken(): String? {
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Refresh Token 가져오기
     */
    fun getRefreshToken(): String? {
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * 유효한 토큰이 있는지 확인
     */
    fun hasValidToken(): Boolean {
        val token = getAccessToken()
        if (token.isNullOrEmpty()) return false

        // 만료 확인 (refresh token이 있으면 갱신 가능하므로 true)
        if (isTokenExpired()) {
            return getRefreshToken() != null
        }

        return true
    }

    /**
     * 토큰 만료 여부 확인
     */
    fun isTokenExpired(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        return System.currentTimeMillis() > (expiresAt - EXPIRY_BUFFER_MS)
    }

    /**
     * 토큰 정보 가져오기
     */
    fun getTokenInfo(): TokenInfo? {
        val accessToken = getAccessToken() ?: return null
        val refreshToken = getRefreshToken()
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)

        return TokenInfo(
            hasAccessToken = accessToken.isNotEmpty(),
            hasRefreshToken = !refreshToken.isNullOrEmpty(),
            expiresAt = expiresAt,
            isExpired = isTokenExpired()
        )
    }

    /**
     * 토큰 삭제
     */
    fun clearTokens() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Tokens cleared")
    }

    /**
     * Access Token 갱신
     */
    fun refreshAccessToken(): Boolean = runBlocking {
        refreshAccessTokenAsync()
    }

    /**
     * Access Token 비동기 갱신
     */
    suspend fun refreshAccessTokenAsync(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken()
        if (refreshToken.isNullOrEmpty()) {
            Log.w(TAG, "No refresh token available")
            return@withContext false
        }

        try {
            val requestJson = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", CLIENT_ID)
            }

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Refreshing access token...")

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Token refresh failed: ${response.code} - $body")
                    return@withContext false
                }

                val json = JSONObject(body ?: "{}")
                val newAccessToken = json.optString("access_token")
                val newRefreshToken = json.optString("refresh_token", refreshToken)
                val expiresIn = json.optLong("expires_in", 28800)

                if (newAccessToken.isNotEmpty()) {
                    saveTokens(newAccessToken, newRefreshToken, expiresIn)
                    Log.i(TAG, "Token refreshed successfully")
                    return@withContext true
                }

                Log.e(TAG, "No access token in refresh response")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            return@withContext false
        }
    }
}
