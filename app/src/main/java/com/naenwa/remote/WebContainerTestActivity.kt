package com.naenwa.remote

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Claude Code 웹 버전 Activity
 * claude.ai/code를 Chrome Custom Tabs로 열기
 */
class WebContainerTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Claude Code 웹 버전 열기 (Claude Max 로그인 필요)
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTabsIntent.launchUrl(
            this,
            Uri.parse("https://claude.ai/code")
        )

        // Activity 종료 (Chrome Custom Tab이 열림)
        finish()
    }
}
