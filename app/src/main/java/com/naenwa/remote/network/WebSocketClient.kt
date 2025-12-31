package com.naenwa.remote.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*

class WebSocketClient(val serverUrl: String) {

    companion object {
        private const val TAG = "WebSocketClient"

        // 싱글톤 OkHttpClient (커넥션 풀 재사용)
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(java.time.Duration.ofSeconds(15))  // LTE용 더 짧은 ping
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .readTimeout(java.time.Duration.ofSeconds(0))  // WebSocket은 무제한
                .writeTimeout(java.time.Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .build()
        }

        // 싱글톤 Gson
        private val sharedGson: Gson by lazy { Gson() }
    }

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private val maxReconnectAttempts = 5
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 시그널링 클라이언트 (WebRTC용)
    private var _signalingClient: WebSocketSignalingClient? = null
    val signalingClient: WebSocketSignalingClient?
        get() = _signalingClient

    private val _messages = MutableSharedFlow<ServerMessage>()
    val messages: SharedFlow<ServerMessage> = _messages

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    sealed class ServerMessage {
        data class System(val message: String) : ServerMessage()
        data class ClaudeOutput(val text: String) : ServerMessage()
        data class ToolUse(val tool: String, val message: String) : ServerMessage()  // Tool 사용 진행 상황
        data class BuildStatus(val status: String) : ServerMessage()
        data class BuildReady(val apkUrl: String, val apkSize: Long) : ServerMessage()  // APK 다운로드 준비됨
        data class BuildLog(val text: String) : ServerMessage()
        data class GitStatus(val status: String, val message: String) : ServerMessage()  // Git 작업 상태
        data class Error(val message: String) : ServerMessage()
        data class Connected(val sessionId: String) : ServerMessage()
        data class ClaudeSessionId(val claudeSessionId: String) : ServerMessage()  // Claude CLI 세션 ID
        data class ProjectPath(val path: String) : ServerMessage()  // 서버 프로젝트 경로
    }

    fun connect() {
        shouldReconnect = true  // 연결 시도 시 재연결 활성화
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
        }

        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws"

        Log.d(TAG, "Connecting to: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                reconnectAttempts = 0  // 연결 성공 시 카운터 리셋
                // 시그널링 클라이언트 초기화
                _signalingClient = WebSocketSignalingClient(webSocket, sharedGson)
                scope.launch {
                    _connectionState.emit(ConnectionState.Connected)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                scope.launch {
                    try {
                        // JSON 한 번만 파싱 (이중 파싱 제거)
                        val json = sharedGson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString

                        // WebRTC 시그널링 메시지 처리
                        if (type?.startsWith("webrtc_") == true) {
                            _signalingClient?.parseSignalingMessage(json)
                            return@launch
                        }

                        // 일반 메시지 처리 (파싱된 json 재사용)
                        parseMessage(json)?.let { _messages.emit(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Message parse error: ${e.message}")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                scope.launch {
                    _connectionState.emit(ConnectionState.Error(t.message ?: "Unknown error"))
                    attemptReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected)
                    // 정상 종료(1000)가 아니면 재연결 시도
                    if (code != 1000) {
                        attemptReconnect()
                    }
                }
            }
        })
    }

