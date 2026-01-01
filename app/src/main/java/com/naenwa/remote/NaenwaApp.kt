package com.naenwa.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class NaenwaApp : Application() {

    companion object {
        const val CHANNEL_ID = "naenwa_floating"
        const val CHANNEL_NAME = "Naenwa Remote"
        const val LOCAL_SERVER_PORT = 8173
        private const val TAG = "NaenwaApp"
    }

    var localServer: LocalAssetServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
     * 로컬 Asset 서버 (COOP/COEP 헤더 포함)
     */
    inner class LocalAssetServer(
        port: Int,
        private val assetManager: android.content.res.AssetManager
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            var uri = session.uri
            if (uri == "/") uri = "/webcontainer_test.html"
            val path = uri.removePrefix("/")

            Log.d(TAG, "Serving: $path")

            return try {
                val inputStream = assetManager.open(path)
                val mimeType = getMimeType(path)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    inputStream.available().toLong()
                )

                // COOP/COEP 헤더 추가 (SharedArrayBuffer 활성화 필수)
                response.addHeader("Cross-Origin-Opener-Policy", "same-origin")
                response.addHeader("Cross-Origin-Embedder-Policy", "require-corp")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "*")

                response
            } catch (e: IOException) {
                Log.e(TAG, "File not found: $path", e)
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not Found: $path"
                )
            }
        }

        private fun getMimeType(path: String): String {
            return when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".wasm") -> "application/wasm"
                else -> "application/octet-stream"
            }
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
