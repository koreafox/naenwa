package com.naenwa.remote.claude

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * ClaudeCLIBridge - Chat UI와 Claude CLI 간의 브릿지
 *
 * Claude CLI를 proot-distro + Box64를 통해 실행하고
 * stdin/stdout을 통해 대화를 주고받습니다.
 */
class ClaudeCLIBridge {

    companion object {
        private const val TAG = "ClaudeCLIBridge"

        // Termux paths
        private const val PREFIX = "/data/data/com.termux/files/usr"
        private const val HOME = "/data/data/com.termux/files/home"
        private const val BIN = "$PREFIX/bin"
    }

    // 상태
    sealed class State {
        object Idle : State()
        object Starting : State()
        object Ready : State()
        data class Processing(val prompt: String) : State()
        data class Error(val message: String) : State()
    }

    // 출력 이벤트
    sealed class OutputEvent {
        data class Text(val content: String) : OutputEvent()
        data class ToolUse(val tool: String, val message: String) : OutputEvent()
        data class Error(val message: String) : OutputEvent()
        object Complete : OutputEvent()
        object Started : OutputEvent()
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerJob: Job? = null
    private var errorReaderJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _output = MutableSharedFlow<OutputEvent>(extraBufferCapacity = 100)
    val output: SharedFlow<OutputEvent> = _output.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 프로젝트 경로
    private var projectPath: String = HOME

    /**
     * CLI 환경이 설치되어 있는지 확인
     */
    fun isInstalled(): Boolean {
        val bashFile = File("$BIN/bash")
        val claudeWrapper = File("$BIN/claude")
        return bashFile.exists() && claudeWrapper.exists()
    }

    /**
     * 프로젝트 경로 설정
     */
    fun setProjectPath(path: String) {
        projectPath = path
    }

    /**
     * CLI 프로세스 시작
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (process != null) {
            Log.w(TAG, "Process already running")
            return@withContext true
        }

        if (!isInstalled()) {
            _state.value = State.Error("Claude CLI가 설치되지 않았습니다. 터미널에서 먼저 설치를 완료하세요.")
            _output.emit(OutputEvent.Error("Claude CLI not installed"))
            return@withContext false
        }

        _state.value = State.Starting

        try {
            // Claude CLI wrapper 스크립트 실행
            // --print 모드: 비대화형으로 실행, 프롬프트 받아서 응답 출력
            val pb = ProcessBuilder(
                "$BIN/bash",
                "-c",
                """
                export HOME="$HOME"
                export PREFIX="$PREFIX"
                export PATH="$BIN:/system/bin"
                export TERM=dumb
                export LANG=en_US.UTF-8
                export LD_LIBRARY_PATH="$PREFIX/lib"
                cd "$projectPath"

                # Claude CLI 대화형 모드
                # proot를 통해 Ubuntu로 진입하고 Claude 실행
                proot-distro login ubuntu -- /bin/bash -c '
                    source ~/.bashrc 2>/dev/null
                    export BOX64_LOG=0
                    export PATH="/opt/node/bin:${'$'}PATH"
                    box64 /opt/node/bin/node /opt/node/lib/node_modules/@anthropic-ai/claude-code/cli.js
                '
                """.trimIndent()
            )

            pb.environment().apply {
                put("HOME", HOME)
                put("PREFIX", PREFIX)
                put("PATH", "$BIN:/system/bin")
                put("TERM", "dumb")
                put("LD_LIBRARY_PATH", "$PREFIX/lib")
            }

            pb.directory(File(projectPath))
            pb.redirectErrorStream(false)  // stderr 분리

            process = pb.start()
            writer = OutputStreamWriter(process!!.outputStream)

            // stdout 읽기
            readerJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        Log.d(TAG, "stdout: $text")

                        // 출력 파싱
                        when {
                            text.startsWith("⏺") || text.startsWith("●") -> {
                                // 도구 사용 표시
                                _output.emit(OutputEvent.ToolUse("Tool", text))
                            }
                            text.contains("[claude]") || text.contains("Claude") -> {
                                _output.emit(OutputEvent.Text(text + "\n"))
                            }
                            text.isNotBlank() -> {
                                _output.emit(OutputEvent.Text(text + "\n"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stdout reader error", e)
                }
            }

            // stderr 읽기
            errorReaderJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        Log.w(TAG, "stderr: $text")

                        // Box64 경고 무시
                        if (text.contains("Box64") || text.contains("dynarec")) {
                            continue
                        }

                        // 심각한 에러만 보고
                        if (text.contains("error", ignoreCase = true) ||
                            text.contains("failed", ignoreCase = true)) {
                            _output.emit(OutputEvent.Error(text))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stderr reader error", e)
                }
            }

            _state.value = State.Ready
            _output.emit(OutputEvent.Started)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Claude CLI", e)
            _state.value = State.Error("CLI 시작 실패: ${e.message}")
            _output.emit(OutputEvent.Error("Failed to start: ${e.message}"))
            false
        }
    }

    /**
     * 메시지 전송
     */
    suspend fun sendMessage(message: String) {
        val w = writer
        val p = process

        if (w == null || p == null || !p.isAlive) {
            // 프로세스가 없으면 시작
            if (!start()) {
                return
            }
        }

        _state.value = State.Processing(message)

        withContext(Dispatchers.IO) {
            try {
                writer?.apply {
                    write(message + "\n")
                    flush()
                }
                Log.d(TAG, "Sent: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                _output.emit(OutputEvent.Error("메시지 전송 실패: ${e.message}"))
            }
        }
    }

    /**
     * 단일 프롬프트 실행 (비대화형)
     * --print 모드로 한 번만 실행하고 결과 반환
     */
    suspend fun executeOnce(prompt: String): Flow<String> = flow {
        if (!isInstalled()) {
            emit("[오류] Claude CLI가 설치되지 않았습니다.")
            return@flow
        }

        try {
            val pb = ProcessBuilder(
                "$BIN/bash",
                "-c",
                """
                export HOME="$HOME"
                export PREFIX="$PREFIX"
                export PATH="$BIN:/system/bin"
                export TERM=dumb
                export LD_LIBRARY_PATH="$PREFIX/lib"
                cd "$projectPath"

                # --print 모드로 비대화형 실행
                proot-distro login ubuntu -- /bin/bash -c '
                    export BOX64_LOG=0
                    export PATH="/opt/node/bin:${'$'}PATH"
                    box64 /opt/node/bin/node /opt/node/lib/node_modules/@anthropic-ai/claude-code/cli.js --print "$prompt"
                '
                """.trimIndent()
            )

            pb.environment().apply {
                put("HOME", HOME)
                put("PREFIX", PREFIX)
                put("PATH", "$BIN:/system/bin")
                put("LD_LIBRARY_PATH", "$PREFIX/lib")
            }

            pb.directory(File(projectPath))
            pb.redirectErrorStream(true)

            val process = pb.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line ?: continue

                // Box64 로그 무시
                if (text.contains("Box64") || text.contains("dynarec")) {
                    continue
                }

                emit(text + "\n")
            }

            process.waitFor()

        } catch (e: Exception) {
            Log.e(TAG, "executeOnce failed", e)
            emit("[오류] ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 프로세스 종료
     */
    fun stop() {
        try {
            readerJob?.cancel()
            errorReaderJob?.cancel()
            writer?.close()
            process?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping CLI", e)
        } finally {
            process = null
            writer = null
            readerJob = null
            errorReaderJob = null
            _state.value = State.Idle
        }
    }

    /**
     * 리소스 정리
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }
}