    private fun parseMessage(json: JsonObject): ServerMessage? {
        return try {
            when (json.get("type")?.asString) {
                "connected" -> ServerMessage.Connected(json.get("session_id")?.asString ?: "")
                "system" -> ServerMessage.System(json.get("message")?.asString ?: "")
                "claude_output" -> ServerMessage.ClaudeOutput(json.get("text")?.asString ?: "")
                "tool_use" -> ServerMessage.ToolUse(
                    json.get("tool")?.asString ?: "",
                    json.get("message")?.asString ?: ""
                )
                "build" -> {
                    val status = json.get("status")?.asString ?: ""
                    if (status == "ready") {
                        // APK 다운로드 준비됨
                        val apkUrl = json.get("apk_url")?.asString ?: ""
                        val apkSize = json.get("apk_size")?.asLong ?: 0L
                        ServerMessage.BuildReady(apkUrl, apkSize)
                    } else {
                        ServerMessage.BuildStatus(status)
                    }
                }
                "build_log" -> ServerMessage.BuildLog(json.get("text")?.asString ?: "")
                "git" -> ServerMessage.GitStatus(
                    json.get("status")?.asString ?: "",
                    json.get("message")?.asString ?: ""
                )
                "error" -> ServerMessage.Error(json.get("message")?.asString ?: "")
                "session_id" -> {
                    val claudeSessionId = json.get("claude_session_id")?.asString ?: ""
                    if (claudeSessionId.isNotEmpty()) {
                        ServerMessage.ClaudeSessionId(claudeSessionId)
                    } else null
                }
                "project_path" -> {
                    val path = json.get("path")?.asString ?: ""
                    if (path.isNotEmpty()) {
                        ServerMessage.ProjectPath(path)
                    } else null
                }
                "ping" -> null  // keepalive ping 무시
                "input_sent" -> null  // echo 무시
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }

    fun startClaude(projectPath: String? = null, sessionId: String? = null) {
        val data = JsonObject().apply {
            addProperty("action", "start_claude")
            projectPath?.let { addProperty("project_path", it) }
            sessionId?.let { addProperty("session_id", it) }
        }
        send(data)
    }

    fun resumeSession(sessionId: String, claudeSessionId: String? = null) {
        val data = JsonObject().apply {
            addProperty("action", "resume_session")
            addProperty("session_id", sessionId)
            claudeSessionId?.let { addProperty("claude_session_id", it) }
        }
        send(data)
    }

    fun sendText(text: String) {
        val data = JsonObject().apply {
            addProperty("action", "send_text")
            addProperty("text", text)
        }
        send(data)
    }

    fun sendImage(imageBytes: ByteArray, prompt: String) {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val data = JsonObject().apply {
            addProperty("action", "send_image")
            addProperty("image", base64)
            addProperty("prompt", prompt)
        }
        send(data)
    }

    fun requestBuild() {
        val data = JsonObject().apply {
            addProperty("action", "build")
        }
        send(data)
    }

    fun requestGitPush(commitMessage: String = "Update from Naenwa") {
        val data = JsonObject().apply {
            addProperty("action", "git_push")
            addProperty("message", commitMessage)
        }
        send(data)
    }

    fun requestGitClone(repoUrl: String, repoName: String, accessToken: String) {
        val data = JsonObject().apply {
            addProperty("action", "git_clone")
            addProperty("repo_url", repoUrl)
            addProperty("repo_name", repoName)
            addProperty("access_token", accessToken)
        }
        send(data)
    }

    fun requestGitInit(repoUrl: String, repoName: String, accessToken: String) {
        val data = JsonObject().apply {
            addProperty("action", "git_init")
            addProperty("repo_url", repoUrl)
            addProperty("repo_name", repoName)
            addProperty("access_token", accessToken)
        }
        send(data)
    }

    private fun send(data: JsonObject) {
        val json = sharedGson.toJson(data)
        Log.d(TAG, "Sending: $json")
        val result = webSocket?.send(json)
        Log.d(TAG, "Send result: $result, webSocket null: ${webSocket == null}")
        if (result != true) {
            Log.e(TAG, "Failed to send message! WebSocket might be disconnected.")
            scope.launch {
                _connectionState.emit(ConnectionState.Error("Send failed - reconnecting..."))
                attemptReconnect()
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false  // 수동 종료 시 재연결 방지
        _signalingClient = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        scope.cancel()
    }

    /**
     * Exponential backoff 재연결 (1s, 2s, 4s, 8s, 16s)
     */
    private suspend fun attemptReconnect() {
        if (!shouldReconnect || reconnectAttempts >= maxReconnectAttempts) {
            Log.d(TAG, "Reconnect stopped: shouldReconnect=$shouldReconnect, attempts=$reconnectAttempts")
            return
        }

        reconnectAttempts++
        val delayMs = (1000L * (1 shl (reconnectAttempts - 1))).coerceAtMost(16000L)
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$maxReconnectAttempts)")

        kotlinx.coroutines.delay(delayMs)

        if (shouldReconnect) {
            connect()
        }
    }

    /**
     * WebRTC 연결 시작 요청
     */
    fun requestWebRTCConnection() {
        val data = JsonObject().apply {
            addProperty("action", "webrtc_connect")
        }
        send(data)
    }
}
