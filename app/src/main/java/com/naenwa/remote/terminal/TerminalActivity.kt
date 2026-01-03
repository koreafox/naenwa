package com.naenwa.remote.terminal

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var terminalView: TerminalView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private var currentSession: TerminalSession? = null
    private var currentTextSize = 24

    private val PREFIX_PATH = "/data/data/com.termux/files/usr"
    private val HOME_PATH = "/data/data/com.termux/files/home"
    private val BIN_PATH = "$PREFIX_PATH/bin"

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

        if (prefixDir.exists() && bashPath.exists()) {
            // Already installed, but fix library symlinks, paths, and permissions if needed
            fixLibrarySymlinks()
            fixTermuxPaths()
            fixPermissions()
            startTerminalSession()
        } else {
            // Need to install bootstrap
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
                        startTerminalSession()
                    } else {
                        statusText.text = "Installation failed. Please restart the app."
                    }
                }
            }
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
            if (file.isFile && file.name.matches(Regex(".*\\.so\\.[0-9]+\\.[0-9]+"))) {
                // Handle .so.X.Y -> .so.X (e.g., libncursesw.so.6.3 -> libncursesw.so.6)
                val parts = file.name.split(".")
                if (parts.size >= 3) {
                    val baseSo = parts.dropLast(1).joinToString(".")
                    val symlink = File(libDir, baseSo)
                    if (!symlink.exists()) {
                        try {
                            android.system.Os.symlink(file.name, symlink.absolutePath)
                            android.util.Log.i("Terminal", "Created symlink: $baseSo -> ${file.name}")
                        } catch (e: Exception) {
                            file.copyTo(symlink, overwrite = true)
                            android.util.Log.i("Terminal", "Copied library: $baseSo")
                        }
                    }
                }
            }
        }
    }

    private fun fixPermissions() {
        val binDir = File("$PREFIX_PATH/bin")
        binDir.listFiles()?.forEach { file ->
            if (!file.canExecute()) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
                android.util.Log.i("Terminal", "Fixed permissions for ${file.name}")
            }
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

        // Copy startup scripts if they don't exist (in case of fresh install or update)
        copyStartupScripts()

        val bashPath = "$PREFIX_PATH/bin/bash"

        // With targetSdk 28, we can execute binaries from app data directory
        val shell: String
        val args: Array<String>

        val bashFile = File(bashPath)
        if (bashFile.exists() && bashFile.canExecute()) {
            shell = bashPath
            args = arrayOf("bash", "-l")  // Login shell
        } else {
            // Fallback to system sh if bash not available
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

        // Auto-run setup if not installed yet
        // Note: The setup script requires termux bash, but SELinux blocks bash execution
        // For now, just display a message instructing user to run the setup manually
    }

    // TerminalSessionClient implementation
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        android.util.Log.e("Terminal", "Session finished with exit status: ${finishedSession.exitStatus}")
        finish()
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

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.finishIfRunning()
    }
}
