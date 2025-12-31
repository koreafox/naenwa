package com.naenwa.remote.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.naenwa.remote.MainActivity
import com.naenwa.remote.NaenwaApp
import com.naenwa.remote.R
import com.naenwa.remote.network.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream

class FloatingService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CLAUDE_SESSION_ID = "claude_session_id"
        const val ACTION_UPDATE_SESSION = "update_session"
        private const val TAG = "FloatingService"

        // 싱글톤 인스턴스 (Activity에서 접근용)
        var instance: FloatingService? = null
            private set

        // WebSocket 클라이언트 (서비스에서 관리)
        var webSocketClient: WebSocketClient? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var expandedMenu: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 현재 서버 URL과 세션 ID
    private var serverUrl: String? = null
    private var currentSessionId: String? = null
    private var currentClaudeSessionId: String? = null

    // 연결 상태 콜백
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onMessageReceived: ((WebSocketClient.ServerMessage) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        // 세션 업데이트 액션 처리
        if (intent?.action == ACTION_UPDATE_SESSION) {
            intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
                currentSessionId = sessionId
                Log.d(TAG, "Session updated: $sessionId")
            }
            return START_STICKY
        }

        // MediaProjection 초기화
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        }

        // 서버 URL 및 세션 ID 가져오기
        intent?.getStringExtra(EXTRA_SERVER_URL)?.let { url ->
            serverUrl = url
        }
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
            currentSessionId = sessionId
        }
        intent?.getStringExtra(EXTRA_CLAUDE_SESSION_ID)?.let { claudeSessionId ->
            currentClaudeSessionId = claudeSessionId
        }

        // 서버 연결 (URL이 있는 경우)
        serverUrl?.let { url ->
            if (url.isNotEmpty()) {
                connectToServer(url)
            }
        }

        createFloatingButton()
        // SpeechRecognizer는 첫 사용 시 lazy init (배터리 절약)

        return START_STICKY
    }

    fun connectToServer(url: String) {
        // 기존 연결 해제
        webSocketClient?.disconnect()

        serverUrl = url
        Log.d(TAG, "Connecting to server: $url, session: $currentSessionId")

        webSocketClient = WebSocketClient(url).also { client ->
            // 연결 상태 관찰
            serviceScope.launch {
                client.connectionState.collectLatest { state ->
                    val isConnected = state is WebSocketClient.ConnectionState.Connected
                    handler.post {
                        onConnectionStateChanged?.invoke(isConnected)
                        updateNotification(isConnected)
                    }

                    // 연결 성공 시 세션 복원
                    if (isConnected && currentSessionId != null) {
                        Log.d(TAG, "Resuming session: $currentSessionId, Claude: $currentClaudeSessionId")
                        client.resumeSession(currentSessionId!!, currentClaudeSessionId)
                    }
                }
            }

            // 메시지 수신 관찰
            serviceScope.launch {
                client.messages.collectLatest { message ->
                    handler.post {
                        onMessageReceived?.invoke(message)
                    }
                }
            }

            client.connect()
        }
    }

    fun disconnect() {
        webSocketClient?.disconnect()
        webSocketClient = null
        updateNotification(false)
    }

    fun updateSessionId(sessionId: String?, claudeSessionId: String? = null) {
        currentSessionId = sessionId
        claudeSessionId?.let { currentClaudeSessionId = it }
        Log.d(TAG, "Session ID updated: $sessionId, Claude: $claudeSessionId")
    }

    private fun updateNotification(connected: Boolean) {
        val notification = createNotification(connected)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(1, notification)
    }

    private fun createNotification(connected: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (connected) "서버 연결됨 - 백그라운드 실행 중" else "플로팅 버튼 활성화됨"

        return NotificationCompat.Builder(this, NaenwaApp.CHANNEL_ID)
            .setContentTitle("Naenwa Remote")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams")
    private fun createFloatingButton() {
        // 메인 플로팅 버튼
        floatingView = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setBackgroundResource(android.R.drawable.btn_default)
            minimumWidth = 120
            minimumHeight = 120
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 200
        }

        windowManager.addView(floatingView, params)

        // 터치 리스너 (드래그 + 클릭)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (initialTouchY - event.rawY).toInt()

                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                    }

                    params.x = initialX + dx
                    params.y = initialY - dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMenu() {
        if (expandedMenu == null) {
            showMenu()
        } else {
            hideMenu()
        }
    }

    @SuppressLint("InflateParams")
    private fun showMenu() {
        expandedMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE333333.toInt())
            setPadding(20, 20, 20, 20)

            // 화면 캡처 버튼
            addView(createMenuButton("캡처") {
                hideMenu()
                captureScreen()
            })

            // 음성 입력 버튼
            addView(createMenuButton("음성") {
                hideMenu()
                startVoiceInput()
            })

            // 빌드 버튼
            addView(createMenuButton("빌드") {
                hideMenu()
                webSocketClient?.requestBuild()
                showToast("빌드 요청됨")
            })

            // 닫기 버튼
            addView(createMenuButton("닫기") {
                hideMenu()
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 400
        }

        windowManager.addView(expandedMenu, params)
    }

    private fun createMenuButton(text: String, onClick: () -> Unit): android.widget.Button {
        return android.widget.Button(this).apply {
            this.text = text
            textSize = 14f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10
            }
        }
    }

    private fun hideMenu() {
        expandedMenu?.let {
            windowManager.removeView(it)
            expandedMenu = null
        }
    }

    private fun captureScreen() {
        if (mediaProjection == null) {
            showToast("화면 캡처 권한이 없습니다")
            return
        }

        val metrics = Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // 잠시 후 이미지 캡처
        handler.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to actual screen size
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()

                // JPEG 75%로 압축 (PNG 대비 50-70% 용량 절감)
                val stream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                val bytes = stream.toByteArray()
                croppedBitmap.recycle()
                stream.close()  // 스트림 명시적 종료

                image.close()

                // 서버로 전송
                webSocketClient?.sendImage(bytes, "이 화면을 보고 필요한 수정사항을 알려주세요")
                showToast("화면 캡처 전송됨")

                Log.d(TAG, "Screenshot captured and sent: ${bytes.size} bytes")
            } else {
                showToast("캡처 실패")
            }

            // 정리
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }, 100)  // 500ms→100ms로 단축 (VirtualDisplay 안정화 최소 시간)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    showToast("말씀하세요...")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "인식 실패"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "시간 초과"
                        else -> "오류: $error"
                    }
                    showToast(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        showToast("인식됨: $text")
                        webSocketClient?.sendText(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startVoiceInput() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            return
        }

        // Lazy init: 첫 사용 시에만 초기화
        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        isListening = true
        speechRecognizer?.startListening(intent)
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        webSocketClient?.disconnect()
        webSocketClient = null
        floatingView?.let { windowManager.removeView(it) }
        expandedMenu?.let { windowManager.removeView(it) }
        speechRecognizer?.destroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
