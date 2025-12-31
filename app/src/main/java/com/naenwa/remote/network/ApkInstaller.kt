package com.naenwa.remote.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * APK 다운로드 및 설치 관리
 * 원격 서버에서 빌드된 APK를 다운로드하고 설치 요청
 */
class ApkInstaller(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * APK 다운로드 및 설치 인텐트 실행
     * @param baseUrl 서버 기본 URL (예: https://xxx.trycloudflare.com)
     * @param apkPath APK 경로 (예: /download-apk)
     * @param onProgress 다운로드 진행률 콜백 (0-100)
     * @param onComplete 완료 콜백
     * @param onError 에러 콜백
     */
    suspend fun downloadAndInstall(
        baseUrl: String,
        apkPath: String,
        onProgress: (Int) -> Unit = {},
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            try {
                val url = baseUrl.trimEnd('/') + apkPath
                Log.d(TAG, "Downloading APK from: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onError("Download failed: ${response.code}")
                    }
                    return@withContext
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) {
                        onError("Empty response")
                    }
                    return@withContext
                }

                // 임시 파일로 저장 (타임스탬프로 매번 새 파일)
                val timestamp = System.currentTimeMillis()
                val apkFile = File(context.cacheDir, "update_$timestamp.apk")

                // 이전 APK 파일들 삭제
                context.cacheDir.listFiles()?.filter { it.name.startsWith("update_") && it.name.endsWith(".apk") }?.forEach {
                    it.delete()
                    Log.d(TAG, "Deleted old APK: ${it.name}")
                }
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "APK downloaded: ${apkFile.length()} bytes")

                // 설치 인텐트 실행
                withContext(Dispatchers.Main) {
                    // 권한 체크
                    if (!canInstallApks()) {
                        onError("'출처를 알 수 없는 앱' 설치 권한이 필요합니다. 설정 화면으로 이동합니다.")
                        openInstallPermissionSettings()
                        return@withContext
                    }

                    val success = installApk(apkFile)
                    if (success) {
                        onComplete()
                    } else {
                        onError("설치 화면을 열 수 없습니다.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * 앱 설치 권한 확인
     */
    fun canInstallApks(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 앱 설치 권한 설정 화면 열기
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * APK 설치 인텐트 실행
     */
    private fun installApk(apkFile: File): Boolean {
        return try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
                return false
            }

            // 설치 권한 확인
            if (!canInstallApks()) {
                Log.e(TAG, "No permission to install APKs")
                openInstallPermissionSettings()
                return false
            }

            Log.d(TAG, "Installing APK: ${apkFile.absolutePath}, size: ${apkFile.length()}")

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            Log.d(TAG, "APK URI: $uri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                setDataAndType(uri, "application/vnd.android.package-archive")
            }

            context.startActivity(intent)
            Log.d(TAG, "Install intent started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install intent: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
