package com.naenwa.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.naenwa.remote.nodejs.NodeJS
import com.naenwa.remote.nodejs.ClaudeBridge
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class NaenwaApp : Application() {

    companion object {
        const val CHANNEL_ID = "naenwa_floating"
        const val CHANNEL_NAME = "Naenwa Remote"
        const val LOCAL_SERVER_PORT = 8173
        private const val TAG = "NaenwaApp"
    }

    var localServer: LocalAssetServer? = null
        private set

    // Node.js 인스턴스
    val nodeJS: NodeJS by lazy { NodeJS.getInstance(this) }
    val claudeBridge: ClaudeBridge by lazy { ClaudeBridge.getInstance(this) }

    override fun onCreate() {
        super.onCreate()

        // Firebase 초기화
        FirebaseApp.initializeApp(this)

        createNotificationChannel()

        // Node.js 브릿지 테스트 시작
        testNodeJsBridge()
    }

    /**
     * Node.js 브릿지 테스트
     */
    private fun testNodeJsBridge() {
        Log.i(TAG, "=== Node.js Bridge Test Started ===")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Initializing Claude Bridge...")
                val initialized = claudeBridge.initialize()
                Log.i(TAG, "Claude Bridge initialized: $initialized")

                if (initialized) {
                    Log.i(TAG, "Testing Claude prompt...")
                    val response = claudeBridge.sendPrompt("Hello, say 'test success' in Korean")
                    Log.i(TAG, "Claude response: ${response.result ?: response.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Node.js Bridge test failed", e)
            }
        }
    }

    /**
     * 로컬 서버 시작 (WebContainers 용)
     */
    fun startLocalServer() {
        if (localServer != null) {
            Log.d(TAG, "Local server already running")
            return
        }
        try {
            localServer = LocalAssetServer(LOCAL_SERVER_PORT, assets)
            localServer?.start()
            Log.d(TAG, "Local server started on port $LOCAL_SERVER_PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start local server", e)
        }
    }

    fun stopLocalServer() {
        localServer?.stop()
        localServer = null
        Log.d(TAG, "Local server stopped")
    }

    /**
     * 로컬 Asset 서버 (COOP/COEP 헤더 포함 + 프록시)
     */
    inner class LocalAssetServer(
        port: Int,
        private val assetManager: android.content.res.AssetManager
    ) : NanoHTTPD(port) {

        private val executor = Executors.newCachedThreadPool()

        // 캐시된 외부 리소스 (메모리 절약을 위해 제한적으로)
        private val resourceCache = mutableMapOf<String, ByteArray>()

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d(TAG, "Request: $method $uri")

            // CORS preflight 처리
            if (method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                    addCrossOriginHeaders(this)
                }
            }

            // 프록시 요청 처리: /proxy?url=https://...
            if (uri.startsWith("/proxy")) {
                val params = session.parameters
                val targetUrl = params["url"]?.firstOrNull()
                if (targetUrl != null) {
                    return proxyRequest(targetUrl, session)  // session 전달로 POST 지원
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing url parameter")
            }

            // 로컬 Asset 제공
            val path = if (uri == "/") "webcontainer_test.html" else uri.removePrefix("/")

            return try {
                val inputStream = assetManager.open(path)
                val bytes = inputStream.readBytes()
                val mimeType = getMimeType(path)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
                addCrossOriginHeaders(response)
                response
            } catch (e: IOException) {
                Log.e(TAG, "File not found: $path", e)
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: $path")
            }
        }

        private fun proxyRequest(targetUrl: String, session: IHTTPSession? = null): Response {
            Log.d(TAG, "Proxying: $targetUrl (method: ${session?.method ?: "GET"})")

            // POST가 아닌 GET 요청만 캐시 확인
            if (session?.method != Method.POST && session?.method != Method.PUT) {
                resourceCache[targetUrl]?.let { cached ->
                    Log.d(TAG, "Cache hit: $targetUrl")
                    val mimeType = getMimeTypeFromUrl(targetUrl)
                    val response = newFixedLengthResponse(
                        Response.Status.OK,
                        mimeType,
                        ByteArrayInputStream(cached),
                        cached.size.toLong()
                    )
                    addCrossOriginHeaders(response)
                    return response
                }
            }

            return try {
                val url = URL(targetUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 60000  // 60초로 증가
                connection.readTimeout = 120000    // 2분으로 증가 (npm install 용)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WebContainer/1.0")

                // POST/PUT 요청 처리
                if (session?.method == Method.POST || session?.method == Method.PUT) {
                    connection.requestMethod = session.method.name
                    connection.doOutput = true

                    // 헤더 복사
                    session.headers?.forEach { (key, value) ->
                        if (!key.equals("host", ignoreCase = true) &&
                            !key.equals("content-length", ignoreCase = true)) {
                            connection.setRequestProperty(key, value)
                        }
                    }

                    // Body 복사
                    val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLength > 0) {
                        val body = ByteArray(contentLength)
                        session.inputStream.read(body)
                        connection.outputStream.use { it.write(body) }
                    }
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Proxy response: $responseCode for $targetUrl")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // Redirect 처리
                    if (responseCode in 300..399) {
                        val redirectUrl = connection.getHeaderField("Location")
                        if (redirectUrl != null) {
                            Log.d(TAG, "Redirect to: $redirectUrl")
                            return proxyRequest(redirectUrl, session)
                        }
                    }

                    // 에러 응답 body도 전달
                    val errorStream = connection.errorStream ?: connection.inputStream
                    val errorBytes = errorStream?.readBytes() ?: ByteArray(0)
                    val errorResponse = newFixedLengthResponse(
                        Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR,
                        connection.contentType ?: "text/plain",
                        ByteArrayInputStream(errorBytes),
                        errorBytes.size.toLong()
                    )
                    addCrossOriginHeaders(errorResponse)
                    return errorResponse
                }

                val bytes = connection.inputStream.readBytes()
                connection.disconnect()

                // GET 요청만 캐시 저장 (10MB 이하)
                if (session?.method != Method.POST && session?.method != Method.PUT && bytes.size < 10 * 1024 * 1024) {
                    resourceCache[targetUrl] = bytes
                }

                val mimeType = connection.contentType ?: getMimeTypeFromUrl(targetUrl)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
                addCrossOriginHeaders(response)
                response
            } catch (e: Exception) {
                Log.e(TAG, "Proxy error: ${e.message}", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy error: ${e.message}")
            }
        }

        private fun addCrossOriginHeaders(response: Response) {
            // SharedArrayBuffer 활성화를 위한 필수 헤더
            response.addHeader("Cross-Origin-Opener-Policy", "same-origin")
            response.addHeader("Cross-Origin-Embedder-Policy", "require-corp")
            response.addHeader("Cross-Origin-Resource-Policy", "cross-origin")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "*")
        }

        private fun getMimeType(path: String): String {
            return when {
                path.endsWith(".html") -> "text/html; charset=utf-8"
                path.endsWith(".js") -> "application/javascript; charset=utf-8"
                path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
                path.endsWith(".css") -> "text/css; charset=utf-8"
                path.endsWith(".json") -> "application/json; charset=utf-8"
                path.endsWith(".wasm") -> "application/wasm"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".svg") -> "image/svg+xml"
                else -> "application/octet-stream"
            }
        }

        private fun getMimeTypeFromUrl(url: String): String {
            val path = URL(url).path
            return getMimeType(path)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Claude Remote Control"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
