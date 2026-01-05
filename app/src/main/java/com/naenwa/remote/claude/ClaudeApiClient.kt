package com.naenwa.remote.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Claude API 직접 호출 클라이언트
 * OAuth 토큰을 사용하여 Anthropic API와 통신
 */
class ClaudeApiClient(private val context: Context) {

    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val API_BASE_URL = "https://api.anthropic.com/v1"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        private const val DEFAULT_MAX_TOKENS = 4096

        @Volatile
        private var instance: ClaudeApiClient? = null

        fun getInstance(context: Context): ClaudeApiClient {
            return instance ?: synchronized(this) {
                instance ?: ClaudeApiClient(context.applicationContext).also { instance = it }
            }
        }
    }

    private val tokenManager = ClaudeTokenManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

    /**
     * 로그인 필요 여부 확인
     */
    fun needsLogin(): Boolean {
        return !tokenManager.hasValidToken()
    }

    /**
     * 현재 토큰 정보
     */
    fun getTokenInfo(): TokenInfo? {
        return tokenManager.getTokenInfo()
    }

    /**
     * 토큰 설정 (OAuth 로그인 후 호출)
     */
    fun setTokens(accessToken: String, refreshToken: String?, expiresIn: Long = 28800) {
        tokenManager.saveTokens(accessToken, refreshToken, expiresIn)
    }

    /**
     * 로그아웃
     */
    fun logout() {
        tokenManager.clearTokens()
    }

    /**
     * Claude에게 메시지 전송 (일반 응답)
     */
    suspend fun sendMessage(
        prompt: String,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        systemPrompt: String? = null
    ): ClaudeApiResponse = withContext(Dispatchers.IO) {
        val token = getValidToken()
            ?: return@withContext ClaudeApiResponse(false, null, "로그인이 필요합니다")

        try {
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val requestJson = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("messages", messagesArray)
                if (systemPrompt != null) {
                    put("system", systemPrompt)
                }
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/messages")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Sending request to Claude API...")

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "API Error: ${response.code} - $body")

                    // 401 에러면 토큰 갱신 시도
                    if (response.code == 401) {
                        if (tokenManager.refreshAccessToken()) {
                            // 재시도
                            return@withContext sendMessage(prompt, model, maxTokens, systemPrompt)
                        } else {
                            tokenManager.clearTokens()
                            return@withContext ClaudeApiResponse(false, null, "인증이 만료되었습니다. 다시 로그인해주세요.")
                        }
                    }

                    return@withContext ClaudeApiResponse(false, null, "API 오류: ${response.code}")
                }

                val json = JSONObject(body ?: "{}")
                val content = json.optJSONArray("content")
                val text = content?.optJSONObject(0)?.optString("text", "")

                Log.i(TAG, "Response received: ${text?.take(100)}...")

                ClaudeApiResponse(true, text, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            ClaudeApiResponse(false, null, "요청 실패: ${e.message}")
        }
    }

    /**
     * Claude에게 메시지 전송 (스트리밍)
     */
    fun sendMessageStreaming(
        prompt: String,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        systemPrompt: String? = null
    ): Flow<StreamEvent> = flow {
        val token = getValidToken()
        if (token == null) {
            emit(StreamEvent.Error("로그인이 필요합니다"))
            return@flow
        }

        try {
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val requestJson = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("messages", messagesArray)
                put("stream", true)
                if (systemPrompt != null) {
                    put("system", systemPrompt)
                }
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/messages")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Starting streaming request...")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(StreamEvent.Error("API 오류: ${response.code}"))
                    return@use
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    if (line?.startsWith("data: ") == true) {
                        val data = line!!.removePrefix("data: ").trim()

                        if (data == "[DONE]") {
                            emit(StreamEvent.End)
                            break
                        }

                        try {
                            val event = JSONObject(data)
                            val type = event.optString("type")

                            when (type) {
                                "content_block_delta" -> {
                                    val delta = event.optJSONObject("delta")
                                    val text = delta?.optString("text", "")
                                    if (!text.isNullOrEmpty()) {
                                        emit(StreamEvent.Delta(text))
                                    }
                                }
                                "message_stop" -> {
                                    emit(StreamEvent.End)
                                }
                                "error" -> {
                                    val error = event.optJSONObject("error")
                                    val message = error?.optString("message", "Unknown error")
                                    emit(StreamEvent.Error(message ?: "Unknown error"))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse SSE event: $data", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            emit(StreamEvent.Error("스트리밍 오류: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 유효한 토큰 가져오기 (필요시 갱신)
     */
    private suspend fun getValidToken(): String? {
        val token = tokenManager.getAccessToken()
        if (token != null && !tokenManager.isTokenExpired()) {
            return token
        }

        // 토큰 갱신 시도
        if (tokenManager.refreshAccessToken()) {
            return tokenManager.getAccessToken()
        }

        return null
    }
}

/**
 * API 응답 데이터 클래스
 */
data class ClaudeApiResponse(
    val success: Boolean,
    val result: String?,
    val error: String?
)

/**
 * 스트리밍 이벤트
 */
sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data object End : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * 토큰 정보
 */
data class TokenInfo(
    val hasAccessToken: Boolean,
    val hasRefreshToken: Boolean,
    val expiresAt: Long,
    val isExpired: Boolean
)
