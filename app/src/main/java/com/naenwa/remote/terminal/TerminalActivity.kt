package com.naenwa.remote.terminal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.naenwa.remote.R
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity(), TerminalViewClient, TerminalSessionClient {

    companion object {
        private const val STORAGE_PERMISSION_CODE = 1001
        private const val MANAGE_STORAGE_CODE = 1002
    }

    private lateinit var terminalView: TerminalView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private var currentSession: TerminalSession? = null
    private var currentTextSize = 24

    private val PREFIX_PATH = "/data/data/com.termux/files/usr"
    private val HOME_PATH = "/data/data/com.termux/files/home"
    private val BIN_PATH = "$PREFIX_PATH/bin"

    // External storage for user data (survives app uninstall)
    private val NAENWA_DIR = File(Environment.getExternalStorageDirectory(), "naenwa")

    // Ubuntu rootfs MUST be in internal storage for execute permission
    // Android external storage doesn't support execute permission
    private val UBUNTU_ROOTFS_PATH: File
        get() = File(filesDir, "ubuntu-rootfs")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.terminal_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(24)

        checkAndSetupEnvironment()
    }

    private fun checkAndSetupEnvironment() {
        val prefixDir = File(PREFIX_PATH)
        val bashPath = File("$BIN_PATH/bash")
        val nodePath = File("$BIN_PATH/node")
        val claudePath = File("$BIN_PATH/claude")

        // Check what needs to be installed
        val needsBootstrap = !prefixDir.exists() || !bashPath.exists()
        val needsClaude = !nodePath.exists() || !claudePath.exists()

        android.util.Log.i("Terminal", "needsBootstrap: $needsBootstrap, needsClaude: $needsClaude")

        if (needsBootstrap || needsClaude) {
            // Need to install something
            showInstallProgress()
            lifecycleScope.launch {
                val success = TerminalInstaller.install(this@TerminalActivity) { progress, message ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusText.text = message
                    }
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        hideInstallProgress()
                        fixLibrarySymlinks()
                        fixTermuxPaths()
                        fixPermissions()
                        fixClaudeShebang()
                        startTerminalSession()
                    } else {
                        statusText.text = "Installation failed. Please restart the app."
                    }
                }
            }
        } else {
            // Already installed, just fix and start
            fixLibrarySymlinks()
            fixTermuxPaths()
            fixPermissions()
            fixClaudeShebang()
            startTerminalSession()
        }
    }

    private fun showInstallProgress() {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        terminalView.visibility = View.GONE
    }

    private fun hideInstallProgress() {
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
    }

    private fun fixLibrarySymlinks() {
        val libDir = File("$PREFIX_PATH/lib")
        if (!libDir.exists()) return

        libDir.listFiles()?.forEach { file ->
            // Handle .so.X.Y.Z patterns (e.g., libbz2.so.1.0.8)
            if (file.isFile && file.name.matches(Regex(".*\\.so\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                val parts = file.name.split(".")
                // Create all intermediate symlinks:
                // .so.1.0.8 -> .so.1.0 -> .so.1 -> .so
                for (dropCount in 1 until parts.size - parts.indexOf("so")) {
                    val targetName = parts.dropLast(dropCount).joinToString(".")
                    val symlink = File(libDir, targetName)
                    if (!symlink.exists()) {
                        try {
                            android.system.Os.symlink(file.name, symlink.absolutePath)
                            android.util.Log.i("Terminal", "Created symlink: $targetName -> ${file.name}")
                        } catch (e: Exception) {
                            try {
                                file.copyTo(symlink, overwrite = true)
                                android.util.Log.i("Terminal", "Copied library: $targetName")
                            } catch (e2: Exception) {
                                android.util.Log.w("Terminal", "Failed to create $targetName: ${e2.message}")
                            }
                        }
                    }
                }
            }
            // Handle .so.X.Y patterns (e.g., libncursesw.so.6.3)
            else if (file.isFile && file.name.matches(Regex(".*\\.so\\.[0-9]+\\.[0-9]+"))) {
                val parts = file.name.split(".")
                if (parts.size >= 3) {
                    val baseSo = parts.dropLast(1).joinToString(".")
                    val symlink = File(libDir, baseSo)
                    if (!symlink.exists()) {
                        try {
                            android.system.Os.symlink(file.name, symlink.absolutePath)
                            android.util.Log.i("Terminal", "Created symlink: $baseSo -> ${file.name}")
                        } catch (e: Exception) {
                            try {
                                file.copyTo(symlink, overwrite = true)
                                android.util.Log.i("Terminal", "Copied library: $baseSo")
                            } catch (e2: Exception) {
                                android.util.Log.w("Terminal", "Failed to create $baseSo: ${e2.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fixPermissions() {
        // Fix permissions for multiple directories
        val dirsToFix = listOf(
            "$PREFIX_PATH/bin",
            "$PREFIX_PATH/lib/apt/methods",
            "$PREFIX_PATH/libexec",
            "$PREFIX_PATH/libexec/termux"
        )

        dirsToFix.forEach { dirPath ->
            val dir = File(dirPath)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && !file.canExecute()) {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                        android.util.Log.i("Terminal", "Fixed permissions for ${file.name}")
                    }
                }
            }
        }
    }

    private fun fixClaudeShebang() {
        // Fix Claude CLI shebang issue
        // The npm package uses #!/usr/bin/env node, but Termux doesn't have /usr/bin/env
        // Create a wrapper script that calls node directly
        val claudeSymlink = File("$BIN_PATH/claude")
        val claudeCliPath = "$PREFIX_PATH/lib/node_modules/@anthropic-ai/claude-code/cli.js"
        val nodePath = "$BIN_PATH/node"

        // Check if claude exists and needs fixing
        if (!File(claudeCliPath).exists()) {
            android.util.Log.i("Terminal", "Claude CLI not installed, skipping shebang fix")
            return
        }

        try {
            // Check if it's already a wrapper script (not a symlink)
            if (claudeSymlink.exists() && !isSymlink(claudeSymlink)) {
                val content = claudeSymlink.readText()
                if (content.contains("exec") && content.contains(nodePath)) {
                    android.util.Log.i("Terminal", "Claude CLI wrapper already exists")
                    return
                }
            }

            // Remove the npm-created symlink
            if (claudeSymlink.exists()) {
                claudeSymlink.delete()
            }

            // Create a wrapper script that calls node directly
            claudeSymlink.writeText("""
#!/data/data/com.termux/files/usr/bin/bash
exec "$nodePath" "$claudeCliPath" "$@"
""".trimIndent())
            claudeSymlink.setExecutable(true, false)
            claudeSymlink.setReadable(true, false)

            android.util.Log.i("Terminal", "Fixed Claude CLI shebang with wrapper script")
        } catch (e: Exception) {
            android.util.Log.e("Terminal", "Failed to fix Claude CLI shebang: ${e.message}")
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            file.canonicalPath != file.absolutePath
        } catch (e: Exception) {
            false
        }
    }

    private fun copyStartupScripts() {
        val scriptNames = listOf("naenwa-startup.sh", "naenwa-setup.sh")
        scriptNames.forEach { scriptName ->
            val scriptFile = File(HOME_PATH, scriptName)
            if (!scriptFile.exists()) {
                try {
                    assets.open(scriptName).use { input ->
                        scriptFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    scriptFile.setExecutable(true, false)
                    scriptFile.setReadable(true, false)
                    android.util.Log.i("Terminal", "Copied $scriptName to home")
                } catch (e: Exception) {
                    android.util.Log.e("Terminal", "Failed to copy $scriptName: ${e.message}")
                }
            }
        }
    }

    private fun fixTermuxPaths() {
        val oldPath = "/data/data/com.termux/files"
        val newPath = "/data/data/com.termux/files"

        val etcDir = File("$PREFIX_PATH/etc")
        val filesToFix = listOf("profile", "bash.bashrc", "termux-login.sh")

        filesToFix.forEach { fileName ->
            val file = File(etcDir, fileName)
            if (file.exists()) {
                try {
                    val content = file.readText()
                    if (content.contains(oldPath)) {
                        val fixed = content.replace(oldPath, newPath)
                        file.writeText(fixed)
                        android.util.Log.i("Terminal", "Fixed paths in $fileName")
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }

        // Also fix files in profile.d/
        val profileD = File(etcDir, "profile.d")
        profileD.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".sh")) {
                try {
                    val content = file.readText()
                    if (content.contains(oldPath)) {
                        val fixed = content.replace(oldPath, newPath)
                        file.writeText(fixed)
                        android.util.Log.i("Terminal", "Fixed paths in ${file.name}")
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }
    }

    private fun startTerminalSession() {
        val homeDir = File(HOME_PATH)
        if (!homeDir.exists()) homeDir.mkdirs()

        // Copy startup scripts
        copyStartupScripts()

        val bashPath = "$PREFIX_PATH/bin/bash"
        val bashFile = File(bashPath)

        android.util.Log.i("Terminal", "Starting Termux bash session with Python/Claude support...")

        val shell: String
        val args: Array<String>

        if (bashFile.exists() && bashFile.canExecute()) {
            shell = bashPath
            args = arrayOf("bash", "-l")  // Login shell
        } else {
            shell = "/system/bin/sh"
            args = arrayOf("sh")
        }

        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=$HOME_PATH",
            "PREFIX=$PREFIX_PATH",
            "LANG=en_US.UTF-8",
            "PATH=$BIN_PATH:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=$PREFIX_PATH/lib",
            "TMPDIR=$PREFIX_PATH/tmp",
            "COLORTERM=truecolor",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )

        currentSession = TerminalSession(
            shell,
            HOME_PATH,
            args,
            env,
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            this
        )

        terminalView.attachSession(currentSession)
        terminalView.requestFocus()

        android.util.Log.i("Terminal", "Termux bash session started")
    }

    // TerminalSessionClient implementation
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val exitStatus = finishedSession.exitStatus
        android.util.Log.e("Terminal", "Session finished with exit status: $exitStatus")

        if (exitStatus != 0) {
            // Show error and retry option instead of closing
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Session Failed")
                    .setMessage("Exit code: $exitStatus\n\nRetry or use fallback bash?")
                    .setPositiveButton("Retry") { _, _ ->
                        startTerminalSession()
                    }
                    .setNegativeButton("Fallback Bash") { _, _ ->
                        startFallbackBashSession()
                    }
                    .setNeutralButton("Close") { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        } else {
            finish()
        }
    }

    private fun startFallbackBashSession() {
        val bashPath = "$PREFIX_PATH/bin/bash"
        val bashFile = java.io.File(bashPath)

        val shell = if (bashFile.exists() && bashFile.canExecute()) bashPath else "/system/bin/sh"
        val args = if (bashFile.exists()) arrayOf("bash", "-l") else arrayOf("sh")
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=$HOME_PATH",
            "PREFIX=$PREFIX_PATH",
            "LANG=en_US.UTF-8",
            "PATH=$BIN_PATH:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=$PREFIX_PATH/lib",
            "TMPDIR=$PREFIX_PATH/tmp",
            "COLORTERM=truecolor"
        )

        currentSession = TerminalSession(
            shell,
            HOME_PATH,
            args,
            env,
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            this
        )

        terminalView.attachSession(currentSession)
        terminalView.requestFocus()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            currentSession?.write(text)
        }
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        android.util.Log.e("Terminal", "Shell PID: $pid")
    }

    override fun logError(tag: String?, message: String?) {
        android.util.Log.e(tag ?: "Terminal", message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        android.util.Log.w(tag ?: "Terminal", message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        android.util.Log.i(tag ?: "Terminal", message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        android.util.Log.d(tag ?: "Terminal", message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        android.util.Log.v(tag ?: "Terminal", message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.e(tag ?: "Terminal", message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        android.util.Log.e(tag ?: "Terminal", "Error", e)
    }

    // TerminalViewClient implementation
    override fun onScale(scale: Float): Float {
        val newSize = (currentTextSize * scale).toInt()
        if (newSize in 12..48) {
            currentTextSize = newSize
            terminalView.setTextSize(newSize)
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        terminalView.requestFocus()
        val inputManager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        inputManager.showSoftInput(terminalView, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            currentSession?.finishIfRunning()
            finish()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 터미널 세션 종료하고 이전 화면으로 돌아가기
        currentSession?.finishIfRunning()
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.finishIfRunning()
    }
}
