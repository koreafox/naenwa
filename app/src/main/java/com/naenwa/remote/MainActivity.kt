package com.naenwa.remote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.naenwa.remote.adapter.ChatDisplayMessage
import com.naenwa.remote.adapter.ChatMessageAdapter
import com.naenwa.remote.adapter.SessionAdapter
import com.naenwa.remote.auth.AuthManager
import com.naenwa.remote.auth.ClaudeOAuth
import com.naenwa.remote.auth.GitHubAuthManager
import com.naenwa.remote.nodejs.ClaudeBridge
import com.naenwa.remote.nodejs.StreamEvent
import com.naenwa.remote.data.AppDatabase
import com.naenwa.remote.data.ChatMessage
import com.naenwa.remote.data.ChatSession
import com.naenwa.remote.data.MessageType
import com.naenwa.remote.databinding.ActivityMainBinding
import com.naenwa.remote.network.ApkInstaller
import com.naenwa.remote.network.WebSocketClient
import com.naenwa.remote.service.FloatingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val WEB_CLIENT_ID = "939750314714-qp4q05ablp3milag2fhp0ivktaiusade.apps.googleusercontent.com"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var apkInstaller: ApkInstaller
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var authManager: AuthManager
    private lateinit var gitHubAuthManager: GitHubAuthManager
    private lateinit var googleSignInClient: GoogleSignInClient

    private val database by lazy { AppDatabase.getDatabase(this) }
    private val chatDao by lazy { database.chatDao() }

    private var mediaProjectionIntent: Intent? = null

    // 현재 세션 ID (로컬 DB용)
    private var currentSessionId: Long = -1
    // 서버 세션 ID (WebSocket용)
    private var serverSessionId: String? = null
    // Claude CLI 세션 ID (대화 재개용)
    private var currentClaudeSessionId: String? = null
    // 현재 세션 타이틀
    private var currentSessionTitle: String = "새 대화"

    // 서버 URL
    private var serverUrl: String = ""

    // FloatingService 없이 직접 연결한 경우의 클라이언트
    private var directWebSocketClient: WebSocketClient? = null

    // Claude Bridge (nodejs-mobile 기반)
    private val claudeBridge by lazy { ClaudeBridge.getInstance(this) }
    private val claudeOAuth by lazy { ClaudeOAuth(this) }
    private var useCLIMode = false  // CLI 모드 사용 여부

    // Google Sign-In launcher
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

    // QR Scanner launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("server_url")?.let { url ->
                serverUrl = url
                saveServerUrl(url)
                appendLog("QR 스캔 완료: $url")
                connectToServer(url)
            }
        }
    }

    // 채팅 메시지 리스트
    private val chatMessages = mutableListOf<ChatDisplayMessage>()

    // 스트리밍 텍스트 배치 처리 (동적 간격 조절)
    private val streamBuffer = StringBuilder()
    private val streamHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var streamPending = false
    private var currentStreamingMessageId: Long = -1
    private var streamFlushDelayMs = 50L  // 기본 50ms, 동적으로 조절
    private var lastFlushTime = 0L
    private var streamBytesPerSecond = 0

    // 사용자가 맨 아래에 있는지 추적 (자동 스크롤 결정용)
    private var isUserAtBottom = true

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionIntent = result.data
            startFloatingService()
        } else {
            Toast.makeText(this, "화면 캡처 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "음성 인식을 위해 마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "알림 표시를 위해 알림 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    // 저장소 권한 launcher
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "Storage permission granted")
        } else {
            Toast.makeText(this, "터미널 모드를 위해 저장소 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 11+ 모든 파일 접근 권한 launcher
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                Log.i(TAG, "All files access granted")
            } else {
                Toast.makeText(this, "파일 접근 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkInstaller = ApkInstaller(this)
        authManager = AuthManager(this)
        gitHubAuthManager = GitHubAuthManager(this)
        setupGoogleSignIn()

        setupUI()
        setupChatList()
        setupSessionList()
        setupDrawerInsets()
        requestPermissions()
        observeSessions()

        // 로그인 상태 확인 후 자동 연결
        checkAuthAndConnect()
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupDrawerInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            binding.drawerHeader.setPadding(
                binding.drawerHeader.paddingLeft,
                insets.top + resources.getDimensionPixelSize(R.dimen.drawer_header_padding_top),
                binding.drawerHeader.paddingRight,
                binding.drawerHeader.paddingBottom
            )
            windowInsets
        }
        // 인셋 요청
        androidx.core.view.ViewCompat.requestApplyInsets(binding.navView)
    }

    private fun checkAuthAndConnect() {
        // 1. Google 로그인 확인
        if (!authManager.isLoggedIn) {
            appendLog("로그인이 필요합니다. 터치하여 로그인하세요.")
            binding.tvStatus.text = "로그인 필요"
            return
        }

        // 2. GitHub 연결 확인
        if (!gitHubAuthManager.isLoggedIn) {
            appendLog("GitHub 연결이 필요합니다.")
            binding.tvStatus.text = "GitHub 연결 필요"
            startActivity(Intent(this, GitHubLoginActivity::class.java))
            return
        }

        // 3. 프로젝트 선택 확인
        val selectedRepo = getSharedPreferences("naenwa", MODE_PRIVATE)
            .getString("selected_repo_name", null)
        if (selectedRepo == null) {
            appendLog("프로젝트를 선택해주세요.")
            binding.tvStatus.text = "프로젝트 선택 필요"
            startActivity(Intent(this, ProjectSelectActivity::class.java))
            return
        }

        // 모든 인증 완료 - 연결 시작
        appendLog("로그인됨: ${authManager.currentUser?.email}")
        appendLog("GitHub: ${gitHubAuthManager.username}")
        appendLog("프로젝트: $selectedRepo")
        fetchDevicesAndConnect()
    }

    private fun startGoogleSignIn() {
        appendLog("Google 로그인 중...")
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            signInLauncher.launch(signInIntent)
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken

            if (idToken != null) {
                lifecycleScope.launch {
                    appendLog("인증 중...")
                    val result = authManager.signInWithGoogle(idToken)
                    result.fold(
                        onSuccess = { user ->
                            appendLog("로그인 성공: ${user.email}")
                            // GitHub 로그인으로 이동
                            checkAuthAndConnect()
                        },
                        onFailure = { e ->
                            appendLog("[오류] 로그인 실패: ${e.message}")
                        }
                    )
                }
            } else {
                appendLog("[오류] ID 토큰을 가져올 수 없습니다")
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google sign-in failed: ${e.statusCode}", e)
            appendLog("[오류] Google 로그인 실패: ${e.message}")
        }
    }

    private fun fetchDevicesAndConnect() {
        lifecycleScope.launch {
            appendLog("기기 목록 조회 중...")
            val devices = authManager.getDevices()

            if (devices.isEmpty()) {
                appendLog("등록된 기기가 없습니다. 설정에서 기기를 추가하세요.")
                binding.tvStatus.text = "기기 없음"
                return@launch
            }

            // 온라인 기기 찾기
            val onlineDevice = devices.find { it.isOnline }
            if (onlineDevice != null && onlineDevice.url != null) {
                appendLog("온라인 기기 발견: ${onlineDevice.deviceName}")
                serverUrl = onlineDevice.url
                saveServerUrl(serverUrl)
                connectToServer(serverUrl)
            } else {
                appendLog("온라인 기기가 없습니다. (${devices.size}개 등록됨)")
                binding.tvStatus.text = "기기 오프라인"

                // 저장된 URL이 있으면 시도
                val savedUrl = getSharedPreferences("naenwa", Context.MODE_PRIVATE)
                    .getString("server_url", null)
                if (!savedUrl.isNullOrEmpty()) {
                    serverUrl = savedUrl
                    appendLog("저장된 URL로 연결 시도: $serverUrl")
                    connectToServer(serverUrl)
                }
            }
        }
    }

    private fun setupChatList() {
        chatAdapter = ChatMessageAdapter()
        val linearLayoutManager = LinearLayoutManager(this@MainActivity).apply {
            stackFromEnd = true  // 새 메시지가 아래에 추가되면 자동 스크롤
        }
        binding.rvChatMessages.apply {
            layoutManager = linearLayoutManager
            adapter = chatAdapter

            // 스크롤 위치 추적 - 사용자가 맨 아래에 있는지 감지
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    // 스크롤이 멈췄을 때만 상태 확인 (레이아웃 변경 중 잘못된 값 방지)
                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                        // canScrollVertically(1): 아래로 더 스크롤 가능하면 true
                        isUserAtBottom = !recyclerView.canScrollVertically(1)
                    }
                }
            })
        }
    }

    private fun saveServerUrl(url: String) {
        getSharedPreferences("naenwa", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", url)
            .apply()
    }

    private fun setupUI() {
        // 콘솔 헤더 버튼 설정
        setupConsoleHeaderButtons()

        // 세션 타이틀 바 설정
        setupSessionTitleBar()

        // 새 세션 버튼
        binding.btnNewSession.setOnClickListener {
            createNewSession()
        }

        // 설정 버튼 (드로어)
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etInput.text?.clear()
                showLoading(true)

                // 세션이 없으면 먼저 생성 후 메시지 전송
                lifecycleScope.launch {
                    // 세션이 없으면 생성
                    if (currentSessionId <= 0) {
                        val title = generateSessionTitle(text)
                        val session = ChatSession(
                            title = title,
                            serverUrl = serverUrl
                        )
                        currentSessionId = withContext(Dispatchers.IO) {
                            chatDao.insertSession(session)
                        }
                        sessionAdapter.setSelectedSession(currentSessionId)

                        // 타이틀 바 표시
                        updateSessionTitle(title)
                        showSessionTitleBar(true)
                    }

                    // UI에 사용자 메시지 추가 (사용자가 보낸 메시지이므로 항상 스크롤)
                    isUserAtBottom = true
                    appendLog(text, type = MessageType.USER_INPUT)

                    // DB에 저장
                    saveMessageInternal(text, MessageType.USER_INPUT)

                    // CLI 모드 또는 서버 모드로 전송
                    if (useCLIMode) {
                        sendToCLI(text)
                    } else {
                        val client = FloatingService.webSocketClient ?: directWebSocketClient
                        client?.sendText(text)
                    }
                }
            }
        }

        binding.btnClearLog.setOnClickListener {
            chatMessages.clear()
            chatAdapter.submitList(emptyList())
        }
    }

    // 전체 세션 목록 (검색 필터링용)
    private var allSessions: List<ChatSession> = emptyList()
    private var currentSearchQuery: String = ""

    private fun setupSessionList() {
        sessionAdapter = SessionAdapter(
            onSessionClick = { session ->
                loadSession(session)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            },
            onDeleteClick = { session ->
                showDeleteConfirmDialog(session)
            }
        )

        binding.rvSessions.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sessionAdapter
        }

        // 검색 기능 설정
        setupSearch()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                filterSessions()
            }
        })
    }

    private fun filterSessions() {
        val filtered = if (currentSearchQuery.isEmpty()) {
            allSessions
        } else {
            allSessions.filter { session ->
                session.title.contains(currentSearchQuery, ignoreCase = true)
            }
        }
        sessionAdapter.submitList(filtered)
        updateEmptyState(filtered.size, currentSearchQuery.isNotEmpty())
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            chatDao.getAllSessions().collectLatest { sessions ->
                allSessions = sessions
                filterSessions()
                updateSessionInfo(sessions.size)
            }
        }
    }

    private fun updateSessionInfo(count: Int) {
        binding.tvSessionCount.text = if (count == 0) {
            "대화 없음"
        } else {
            "${count}개의 대화"
        }

        binding.tvSessionInfo.text = if (count == 0) {
            "새 대화를 시작하세요"
        } else {
            "준비됨"
        }
    }

    private fun updateEmptyState(visibleCount: Int, isSearching: Boolean) {
        val isEmpty = visibleCount == 0
        binding.layoutEmptyState.visibility = if (isEmpty) android.view.View.VISIBLE else android.view.View.GONE
        binding.rvSessions.visibility = if (isEmpty) android.view.View.GONE else android.view.View.VISIBLE

        // 검색 중일 때와 아닐 때 다른 메시지
        if (isEmpty) {
            if (isSearching) {
                binding.tvEmptyTitle.text = "검색 결과 없음"
                binding.tvEmptySubtitle.text = "다른 검색어를 시도해 보세요"
            } else {
                binding.tvEmptyTitle.text = "대화가 없습니다"
                binding.tvEmptySubtitle.text = "새 대화를 시작해 보세요"
            }
        }
    }

    private fun createNewSession() {
        lifecycleScope.launch {
            // 현재 세션 저장
            saveCurrentSession()

            // 새 세션 생성
            val session = ChatSession(
                title = "새 대화",
                serverUrl = serverUrl
            )
            val newSessionId = withContext(Dispatchers.IO) {
                chatDao.insertSession(session)
            }

            // 새 세션으로 전환
            currentSessionId = newSessionId
            currentClaudeSessionId = null  // 새 대화이므로 Claude 세션 ID 초기화
            sessionAdapter.setSelectedSession(newSessionId)

            // 타이틀 바 표시
            updateSessionTitle("새 대화")
            showSessionTitleBar(true)

            // 채팅 UI 초기화
            chatMessages.clear()
            chatAdapter.submitList(emptyList())
            binding.drawerLayout.closeDrawer(GravityCompat.START)

            Toast.makeText(this@MainActivity, "새 대화가 시작되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSession(session: ChatSession) {
        lifecycleScope.launch {
            // 현재 세션 저장
            saveCurrentSession()

            // 새 세션으로 전환
            currentSessionId = session.id
            currentClaudeSessionId = session.claudeSessionId
            sessionAdapter.setSelectedSession(session.id)

            // 타이틀 바 표시
            updateSessionTitle(session.title)
            showSessionTitleBar(true)

            // 서버 URL 설정
            serverUrl = session.serverUrl

            // 메시지 불러오기
            val messages = withContext(Dispatchers.IO) {
                chatDao.getMessagesBySessionOnce(session.id)
            }

            // 채팅 UI 초기화 및 메시지 표시
            chatMessages.clear()
            messages.forEach { dbMessage ->
                val displayMessage = ChatDisplayMessage(
                    id = dbMessage.timestamp,
                    type = dbMessage.type,
                    content = dbMessage.content
                )
                chatMessages.add(displayMessage)
            }
            val scrollPosition = chatMessages.size - 1
            isUserAtBottom = true  // 세션 로드 시 맨 아래로 스크롤
            chatAdapter.submitList(chatMessages.toList()) {
                if (chatMessages.isNotEmpty()) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.smoothScrollToPosition(scrollPosition)
                    }
                }
            }

            // 서버에 세션 재개 요청 (연결된 경우)
            val client = FloatingService.webSocketClient ?: directWebSocketClient
            if (client != null && session.claudeSessionId != null) {
                client.resumeSession(session.id.toString(), session.claudeSessionId)
                appendLog("[세션] 이전 대화 이어가기 준비됨")
            }
        }
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "QR 코드로 기기 추가",
            "서버 URL 직접 입력",
            "로그아웃"
        )

        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startQrScanner()
                    1 -> showServerUrlDialog()
                    2 -> signOut()
                }
            }
            .show()
    }

    private fun startQrScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        qrScannerLauncher.launch(intent)
    }

    private fun showServerUrlDialog() {
        val input = android.widget.EditText(this)
        input.hint = "ws://192.168.x.x:3030"
        input.setText(serverUrl)
        input.setPadding(50, 30, 50, 30)

        AlertDialog.Builder(this)
            .setTitle("서버 URL 입력")
            .setView(input)
            .setPositiveButton("연결") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    serverUrl = url
                    saveServerUrl(url)
                    connectToServer(url)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun signOut() {
        lifecycleScope.launch {
            authManager.signOut()
            googleSignInClient.signOut()
            disconnect()
            appendLog("로그아웃 되었습니다.")
            binding.tvStatus.text = "로그인 필요"
        }
    }

    private fun showDeleteConfirmDialog(session: ChatSession) {
        AlertDialog.Builder(this)
            .setTitle("세션 삭제")
            .setMessage("'${session.title}' 세션을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                deleteSession(session)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteSession(session: ChatSession) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                chatDao.deleteSession(session)
            }

            // 현재 세션이 삭제된 경우
            if (currentSessionId == session.id) {
                currentSessionId = -1
                chatMessages.clear()
                chatAdapter.submitList(emptyList())
            }

            Toast.makeText(this@MainActivity, "세션이 삭제되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentSession() {
        if (currentSessionId <= 0) return

        lifecycleScope.launch(Dispatchers.IO) {
            chatDao.updateSessionTimestamp(currentSessionId)
        }
    }

    private fun saveMessage(content: String, type: MessageType) {
        if (currentSessionId <= 0) {
            // 세션이 없으면 자동 생성
            lifecycleScope.launch {
                val session = ChatSession(
                    title = generateSessionTitle(content),
                    serverUrl = serverUrl
                )
                currentSessionId = withContext(Dispatchers.IO) {
                    chatDao.insertSession(session)
                }
                sessionAdapter.setSelectedSession(currentSessionId)

                // 메시지 저장
                saveMessageInternal(content, type)
            }
        } else {
            saveMessageInternal(content, type)
        }
    }

    private fun saveMessageInternal(content: String, type: MessageType) {
        lifecycleScope.launch(Dispatchers.IO) {
            val message = ChatMessage(
                sessionId = currentSessionId,
                type = type,
                content = content
            )
            chatDao.insertMessage(message)
            chatDao.updateSessionTimestamp(currentSessionId)
        }
    }

    private fun generateSessionTitle(firstMessage: String): String {
        return if (firstMessage.length > 20) {
            firstMessage.take(20) + "..."
        } else {
            firstMessage
        }
    }

    // ===== 세션 타이틀 바 =====
    private fun setupSessionTitleBar() {
        binding.btnEditTitle.setOnClickListener {
            showEditTitleDialog()
        }

        // 타이틀 텍스트 자체도 클릭 가능
        binding.tvSessionTitle.setOnClickListener {
            showEditTitleDialog()
        }
    }

    private fun showSessionTitleBar(show: Boolean) {
        runOnUiThread {
            if (show) {
                binding.sessionTitleBar.visibility = android.view.View.VISIBLE
                binding.titleDivider.visibility = android.view.View.VISIBLE

                // 페이드인 애니메이션
                binding.sessionTitleBar.alpha = 0f
                binding.sessionTitleBar.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            } else {
                binding.sessionTitleBar.visibility = android.view.View.GONE
                binding.titleDivider.visibility = android.view.View.GONE
            }
        }
    }

    private fun updateSessionTitle(title: String) {
        currentSessionTitle = title
        runOnUiThread {
            binding.tvSessionTitle.text = title
        }
    }

    private fun showEditTitleDialog() {
        // Material 3 스타일의 컨테이너
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }

        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            this,
            null,
            com.google.android.material.R.attr.textInputFilledStyle
        ).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hint = "대화 제목"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_FILLED
            setBoxCornerRadii(16f, 16f, 16f, 16f)
        }

        val input = com.google.android.material.textfield.TextInputEditText(inputLayout.context).apply {
            setText(currentSessionTitle)
            setSelection(text?.length ?: 0)
            isSingleLine = true
            maxLines = 1
        }

        inputLayout.addView(input)
        container.addView(inputLayout)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("대화 제목")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty() && newTitle != currentSessionTitle) {
                    saveSessionTitle(newTitle)
                }
            }
            .setNegativeButton("취소", null)
            .show()

        // 키보드 자동 표시
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun saveSessionTitle(title: String) {
        updateSessionTitle(title)

        if (currentSessionId > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                chatDao.updateSessionTitle(currentSessionId, title)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "제목이 변경되었습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupConsoleHeaderButtons() {
        // 헤더 메뉴 버튼 - 세션 드로어 열기
        binding.btnHeaderMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // 모드 선택 버튼
        binding.btnRemoteMode.setOnClickListener {
            // 원격 모드로 전환
            useCLIMode = false
            updateModeButtons(isRemote = true)
            appendLog("[모드] 원격 서버 모드")
        }

        binding.btnLocalMode.setOnClickListener {
            // 로컬 CLI 모드로 전환
            useCLIMode = true
            updateModeButtons(isRemote = false)
            appendLog("[모드] 로컬 CLI 모드")
            initializeClaudeBridge()
        }

        // 길게 누르면 Claude 로그인
        binding.btnLocalMode.setOnLongClickListener {
            if (claudeOAuth.isLoggedIn()) {
                // 이미 로그인됨 - 로그아웃 확인
                android.app.AlertDialog.Builder(this)
                    .setTitle("Claude 로그아웃")
                    .setMessage("Claude에서 로그아웃 하시겠습니까?")
                    .setPositiveButton("로그아웃") { _, _ ->
                        claudeOAuth.logout()
                        appendLog("[CLI] 로그아웃 완료", type = MessageType.SYSTEM)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            } else {
                // 로그인 시작
                startClaudeLogin()
            }
            true
        }

        binding.btnTerminalMode.setOnClickListener {
            // 터미널 모드로 전환 - TerminalActivity 시작
            startActivity(Intent(this, com.naenwa.remote.terminal.TerminalActivity::class.java))
        }

        // 상태 텍스트 클릭 - 로그인/연결/해제 토글
        val connectionClickListener = android.view.View.OnClickListener {
            if (!authManager.isLoggedIn) {
                // 로그인 안 되어 있으면 로그인 시작
                startGoogleSignIn()
                return@OnClickListener
            }

            val isConnected = FloatingService.webSocketClient != null || directWebSocketClient != null
            if (isConnected) {
                disconnect()
            } else {
                // 기기 목록에서 연결 시도
                fetchDevicesAndConnect()
            }
        }
        binding.tvStatus.setOnClickListener(connectionClickListener)
        binding.statusIndicator.setOnClickListener(connectionClickListener)

        // 새로고침 버튼 - 기기 목록 새로고침 및 연결
        binding.btnRefresh.setOnClickListener {
            appendLog("기기 목록 새로고침...")
            fetchDevicesAndConnect()
        }

        // 빌드 버튼 - 빌드 요청
        binding.btnBuild.setOnClickListener {
            val client = FloatingService.webSocketClient ?: directWebSocketClient
            if (client != null) {
                client.requestBuild()
                appendLog("[빌드 요청]")
            } else {
                Toast.makeText(this, "먼저 서버에 연결하세요", Toast.LENGTH_SHORT).show()
            }
        }

        // Git 푸시 버튼
        binding.btnGitPush.setOnClickListener {
            val client = FloatingService.webSocketClient ?: directWebSocketClient
            if (client == null) {
                Toast.makeText(this, "먼저 서버에 연결하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            client.requestGitPush("Update from Naenwa")
            Toast.makeText(this, "푸시 중...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            binding.progressLoading.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            if (useCLIMode) {
                // CLI 모드에서는 로딩 중이 아닐 때 항상 활성화
                binding.btnSend.isEnabled = !show
            } else {
                // 원격 모드에서는 연결 상태도 확인
                val hasConnection = FloatingService.webSocketClient != null || directWebSocketClient != null
                binding.btnSend.isEnabled = !show && hasConnection
            }
        }
    }

    private fun updateModeButtons(isRemote: Boolean) {
        runOnUiThread {
            if (isRemote) {
                binding.btnRemoteMode.setIconTintResource(R.color.primary)
                binding.btnRemoteMode.setBackgroundColor(ContextCompat.getColor(this, R.color.mode_selected_bg))
                binding.btnLocalMode.setIconTintResource(R.color.text_hint)
                binding.btnLocalMode.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // 원격 모드: 서버 연결 상태에 따라 Send 버튼 활성화
                val hasConnection = FloatingService.webSocketClient != null || directWebSocketClient != null
                binding.btnSend.isEnabled = hasConnection
            } else {
                binding.btnRemoteMode.setIconTintResource(R.color.text_hint)
                binding.btnRemoteMode.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.btnLocalMode.setIconTintResource(R.color.primary)
                binding.btnLocalMode.setBackgroundColor(ContextCompat.getColor(this, R.color.mode_selected_bg))

                // CLI 모드: Send 버튼 항상 활성화
                binding.btnSend.isEnabled = true
                binding.tvStatus.text = "CLI 모드"
            }
        }
    }

    private fun connectToServer(url: String) {
        appendLog("연결 중: $url")

        // FloatingService가 실행 중이면 해당 서비스를 통해 연결
        val service = FloatingService.instance
        if (service != null) {
            setupServiceCallbacks(service)
            service.connectToServer(url)
            // 현재 세션 ID 전달 (Claude 세션 ID 포함)
            serverSessionId?.let { service.updateSessionId(it, currentClaudeSessionId) }
        } else {
            // 서비스가 없으면 직접 연결 (플로팅 버튼 시작 시 서비스로 이전됨)
            connectDirectly(url)
        }
    }

    private fun setupServiceCallbacks(service: FloatingService) {
        service.onConnectionStateChanged = { connected ->
            if (connected) {
                appendLog("연결됨!")
            } else {
                appendLog("연결 끊김")
            }
            updateConnectionUI(connected)
        }

        service.onMessageReceived = { message ->
            handleServerMessage(message)
        }
    }

    private fun connectDirectly(url: String) {
        // 기존 연결 해제
        FloatingService.webSocketClient?.disconnect()
        directWebSocketClient?.disconnect()

        val client = WebSocketClient(url)
        directWebSocketClient = client

        lifecycleScope.launch {
            client.connectionState.collectLatest { state ->
                when (state) {
                    is WebSocketClient.ConnectionState.Connected -> {
                        appendLog("연결됨!")
                        updateConnectionUI(true)
                        // 세션 복원 (Claude 세션 ID 포함)
                        serverSessionId?.let { client.resumeSession(it, currentClaudeSessionId) }
                    }
                    is WebSocketClient.ConnectionState.Disconnected -> {
                        appendLog("연결 끊김")
                        updateConnectionUI(false)
                    }
                    is WebSocketClient.ConnectionState.Reconnecting -> {
                        val delaySeconds = state.delayMs / 1000
                        if (state.attempt <= state.maxAttempts) {
                            appendLog("재연결 시도 ${state.attempt}/${state.maxAttempts} (${delaySeconds}초 후)")
                        } else {
                            appendLog("재연결 시도 중... (${delaySeconds}초 후)")
                        }
                        updateConnectionUI(false)
                    }
                    is WebSocketClient.ConnectionState.Error -> {
                        appendLog("오류: ${state.message}")
                        updateConnectionUI(false)
                    }
                    is WebSocketClient.ConnectionState.Connecting -> {
                        appendLog("연결 시도 중...")
                    }
                }
            }
        }

        lifecycleScope.launch {
            client.messages.collectLatest { message ->
                handleServerMessage(message)
            }
        }

        client.connect()
    }

    private fun handleServerMessage(message: WebSocketClient.ServerMessage) {
        val client = FloatingService.webSocketClient ?: directWebSocketClient
        when (message) {
            is WebSocketClient.ServerMessage.Connected -> {
                serverSessionId = message.sessionId
                appendLog("세션: ${message.sessionId}")
                // 서비스에 세션 ID 업데이트
                FloatingService.instance?.updateSessionId(message.sessionId, currentClaudeSessionId)
            }
            is WebSocketClient.ServerMessage.ClaudeOutput -> {
                appendStream(message.text)
                saveClaudeOutput(message.text)
            }
            is WebSocketClient.ServerMessage.ToolUse -> {
                appendLog("[${message.tool}] ${message.message}", type = MessageType.TOOL_USE)
                saveMessage("[${message.tool}] ${message.message}", MessageType.TOOL_USE)
            }
            is WebSocketClient.ServerMessage.BuildStatus -> {
                appendLog("[빌드] ${message.status}")
                saveMessage(message.status, MessageType.BUILD_LOG)
            }
            is WebSocketClient.ServerMessage.BuildReady -> {
                appendLog("[빌드] APK 준비 완료! 다운로드 중...")
                client?.let { downloadAndInstallApk(it.serverUrl, message.apkUrl) }
            }
            is WebSocketClient.ServerMessage.BuildLog -> {
                appendLog("[빌드] ${message.text}", type = MessageType.BUILD_LOG)
            }
            is WebSocketClient.ServerMessage.GitStatus -> {
                when (message.status) {
                    "complete" -> Toast.makeText(this, "✅ ${message.message}", Toast.LENGTH_SHORT).show()
                    "error" -> Toast.makeText(this, "❌ ${message.message}", Toast.LENGTH_LONG).show()
                }
            }
            is WebSocketClient.ServerMessage.System -> {
                appendLog("[시스템] ${message.message}")
                if (message.message.contains("complete", ignoreCase = true)) {
                    finishStreaming()
                    showLoading(false)
                    flushClaudeOutputBuffer()
                }
            }
            is WebSocketClient.ServerMessage.Error -> {
                appendLog("[오류] ${message.message}")
                finishStreaming()
                showLoading(false)
                flushClaudeOutputBuffer()
            }
            is WebSocketClient.ServerMessage.ClaudeSessionId -> {
                // Claude CLI 세션 ID 저장
                currentClaudeSessionId = message.claudeSessionId
                appendLog("[세션] Claude 세션 ID 저장됨: ${message.claudeSessionId.take(8)}...")
                // DB에도 저장
                if (currentSessionId > 0) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        chatDao.updateClaudeSessionId(currentSessionId, message.claudeSessionId)
                    }
                }
            }
            is WebSocketClient.ServerMessage.ProjectPath -> {
                appendLog("[프로젝트] 서버 경로: ${message.path}")
            }

            // 메시지 큐 관련
            is WebSocketClient.ServerMessage.Queued -> {
                // 메시지가 대기열에 추가됨
                Toast.makeText(this, "📋 ${message.message}", Toast.LENGTH_SHORT).show()
                appendLog("[대기열] ${message.message}: ${message.textPreview}", type = MessageType.SYSTEM)
            }
            is WebSocketClient.ServerMessage.Processing -> {
                // 메시지 처리 시작
                if (message.queueRemaining > 0) {
                    appendLog("[처리 중] 남은 대기: ${message.queueRemaining}개", type = MessageType.SYSTEM)
                }
            }
            is WebSocketClient.ServerMessage.QueueStatus -> {
                // 대기열 상태 업데이트
                if (message.remaining > 0) {
                    appendLog("[대기열] ${message.message}", type = MessageType.SYSTEM)
                }
            }
        }
    }

    // ===== Claude Bridge 통합 (nodejs-mobile 기반) =====
    private var bridgeInitialized = false

    private fun initializeClaudeBridge() {
        lifecycleScope.launch {
            appendLog("[CLI] 초기화 중...")
            val success = claudeBridge.initialize()
            bridgeInitialized = success
            if (success) {
                // OAuth 토큰이 있으면 설정
                val oauthToken = claudeOAuth.getAccessToken()
                if (oauthToken != null) {
                    val tokenSet = claudeBridge.setApiKey(oauthToken)
                    if (tokenSet) {
                        appendLog("[CLI] Claude 로그인 완료")
                    }
                } else {
                    appendLog("[CLI] Claude 로그인 필요 (버튼 길게 누르기)", type = MessageType.SYSTEM)
                }
                appendLog("[CLI] 준비 완료")
                updateCLIModeUI(ready = true)
            } else {
                appendLog("[CLI] 초기화 실패", type = MessageType.SYSTEM)
                updateCLIModeUI(ready = false)
            }
        }
    }

    private var waitingForOAuthCode = false

    /**
     * Claude OAuth 로그인 시작
     */
    private fun startClaudeLogin() {
        appendLog("[CLI] Claude 로그인 페이지로 이동...", type = MessageType.SYSTEM)
        appendLog("[CLI] 로그인 후 표시된 코드를 복사하세요!", type = MessageType.SYSTEM)
        waitingForOAuthCode = true
        val intent = claudeOAuth.startLogin()
        startActivity(intent)
    }

    /**
     * OAuth 코드 입력 다이얼로그 표시
     */
    private fun showOAuthCodeInputDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "인증 코드 붙여넣기"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(50, 30, 50, 30)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Claude 로그인")
            .setMessage("브라우저에서 표시된 인증 코드를 붙여넣으세요")
            .setView(editText)
            .setPositiveButton("로그인") { _, _ ->
                val code = editText.text.toString().trim()
                if (code.isNotEmpty()) {
                    exchangeOAuthCode(code)
                } else {
                    appendLog("[CLI] 코드가 비어있습니다", type = MessageType.SYSTEM)
                }
            }
            .setNegativeButton("취소") { _, _ ->
                appendLog("[CLI] 로그인 취소됨", type = MessageType.SYSTEM)
            }
            .show()
    }

    private fun exchangeOAuthCode(code: String) {
        lifecycleScope.launch {
            appendLog("[CLI] 토큰 교환 중...", type = MessageType.SYSTEM)
            val success = claudeOAuth.exchangeCodeForToken(code)
            if (success) {
                appendLog("[CLI] 로그인 성공!", type = MessageType.SYSTEM)
                // 토큰을 ClaudeBridge에 설정
                claudeOAuth.getAccessToken()?.let { token ->
                    claudeBridge.setApiKey(token)
                    appendLog("[CLI] Claude 준비 완료!")
                }
            } else {
                appendLog("[CLI] 로그인 실패 - 코드가 올바르지 않거나 만료됨", type = MessageType.SYSTEM)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // OAuth callback 처리
        intent?.data?.let { uri ->
            if (uri.scheme == "naenwa" && uri.host == "oauth") {
                lifecycleScope.launch {
                    appendLog("[CLI] 로그인 처리 중...", type = MessageType.SYSTEM)
                    val success = claudeOAuth.handleCallback(uri)
                    if (success) {
                        appendLog("[CLI] 로그인 성공!", type = MessageType.SYSTEM)
                        // 토큰을 ClaudeBridge에 설정
                        claudeOAuth.getAccessToken()?.let { token ->
                            claudeBridge.setApiKey(token)
                        }
                    } else {
                        appendLog("[CLI] 로그인 실패", type = MessageType.SYSTEM)
                    }
                }
            }
        }
    }

    private fun sendToCLI(message: String) {
        lifecycleScope.launch {
            if (!bridgeInitialized) {
                initializeClaudeBridge()
                delay(1000)
            }

            // 스트리밍으로 전송
            claudeBridge.sendPromptStreaming(message).collect { event ->
                when (event) {
                    is StreamEvent.Data -> {
                        appendStream(event.content)
                        saveClaudeOutput(event.content)
                    }
                    is StreamEvent.End -> {
                        finishStreaming()
                        showLoading(false)
                        flushClaudeOutputBuffer()
                    }
                    is StreamEvent.Error -> {
                        appendLog("[CLI 오류] ${event.message}", type = MessageType.SYSTEM)
                        showLoading(false)
                    }
                }
            }
        }
    }

    private fun updateCLIModeUI(ready: Boolean) {
        runOnUiThread {
            binding.btnSend.isEnabled = ready || !useCLIMode
            binding.tvStatus.text = if (ready) "CLI 준비됨" else "CLI 대기 중"
        }
    }

    private fun requestGitCloneIfNeeded(client: WebSocketClient) {
        // 선택된 저장소 정보 가져오기
        val prefs = getSharedPreferences("naenwa", MODE_PRIVATE)
        val repoName = prefs.getString("selected_repo_name", null)
        val repoUrl = prefs.getString("selected_repo_url", null)

        if (repoName != null && repoUrl != null) {
            val accessToken = gitHubAuthManager.accessToken ?: ""
            appendLog("[Git] 저장소 동기화: $repoName")
            client.requestGitClone(repoUrl, repoName, accessToken)
        }
    }

    // Claude 출력 배치 저장
    private val claudeOutputBuffer = StringBuilder()

    private fun saveClaudeOutput(text: String) {
        synchronized(claudeOutputBuffer) {
            claudeOutputBuffer.append(text)
        }
    }

    private fun flushClaudeOutputBuffer() {
        synchronized(claudeOutputBuffer) {
            if (claudeOutputBuffer.isNotEmpty()) {
                saveMessageInternal(claudeOutputBuffer.toString(), MessageType.CLAUDE_OUTPUT)
                claudeOutputBuffer.clear()
            }
        }
    }

    private fun disconnect() {
        flushClaudeOutputBuffer()
        FloatingService.instance?.disconnect()
        directWebSocketClient?.disconnect()
        directWebSocketClient = null
        updateConnectionUI(false)
    }

    private fun updateConnectionUI(connected: Boolean) {
        runOnUiThread {
            binding.btnSend.isEnabled = connected

            binding.tvStatus.text = if (connected) "연결됨" else "연결 안됨"

            val statusColor = if (connected) {
                ContextCompat.getColor(this, R.color.status_connected)
            } else {
                ContextCompat.getColor(this, R.color.status_disconnected)
            }
            binding.statusIndicator.backgroundTintList = ColorStateList.valueOf(statusColor)

            // 드로어 하단 상태 표시도 업데이트
            binding.drawerStatusIndicator.backgroundTintList = ColorStateList.valueOf(statusColor)
            binding.tvSessionInfo.text = if (connected) "서버 연결됨" else "연결 안됨"

            // 버튼 색상 업데이트
            val btnTint = if (connected) R.color.primary else R.color.text_hint
            binding.btnRefresh.setIconTintResource(btnTint)
            binding.btnBuild.setIconTintResource(btnTint)
            binding.btnGitPush.setIconTintResource(btnTint)
        }
    }

    private fun appendLog(text: String, prefix: String = "", type: MessageType = MessageType.SYSTEM) {
        runOnUiThread {
            val content = if (prefix.isNotEmpty()) "$prefix$text" else text
            val message = ChatDisplayMessage(
                id = System.currentTimeMillis(),
                type = type,
                content = content.trimEnd('\n')
            )
            chatMessages.add(message)
            val scrollPosition = chatMessages.size - 1
            val shouldScroll = isUserAtBottom
            chatAdapter.submitList(chatMessages.toList()) {
                // 사용자가 맨 아래에 있을 때만 자동 스크롤
                if (shouldScroll) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.smoothScrollToPosition(scrollPosition)
                        isUserAtBottom = true  // 스크롤 후 상태 업데이트
                    }
                }
            }
        }
    }

    private fun appendStream(text: String) {
        synchronized(streamBuffer) {
            // 새 스트리밍 메시지 시작
            if (currentStreamingMessageId == -1L) {
                currentStreamingMessageId = System.currentTimeMillis()
                lastFlushTime = System.currentTimeMillis()
                val message = ChatDisplayMessage(
                    id = currentStreamingMessageId,
                    type = MessageType.CLAUDE_OUTPUT,
                    content = ""
                )
                chatMessages.add(message)
            }

            streamBuffer.append(text)

            // 동적 flush 간격 조절
            // 버퍼 크기에 따라 조절: 큰 데이터는 빨리 flush (30ms), 작은 데이터는 천천히 (100ms)
            streamFlushDelayMs = when {
                streamBuffer.length > 500 -> 30L  // 많은 데이터: 빠르게
                streamBuffer.length > 100 -> 50L  // 중간: 기본값
                else -> 100L                       // 적은 데이터: 천천히 (깜박임 방지)
            }

            if (!streamPending) {
                streamPending = true
                streamHandler.postDelayed({
                    flushStreamBuffer()
                }, streamFlushDelayMs)
            }
        }
    }

    private fun flushStreamBuffer() {
        synchronized(streamBuffer) {
            if (streamBuffer.isEmpty()) {
                streamPending = false
                return
            }

            // 현재 스트리밍 메시지 업데이트
            val index = chatMessages.indexOfFirst { it.id == currentStreamingMessageId }
            if (index >= 0) {
                val currentContent = chatMessages[index].content
                val newContent = currentContent + streamBuffer.toString()
                chatMessages[index] = chatMessages[index].copy(content = newContent)
            }

            streamBuffer.clear()
            streamPending = false
        }

        val scrollPosition = chatMessages.size - 1
        val shouldScroll = isUserAtBottom
        runOnUiThread {
            chatAdapter.submitList(chatMessages.toList()) {
                // 사용자가 맨 아래에 있을 때만 자동 스크롤
                if (shouldScroll) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.smoothScrollToPosition(scrollPosition)
                        isUserAtBottom = true
                    }
                }
            }
        }
    }

    private fun finishStreaming() {
        flushStreamBuffer()
        synchronized(streamBuffer) {
            currentStreamingMessageId = -1L
        }
    }

    private fun requestPermissions() {
        // 오디오 권한
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 알림 권한 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 저장소 권한 (터미널 모드용 - 앱 삭제해도 Ubuntu 환경 유지)
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : MANAGE_EXTERNAL_STORAGE 권한 필요
            if (!android.os.Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("파일 접근 권한 필요")
                    .setMessage("터미널 모드에서 Ubuntu 환경을 영구 저장하려면 파일 접근 권한이 필요합니다.\n\n이 권한을 허용하면 앱을 삭제해도 Ubuntu 환경이 유지됩니다.")
                    .setPositiveButton("설정으로 이동") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        manageStorageLauncher.launch(intent)
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        } else {
            // Android 10 이하 : READ/WRITE_EXTERNAL_STORAGE 권한
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needsPermission = permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needsPermission) {
                storagePermissionLauncher.launch(permissions)
            }
        }
    }

    private fun checkAndStartFloatingService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(FloatingService.EXTRA_RESULT_DATA, mediaProjectionIntent)
            putExtra(FloatingService.EXTRA_SERVER_URL, serverUrl)
            serverSessionId?.let { putExtra(FloatingService.EXTRA_SESSION_ID, it) }
            currentClaudeSessionId?.let { putExtra(FloatingService.EXTRA_CLAUDE_SESSION_ID, it) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // 서비스 시작 후 콜백 설정
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            FloatingService.instance?.let { service ->
                setupServiceCallbacks(service)
                // 이미 연결된 상태면 UI 업데이트
                if (FloatingService.webSocketClient != null) {
                    updateConnectionUI(true)
                }
            }
        }, 500)

        Toast.makeText(this, "플로팅 버튼 활성화됨 - 백그라운드 연결 유지", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingService::class.java))
        Toast.makeText(this, "플로팅 버튼 비활성화됨", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // 서비스가 실행 중이면 콜백 다시 연결
        FloatingService.instance?.let { service ->
            setupServiceCallbacks(service)
            // 연결 상태 UI 업데이트
            val isConnected = FloatingService.webSocketClient != null
            updateConnectionUI(isConnected)
        }

        // OAuth 코드 입력 대기 중이면 다이얼로그 표시
        if (waitingForOAuthCode) {
            waitingForOAuthCode = false
            binding.root.postDelayed({
                showOAuthCodeInputDialog()
            }, 500)
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentSession()
        flushClaudeOutputBuffer()
        // 직접 연결한 클라이언트 정리
        directWebSocketClient?.disconnect()
        directWebSocketClient = null
        // 서비스는 백그라운드에서 계속 실행됨 (연결 유지)
        // 콜백만 해제
        FloatingService.instance?.onConnectionStateChanged = null
        FloatingService.instance?.onMessageReceived = null
    }

    private var lastReportedProgress = -1

    private fun downloadAndInstallApk(serverUrl: String, apkPath: String) {
        lastReportedProgress = -1
        lifecycleScope.launch {
            apkInstaller.downloadAndInstall(
                baseUrl = serverUrl,
                apkPath = apkPath,
                onProgress = { progress ->
                    val milestone = (progress / 25) * 25
                    if (milestone > lastReportedProgress) {
                        lastReportedProgress = milestone
                        appendLog("[다운로드] $milestone%")
                    }
                },
                onComplete = {
                    appendLog("[설치] APK 설치 화면이 표시됩니다...")
                },
                onError = { error ->
                    appendLog("[오류] APK 다운로드 실패: $error")
                }
            )
        }
    }
}
