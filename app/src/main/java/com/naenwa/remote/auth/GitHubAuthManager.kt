package com.naenwa.remote.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GitHubAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GitHubAuthManager"
        private const val PREFS_NAME = "github_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USERNAME = "username"

        // GitHub OAuth App 설정
        const val CLIENT_ID = "Ov23liaMT6luHYenfZ46"
        private const val CLIENT_SECRET = "c4e7154c55b3bb334706a85340cdb549a68a2574"
        const val REDIRECT_URI = "naenwa://callback"
        const val AUTH_URL = "https://github.com/login/oauth/authorize"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val API_URL = "https://api.github.com"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    val isLoggedIn: Boolean
        get() = accessToken != null

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(value) {
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        private set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    fun getAuthUrl(): String {
        return "$AUTH_URL?client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&scope=repo,user"
    }

    suspend fun exchangeCodeForToken(code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            Log.d(TAG, "Token response: $body")

            val json = JSONObject(body)
            val token = json.optString("access_token", "")

            if (token.isNotEmpty()) {
                accessToken = token
                // 사용자 정보 가져오기
                fetchUserInfo()
                Result.success(token)
            } else {
                val error = json.optString("error_description", "Failed to get token")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchUserInfo() = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_URL/user")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)

            username = json.optString("login", "")
            Log.d(TAG, "GitHub user: $username")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch user info", e)
        }
    }

    suspend fun getRepositories(): List<Repository> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_URL/user/repos?sort=updated&per_page=50")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "[]"
            val jsonArray = JSONArray(body)

            val repos = mutableListOf<Repository>()
            for (i in 0 until jsonArray.length()) {
                val repo = jsonArray.getJSONObject(i)
                repos.add(Repository(
                    name = repo.getString("name"),
                    fullName = repo.getString("full_name"),
                    description = repo.optString("description", ""),
                    cloneUrl = repo.getString("clone_url"),
                    isPrivate = repo.getBoolean("private"),
                    updatedAt = repo.getString("updated_at")
                ))
            }
            repos
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch repositories", e)
            emptyList()
        }
    }

    suspend fun createRepository(name: String, description: String = "", isPrivate: Boolean = false): Result<Repository> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("name", name)
                put("description", description)
                put("private", isPrivate)
                put("auto_init", true)
            }

            val request = Request.Builder()
                .url("$API_URL/user/repos")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val repo = JSONObject(body)
                Result.success(Repository(
                    name = repo.getString("name"),
                    fullName = repo.getString("full_name"),
                    description = repo.optString("description", ""),
                    cloneUrl = repo.getString("clone_url"),
                    isPrivate = repo.getBoolean("private"),
                    updatedAt = repo.getString("updated_at")
                ))
            } else {
                val error = JSONObject(body).optString("message", "Failed to create repository")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create repository", e)
            Result.failure(e)
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    data class Repository(
        val name: String,
        val fullName: String,
        val description: String,
        val cloneUrl: String,
        val isPrivate: Boolean,
        val updatedAt: String
    )
}
