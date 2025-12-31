package com.naenwa.remote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.naenwa.remote.auth.GitHubAuthManager
import kotlinx.coroutines.launch

class GitHubLoginActivity : AppCompatActivity() {

    private lateinit var gitHubAuthManager: GitHubAuthManager
    private var isWaitingForCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gitHubAuthManager = GitHubAuthManager(this)

        // 이미 로그인되어 있으면 프로젝트 선택으로
        if (gitHubAuthManager.isLoggedIn) {
            goToProjectSelect()
            return
        }

        // Callback으로 돌아온 경우 처리
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onResume() {
        super.onResume()

        // Chrome에서 돌아왔는데 callback이 없으면 (사용자가 취소한 경우)
        if (isWaitingForCallback && !gitHubAuthManager.isLoggedIn) {
            // 다시 로그인 시도하거나 종료
            if (intent?.data == null) {
                // 사용자가 취소했을 수 있음 - 다시 브라우저 열기
                openGitHubLogin()
            }
        } else if (!isWaitingForCallback && !gitHubAuthManager.isLoggedIn) {
            // 처음 실행 - GitHub 로그인 페이지 열기
            openGitHubLogin()
        }
    }

    private fun openGitHubLogin() {
        isWaitingForCallback = true

        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTabsIntent.launchUrl(this, Uri.parse(gitHubAuthManager.getAuthUrl()))
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri != null && uri.scheme == "naenwa" && uri.host == "callback") {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")

            when {
                code != null -> exchangeToken(code)
                error != null -> {
                    Toast.makeText(this, "로그인 취소됨", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun exchangeToken(code: String) {
        lifecycleScope.launch {
            Toast.makeText(this@GitHubLoginActivity, "GitHub 연결 중...", Toast.LENGTH_SHORT).show()

            val result = gitHubAuthManager.exchangeCodeForToken(code)
            result.fold(
                onSuccess = {
                    Toast.makeText(this@GitHubLoginActivity, "GitHub 연결 완료!", Toast.LENGTH_SHORT).show()
                    goToProjectSelect()
                },
                onFailure = { e ->
                    Toast.makeText(this@GitHubLoginActivity, "로그인 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    isWaitingForCallback = false
                    openGitHubLogin()
                }
            )
        }
    }

    private fun goToProjectSelect() {
        startActivity(Intent(this, ProjectSelectActivity::class.java))
        finish()
    }
}
