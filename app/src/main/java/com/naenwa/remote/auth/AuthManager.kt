package com.naenwa.remote.auth

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.naenwa.remote.model.Device
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    val user_id: String,
    val ip_address: String,
    val port: Int,
    val status: String
)

@Serializable
data class DeviceInsert(
    val device_id: String,
    val user_id: String,
    val device_name: String
)

class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "naenwa_auth"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_ACCESS_TOKEN = "access_token"

        // Supabase config
        private const val SUPABASE_URL = "https://bxrgtfjymzmevcjevvmq.supabase.co"
        private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJ4cmd0Zmp5bXptZXZjamV2dm1xIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjcwMzIyODcsImV4cCI6MjA4MjYwODI4N30.odETz_KzHe1Jib0MtIE9st-yWMsAW2FcOc3ajRA5jWQ"
    }

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val supabase: SupabaseClient = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_KEY
    ) {
        install(Auth)
        install(Postgrest)
    }

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    val isLoggedIn: Boolean
        get() = currentUser != null

    val savedUserId: String?
        get() = prefs.getString(KEY_USER_ID, null)

    val savedUserEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)

    val savedUserName: String?
        get() = prefs.getString(KEY_USER_NAME, null)

    val savedAccessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return withContext(Dispatchers.IO) {
            try {
                // Firebase Auth
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val result = firebaseAuth.signInWithCredential(credential).await()
                val user = result.user ?: throw Exception("Firebase sign-in failed")

                Log.d(TAG, "Firebase sign-in successful: ${user.email}")

                // Supabase Auth with Google ID Token
                try {
                    supabase.auth.signInWith(IDToken) {
                        this.idToken = idToken
                        provider = Google
                    }
                    Log.d(TAG, "Supabase sign-in successful")

                    // Save access token
                    val session = supabase.auth.currentSessionOrNull()
                    session?.accessToken?.let { token ->
                        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Supabase sign-in failed (will use Firebase token): ${e.message}")
                }

                // Save user info
                saveUserInfo(user)

                Result.success(user)
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private fun saveUserInfo(user: FirebaseUser) {
        prefs.edit().apply {
            putString(KEY_USER_ID, user.uid)
            putString(KEY_USER_EMAIL, user.email)
            putString(KEY_USER_NAME, user.displayName)
            apply()
        }
    }

    suspend fun getServerInfo(): ServerInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val userId = currentUser?.uid ?: savedUserId ?: return@withContext null

                val result = supabase.postgrest["servers"]
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("status", "online")
                        }
                    }
                    .decodeSingleOrNull<ServerInfo>()

                Log.d(TAG, "Server info: $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get server info: ${e.message}")
                null
            }
        }
    }

    suspend fun getAccessToken(): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Try Supabase token first
                supabase.auth.currentSessionOrNull()?.accessToken?.let { return@withContext it }

                // Fallback to Firebase token
                currentUser?.getIdToken(false)?.await()?.token
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get access token: ${e.message}")
                savedAccessToken
            }
        }
    }

    /**
     * 사용자의 등록된 기기 목록 조회
     */
    suspend fun getDevices(): List<Device> {
        return withContext(Dispatchers.IO) {
            try {
                val userId = currentUser?.uid ?: savedUserId ?: return@withContext emptyList()

                val result = supabase.postgrest["devices"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<Device>()

                Log.d(TAG, "Found ${result.size} devices")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get devices: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * 기기 등록 (QR 스캔 후)
     */
    suspend fun registerDevice(deviceId: String, deviceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val userId = currentUser?.uid ?: savedUserId
                Log.d(TAG, "Registering device: $deviceId, userId: $userId, name: $deviceName")

                if (userId == null) {
                    Log.e(TAG, "User ID is null, cannot register device")
                    return@withContext false
                }

                // 먼저 기존 기기 확인
                val existing = supabase.postgrest["devices"]
                    .select {
                        filter { eq("device_id", deviceId) }
                    }
                    .decodeSingleOrNull<Device>()

                if (existing != null) {
                    // 이미 존재하면 user_id 업데이트
                    Log.d(TAG, "Device exists, updating user_id")
                    supabase.postgrest["devices"]
                        .update({
                            set("user_id", userId)
                        }) {
                            filter { eq("device_id", deviceId) }
                        }
                } else {
                    // 새로 등록
                    supabase.postgrest["devices"]
                        .insert(DeviceInsert(
                            device_id = deviceId,
                            user_id = userId,
                            device_name = deviceName
                        ))
                }

                Log.d(TAG, "Device registered successfully: $deviceId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register device: ${e.message}", e)
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * 기기 삭제
     */
    suspend fun removeDevice(deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                supabase.postgrest["devices"]
                    .delete {
                        filter {
                            eq("device_id", deviceId)
                        }
                    }
                Log.d(TAG, "Device removed: $deviceId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove device: ${e.message}")
                false
            }
        }
    }

    /**
     * 기기 URL 조회 (실시간)
     */
    suspend fun getDeviceUrl(deviceId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val result = supabase.postgrest["devices"]
                    .select {
                        filter {
                            eq("device_id", deviceId)
                        }
                    }
                    .decodeSingleOrNull<Device>()

                result?.url
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device URL: ${e.message}")
                null
            }
        }
    }

    fun signOut() {
        firebaseAuth.signOut()
        prefs.edit().clear().apply()
    }
}
