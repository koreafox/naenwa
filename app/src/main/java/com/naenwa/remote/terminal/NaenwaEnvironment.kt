package com.naenwa.remote.terminal

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Naenwa 환경 관리자
 * 외부 저장소에 proot-distro Ubuntu 환경을 저장하여 앱 삭제 후에도 유지
 */
object NaenwaEnvironment {

    private const val TAG = "NaenwaEnvironment"

    // 외부 저장소 경로 (앱 삭제해도 유지됨)
    private const val EXTERNAL_DIR_NAME = "naenwa"
    private const val PROOT_ROOTFS_DIR = "ubuntu-rootfs"
    private const val SETUP_COMPLETE_FLAG = ".setup_complete"

    /**
     * 외부 저장소의 Naenwa 디렉토리 경로
     */
    fun getExternalNaenwaDir(): File {
        val externalStorage = Environment.getExternalStorageDirectory()
        return File(externalStorage, EXTERNAL_DIR_NAME)
    }

    /**
     * Ubuntu rootfs 경로
     */
    fun getUbuntuRootfsPath(): String {
        return File(getExternalNaenwaDir(), PROOT_ROOTFS_DIR).absolutePath
    }

    /**
     * 환경이 이미 설정되어 있는지 확인
     */
    fun isSetupComplete(): Boolean {
        val setupFlag = File(getExternalNaenwaDir(), SETUP_COMPLETE_FLAG)
        val rootfsExists = File(getUbuntuRootfsPath(), "bin/bash").exists()
        val claudeExists = File(getUbuntuRootfsPath(), "root/.npm-global/bin/claude").exists() ||
                          File(getUbuntuRootfsPath(), "usr/local/bin/claude").exists()

        Log.d(TAG, "Setup check - flag: ${setupFlag.exists()}, rootfs: $rootfsExists, claude: $claudeExists")

        return setupFlag.exists() && rootfsExists
    }

    /**
     * Claude CLI가 설치되어 있는지 확인
     */
    fun isClaudeInstalled(): Boolean {
        val globalNpm = File(getUbuntuRootfsPath(), "root/.npm-global/bin/claude")
        val usrLocal = File(getUbuntuRootfsPath(), "usr/local/bin/claude")
        return globalNpm.exists() || usrLocal.exists()
    }

    /**
     * 설정 완료 플래그 생성
     */
    fun markSetupComplete() {
        try {
            val naenwaDir = getExternalNaenwaDir()
            naenwaDir.mkdirs()
            File(naenwaDir, SETUP_COMPLETE_FLAG).writeText(
                "Naenwa proot-distro environment\n" +
                "Setup completed: ${java.util.Date()}\n" +
                "Android: ${Build.VERSION.RELEASE}\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n"
            )
            Log.i(TAG, "Setup complete flag created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create setup flag", e)
        }
    }

    /**
     * 환경 초기화 (삭제)
     */
    fun resetEnvironment() {
        try {
            val naenwaDir = getExternalNaenwaDir()
            if (naenwaDir.exists()) {
                naenwaDir.deleteRecursively()
                Log.i(TAG, "Environment reset complete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset environment", e)
        }
    }

    /**
     * 필요한 디렉토리 생성
     */
    fun ensureDirectoriesExist() {
        try {
            val naenwaDir = getExternalNaenwaDir()
            naenwaDir.mkdirs()
            File(naenwaDir, "tmp").mkdirs()
            File(naenwaDir, "home").mkdirs()
            Log.d(TAG, "Directories ensured at ${naenwaDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create directories", e)
        }
    }

    /**
     * 디바이스 아키텍처 가져오기
     */
    fun getArchitecture(): String {
        return when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            Build.SUPPORTED_ABIS.contains("x86") -> "i686"
            else -> "aarch64"
        }
    }

    /**
     * proot 실행 명령어 생성
     */
    fun getProotCommand(prefixPath: String): Array<String> {
        val rootfsPath = getUbuntuRootfsPath()
        val prootPath = "$prefixPath/bin/proot"

        return arrayOf(
            prootPath,
            "--link2symlink",
            "-0",  // fake root
            "-r", rootfsPath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${getExternalNaenwaDir()}/home:/root",
            "-b", "/sdcard:/sdcard",
            "-w", "/root",
            "/bin/bash", "--login"
        )
    }

    /**
     * 환경 변수 생성
     */
    fun getEnvironmentVariables(prefixPath: String): Array<String> {
        return arrayOf(
            "TERM=xterm-256color",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.npm-global/bin",
            "PROOT_NO_SECCOMP=1",
            "LD_LIBRARY_PATH=$prefixPath/lib",
            "TMPDIR=/tmp",
            "USER=root",
            "COLORTERM=truecolor",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
    }
}
