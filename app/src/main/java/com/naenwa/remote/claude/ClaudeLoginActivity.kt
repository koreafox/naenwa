package com.naenwa.remote.claude

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.naenwa.remote.auth.AuthManager
import com.naenwa.remote.databinding.ActivityClaudeLoginBinding
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Claude OAuth 로그인 Activity
 * WebView를 통해 claude.ai에 로그인하고 OAuth 토큰을 획득
 */
class ClaudeLoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClaudeLoginActivity"

        // OAuth 설정
        private const val AUTH_URL = "https://claude.ai/oauth/authorize"
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        // localhost redirect (Claude Code CLI 방식)
        private const val REDIRECT_URI = "http://localhost:8765/callback"
        private const val SCOPE = "user:inference user:profile"

        // 결과 코드
        const val RESULT_LOGIN_SUCCESS = 100
        const val RESULT_LOGIN_FAILED = 101
        const val RESULT_LOGIN_CANCELLED = 102

        // Intent extras
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_REFRESH_TOKEN = "refresh_token"
        const val EXTRA_EXPIRES_IN = "expires_in"
        const val EXTRA_LOGIN_HINT = "login_hint"  // Google 이메일 힌트
    }

    private lateinit var binding: ActivityClaudeLoginBinding
    private var tokenCaptured = false
    private var loginHint: String? = null  // Google 이메일 힌트 (자동 로그인용)
    private var oauthState: String? = null  // CSRF 방지용 state 파라미터
    private var codeVerifier: String? = null  // PKCE code_verifier

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClaudeLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 login_hint 가져오기 (없으면 AuthManager에서)
        loginHint = intent.getStringExtra(EXTRA_LOGIN_HINT)
            ?: AuthManager(this).savedUserEmail

        Log.i(TAG, "Login hint: $loginHint")

        setupUI()
        setupWebView()

        // 외부 브라우저에서 콜백으로 돌아온 경우 처리
        if (handleIntentCallback(intent)) {
            return
        }

        startOAuth()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntentCallback(it) }
    }

    /**
     * 외부 브라우저에서 콜백 URI로 돌아온 경우 처리
     * @return true if callback was handled
     */
    private fun handleIntentCallback(intent: Intent): Boolean {
        val uri = intent.data ?: return false

        if (uri.scheme == "claude-code" && uri.host == "oauth") {
            Log.i(TAG, "Received OAuth callback from browser: $uri")

            // SharedPreferences에서 저장된 state와 code_verifier 복원
            val prefs = getSharedPreferences("claude_oauth", MODE_PRIVATE)
            oauthState = prefs.getString("oauth_state", null)
            codeVerifier = prefs.getString("code_verifier", null)

            handleOAuthCallback(uri)
            return true
        }

        return false
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener {
            setResult(RESULT_LOGIN_CANCELLED)
            finish()
        }

        binding.btnRetry.setOnClickListener {
            binding.errorLayout.visibility = View.GONE
            startOAuth()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            // Cookie 허용
            val webViewInstance = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webViewInstance, true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    Log.d(TAG, "Loading URL: $url")

                    // OAuth 콜백 감지
                    if (url.startsWith(REDIRECT_URI) || url.startsWith("claude-code://")) {
                        handleOAuthCallback(Uri.parse(url))
                        return true
                    }

                    return false
                }

                // 페이지 시작 시에도 콜백 URL 확인 (일부 리다이렉트는 shouldOverrideUrlLoading을 우회함)
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "Page started: $url")

                    // 현재 URL 토스트로 표시 (디버깅용)
                    runOnUiThread {
                        binding.tvStatus.text = "URL: ${url?.take(50)}..."
                    }

                    // OAuth 콜백 감지
                    if (url?.startsWith(REDIRECT_URI) == true || url?.startsWith("claude-code://") == true) {
                        view?.stopLoading()
                        handleOAuthCallback(Uri.parse(url))
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE

                    Log.d(TAG, "Page finished: $url")

                    // 현재 페이지의 버튼들 로그
                    logPageButtons()

                    // OAuth 동의 페이지에서 승인 버튼 자동 클릭 시도
                    if (url?.contains("/oauth/authorize") == true) {
                        // 2초 후 승인 버튼 클릭 시도
                        binding.webView.postDelayed({
                            clickApproveButton()
                        }, 2000)
                    }

                    // OAuth 페이지에서 자동 Google 로그인 시도 (첫 화면에서만)
                    if (url?.contains("/oauth/authorize") == true && loginHint != null && !url.contains("consent")) {
                        autoClickGoogleLogin()
                    }

                    // Google 로그인 페이지에서는 자동 입력하지 않음 (보안 이슈)
                    // 사용자가 직접 계정을 선택하도록 함

                    // 토큰 페이지 감지 시도
                    if (url?.contains("/oauth") == true || url?.contains("/login") == true) {
                        injectTokenExtractor()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val url = request?.url?.toString()
                    Log.e(TAG, "WebView error for $url: ${error?.description}")

                    // 커스텀 스킴 URL 로드 실패 시 콜백으로 처리
                    if (url?.startsWith("claude-code://") == true) {
                        Log.i(TAG, "Caught callback URL from error: $url")
                        handleOAuthCallback(Uri.parse(url))
                        return
                    }

                    if (request?.isForMainFrame == true) {
                        showError("로드 실패: ${error?.description}")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: return true
                    Log.d(TAG, "Console: $msg")

                    // 디버그 메시지 UI에 표시
                    if (msg.startsWith("CLAUDE_DEBUG:")) {
                        runOnUiThread {
                            binding.tvStatus.text = msg.removePrefix("CLAUDE_DEBUG: ").take(60)
                        }
                    }

                    // 토큰 메시지 감지
                    if (msg.startsWith("CLAUDE_TOKEN:")) {
                        val tokenData = msg.removePrefix("CLAUDE_TOKEN:")
                        handleTokenData(tokenData)
                    }

                    return true
                }
            }
        }
    }

    private fun startOAuth() {
        binding.progressBar.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE

        // state 파라미터 생성 (CSRF 방지) - SharedPreferences에 저장
        oauthState = java.util.UUID.randomUUID().toString()

        // PKCE: code_verifier 생성 (43-128자의 랜덤 문자열) - SharedPreferences에 저장
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier!!)

        // state와 code_verifier를 SharedPreferences에 저장 (외부 브라우저 사용 시 필요)
        getSharedPreferences("claude_oauth", MODE_PRIVATE).edit().apply {
            putString("oauth_state", oauthState)
            putString("code_verifier", codeVerifier)
            apply()
        }

        // OAuth URL 구성
        val authUrl = buildString {
            append(AUTH_URL)
            append("?client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&response_type=code")
            append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
            append("&state=").append(URLEncoder.encode(oauthState!!, "UTF-8"))
            // PKCE 파라미터
            append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
            append("&code_challenge_method=S256")
            // login_hint 추가 (Google 이메일로 자동 선택)
            loginHint?.let { email ->
                append("&login_hint=").append(URLEncoder.encode(email, "UTF-8"))
            }
        }

        Log.i(TAG, "Starting OAuth: $authUrl")

        // WebView에서 OAuth 열기
        binding.webView.loadUrl(authUrl)
    }

    /**
     * 페이지의 버튼들을 로그로 출력 (디버깅용)
     */
    private fun logPageButtons() {
        val script = """
            (function() {
                var buttons = document.querySelectorAll('button');
                console.log('CLAUDE_DEBUG: Found ' + buttons.length + ' buttons');
                buttons.forEach(function(btn, i) {
                    console.log('CLAUDE_DEBUG: Button ' + i + ': ' + btn.textContent.trim() + ' | type=' + btn.type + ' | disabled=' + btn.disabled);
                });

                // form도 확인
                var forms = document.querySelectorAll('form');
                console.log('CLAUDE_DEBUG: Found ' + forms.length + ' forms');
                forms.forEach(function(form, i) {
                    console.log('CLAUDE_DEBUG: Form ' + i + ': action=' + form.action + ' | method=' + form.method);
                });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
    }

    /**
     * 승인 버튼 JavaScript로 클릭 (터치 이벤트 시뮬레이션)
     */
    fun clickApproveButton() {
        val script = """
            (function() {
                // 승인 버튼 찾기
                var approveBtn = Array.from(document.querySelectorAll('button')).find(
                    btn => btn.textContent.trim() === '승인' ||
                           btn.textContent.includes('Approve') ||
                           btn.textContent.includes('Allow')
                );

                if (!approveBtn) {
                    console.log('CLAUDE_DEBUG: Approve button not found');
                    return 'not_found';
                }

                var rect = approveBtn.getBoundingClientRect();
                var x = rect.left + rect.width / 2;
                var y = rect.top + rect.height / 2;

                console.log('CLAUDE_DEBUG: Button found at (' + x + ', ' + y + ')');

                // 터치 이벤트 시뮬레이션
                function simulateTouch(element) {
                    var touch = new Touch({
                        identifier: Date.now(),
                        target: element,
                        clientX: x,
                        clientY: y,
                        radiusX: 2.5,
                        radiusY: 2.5,
                        rotationAngle: 10,
                        force: 0.5,
                    });

                    var touchStart = new TouchEvent('touchstart', {
                        cancelable: true,
                        bubbles: true,
                        touches: [touch],
                        targetTouches: [touch],
                        changedTouches: [touch]
                    });

                    var touchEnd = new TouchEvent('touchend', {
                        cancelable: true,
                        bubbles: true,
                        touches: [],
                        targetTouches: [],
                        changedTouches: [touch]
                    });

                    element.dispatchEvent(touchStart);
                    setTimeout(function() {
                        element.dispatchEvent(touchEnd);
                        console.log('CLAUDE_DEBUG: Touch events dispatched');
                    }, 50);
                }

                // 먼저 일반 클릭 시도
                approveBtn.click();
                console.log('CLAUDE_DEBUG: click() called');

                // 100ms 후 터치 이벤트 시도
                setTimeout(function() {
                    try {
                        simulateTouch(approveBtn);
                    } catch(e) {
                        console.log('CLAUDE_DEBUG: Touch simulation failed: ' + e);
                    }
                }, 100);

                // React의 onClick 핸들러 직접 호출 시도
                setTimeout(function() {
                    try {
                        var reactKey = Object.keys(approveBtn).find(key => key.startsWith('__reactFiber'));
                        if (reactKey) {
                            console.log('CLAUDE_DEBUG: Found React fiber, trying to trigger onClick');
                            var fiber = approveBtn[reactKey];
                            if (fiber && fiber.memoizedProps && fiber.memoizedProps.onClick) {
                                fiber.memoizedProps.onClick({ preventDefault: function(){}, stopPropagation: function(){} });
                                console.log('CLAUDE_DEBUG: React onClick called directly');
                            }
                        }
                    } catch(e) {
                        console.log('CLAUDE_DEBUG: React onClick failed: ' + e);
                    }
                }, 300);

                return 'methods_executed';
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { result ->
            Log.i(TAG, "Click result: $result")
            runOnUiThread {
                binding.tvStatus.text = "버튼 클릭 시도 중..."
            }
        }
    }

    /**
     * PKCE: code_verifier 생성 (43-128자의 URL-safe 랜덤 문자열)
     */
    private fun generateCodeVerifier(): String {
        val secureRandom = java.security.SecureRandom()
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /**
     * PKCE: code_challenge 생성 (code_verifier의 SHA256 해시, base64url 인코딩)
     */
    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return android.util.Base64.encodeToString(hash,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /**
     * OAuth 콜백 처리
     */
    private fun handleOAuthCallback(uri: Uri) {
        Log.i(TAG, "OAuth callback: $uri")

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val returnedState = uri.getQueryParameter("state")

        // state 검증 (CSRF 방지)
        if (oauthState != null && returnedState != oauthState) {
            Log.e(TAG, "State mismatch! Expected: $oauthState, Got: $returnedState")
            showError("보안 오류: state 불일치")
            return
        }

        if (error != null) {
            Log.e(TAG, "OAuth error: $error")
            showError("로그인 실패: $error")
            return
        }

        if (code != null) {
            Log.i(TAG, "Got auth code, exchanging for token...")
            exchangeCodeForToken(code)
        } else {
            // 직접 토큰이 있는 경우 (implicit flow)
            val accessToken = uri.getQueryParameter("access_token")
            val refreshToken = uri.getQueryParameter("refresh_token")
            val expiresIn = uri.getQueryParameter("expires_in")?.toLongOrNull() ?: 28800

            if (accessToken != null) {
                handleTokenSuccess(accessToken, refreshToken, expiresIn)
            } else {
                showError("인증 코드를 받지 못했습니다")
            }
        }
    }

    /**
     * Authorization Code를 Access Token으로 교환
     */
    private fun exchangeCodeForToken(code: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "토큰 발급 중..."

        Thread {
            try {
                val url = java.net.URL("https://console.anthropic.com/api/oauth/token")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val requestBody = JSONObject().apply {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("client_id", CLIENT_ID)
                    put("redirect_uri", REDIRECT_URI)
                    // PKCE: code_verifier 추가
                    codeVerifier?.let { put("code_verifier", it) }
                }.toString()

                connection.outputStream.write(requestBody.toByteArray())

                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)

                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token")
                val expiresIn = json.optLong("expires_in", 28800)

                runOnUiThread {
                    if (accessToken.isNotEmpty()) {
                        handleTokenSuccess(accessToken, refreshToken, expiresIn)
                    } else {
                        showError("토큰을 받지 못했습니다")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                runOnUiThread {
                    showError("토큰 교환 실패: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * Google 로그인 페이지에서 이메일 자동 입력 및 다음 버튼 클릭
     */
    private fun autoFillGoogleEmail() {
        val email = loginHint ?: return
        Log.i(TAG, "Auto-filling Google email: $email")

        val script = """
            (function() {
                setTimeout(function() {
                    // 이메일 입력 필드 찾기
                    var emailInput = document.querySelector('input[type="email"]') ||
                                    document.querySelector('#identifierId') ||
                                    document.querySelector('input[name="identifier"]');

                    if (emailInput) {
                        console.log('CLAUDE_AUTO: Found email input, filling...');
                        emailInput.value = '$email';
                        emailInput.dispatchEvent(new Event('input', { bubbles: true }));

                        // 다음 버튼 클릭
                        setTimeout(function() {
                            var nextBtn = document.querySelector('#identifierNext') ||
                                         document.querySelector('button[type="submit"]') ||
                                         document.querySelector('[data-idom-class*="nCP5yc"]') ||
                                         Array.from(document.querySelectorAll('button, span[role="button"]')).find(
                                             btn => btn.textContent.includes('다음') ||
                                                    btn.textContent.includes('Next')
                                         );

                            if (nextBtn) {
                                console.log('CLAUDE_AUTO: Clicking next button...');
                                nextBtn.click();
                            }
                        }, 500);
                    } else {
                        console.log('CLAUDE_AUTO: Email input not found');
                    }
                }, 1000);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    /**
     * Google 로그인 버튼 자동 클릭 (login_hint가 있을 때)
     */
    private fun autoClickGoogleLogin() {
        Log.i(TAG, "Auto-clicking Google login button...")

        val script = """
            (function() {
                // 잠시 대기 후 Google 버튼 찾아서 클릭
                setTimeout(function() {
                    // Google 로그인 버튼 찾기 (여러 셀렉터 시도)
                    var googleBtn = document.querySelector('button[data-provider="google"]') ||
                                   document.querySelector('[data-testid="google-button"]') ||
                                   document.querySelector('button:has(svg[data-icon="google"])') ||
                                   Array.from(document.querySelectorAll('button')).find(
                                       btn => btn.textContent.includes('Google') ||
                                              btn.textContent.includes('구글')
                                   );

                    if (googleBtn) {
                        console.log('CLAUDE_AUTO: Found Google button, clicking...');
                        googleBtn.click();
                    } else {
                        console.log('CLAUDE_AUTO: Google button not found');
                        // 모든 버튼 로그
                        document.querySelectorAll('button').forEach(function(btn, i) {
                            console.log('CLAUDE_AUTO: Button ' + i + ': ' + btn.textContent.substring(0, 50));
                        });
                    }
                }, 1000);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    /**
     * JavaScript로 토큰 추출 시도
     */
    private fun injectTokenExtractor() {
        val script = """
            (function() {
                // localStorage에서 토큰 찾기
                try {
                    var keys = Object.keys(localStorage);
                    for (var i = 0; i < keys.length; i++) {
                        var value = localStorage.getItem(keys[i]);
                        if (value && (value.indexOf('sk-ant-oat') !== -1 || value.indexOf('sk-ant-ort') !== -1)) {
                            console.log('CLAUDE_TOKEN:' + JSON.stringify({key: keys[i], value: value}));
                        }
                    }
                } catch(e) {}

                // Cookie에서 토큰 찾기
                try {
                    var cookies = document.cookie.split(';');
                    for (var i = 0; i < cookies.length; i++) {
                        var cookie = cookies[i].trim();
                        if (cookie.indexOf('sk-ant-oat') !== -1 || cookie.indexOf('sk-ant-ort') !== -1) {
                            console.log('CLAUDE_TOKEN:' + JSON.stringify({type: 'cookie', value: cookie}));
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    /**
     * 추출된 토큰 데이터 처리
     */
    private fun handleTokenData(data: String) {
        if (tokenCaptured) return

        try {
            val json = JSONObject(data)
            val value = json.optString("value")

            // 토큰 형식 파싱
            val accessToken = extractToken(value, "sk-ant-oat")
            val refreshToken = extractToken(value, "sk-ant-ort")

            if (accessToken != null) {
                tokenCaptured = true
                handleTokenSuccess(accessToken, refreshToken, 28800)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse token data", e)
        }
    }

    private fun extractToken(text: String, prefix: String): String? {
        val start = text.indexOf(prefix)
        if (start == -1) return null

        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] != '"' && text[end] != '\'') {
            end++
        }

        return text.substring(start, end)
    }

    /**
     * 토큰 획득 성공
     */
    private fun handleTokenSuccess(accessToken: String, refreshToken: String?, expiresIn: Long) {
        Log.i(TAG, "Token acquired successfully")

        // 토큰 저장
        val tokenManager = ClaudeTokenManager(this)
        tokenManager.saveTokens(accessToken, refreshToken, expiresIn)

        // 결과 반환
        val resultIntent = Intent().apply {
            putExtra(EXTRA_ACCESS_TOKEN, accessToken)
            putExtra(EXTRA_REFRESH_TOKEN, refreshToken)
            putExtra(EXTRA_EXPIRES_IN, expiresIn)
        }

        setResult(RESULT_LOGIN_SUCCESS, resultIntent)
        Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 에러 표시
     */
    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            setResult(RESULT_LOGIN_CANCELLED)
            super.onBackPressed()
        }
    }
}
