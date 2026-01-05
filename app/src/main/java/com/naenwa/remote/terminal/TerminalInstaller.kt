package com.naenwa.remote.terminal

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

object TerminalInstaller {

    private const val TAG = "TerminalInstaller"
    private const val BOOTSTRAP_VERSION = "bootstrap-2022.04.28-r5+apt-android-7"

    private val BOOTSTRAP_URL: String
        get() {
            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
                Build.SUPPORTED_ABIS.contains("x86") -> "i686"
                else -> "aarch64"
            }
            return "https://github.com/termux/termux-packages/releases/download/$BOOTSTRAP_VERSION/bootstrap-$arch.zip"
        }

    // Ubuntu rootfs URL (official Ubuntu base)
    private val UBUNTU_ROOTFS_URL: String
        get() {
            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "amd64"
                else -> "arm64"
            }
            return "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-$arch.tar.gz"
        }

    // Use hardcoded Termux paths (bootstrap expects these exact paths)
    private fun getPrefixPath(context: Context) = "/data/data/com.termux/files/usr"
    private fun getHomePath(context: Context) = "/data/data/com.termux/files/home"
    private fun getStagingPath(context: Context) = "${context.filesDir.absolutePath}/staging"

    // External storage paths for persistent Ubuntu environment
    // Use /sdcard/naenwa/ for persistence (survives app uninstall)
    private var cachedExternalDir: File? = null

    /**
     * Get external naenwa directory for user data (survives app uninstall)
     * Used for: home folder, user configurations
     */
    private fun getExternalNaenwaDir(context: Context): File {
        if (cachedExternalDir != null) return cachedExternalDir!!

        // Primary: shared external storage /sdcard/naenwa/ (survives app uninstall)
        val sharedDir = File(Environment.getExternalStorageDirectory(), "naenwa")
        if (sharedDir.mkdirs() || sharedDir.exists()) {
            Log.i(TAG, "Using shared external dir for user data: ${sharedDir.absolutePath}")
            cachedExternalDir = sharedDir
            return sharedDir
        }

        // Fallback: app's external files directory
        val appExternalDir = context.getExternalFilesDir(null)
        if (appExternalDir != null) {
            val naenwaDir = File(appExternalDir, "naenwa")
            if (naenwaDir.mkdirs() || naenwaDir.exists()) {
                Log.i(TAG, "Using app external dir: ${naenwaDir.absolutePath}")
                cachedExternalDir = naenwaDir
                return naenwaDir
            }
        }

        // Last resort: app's internal files directory
        val internalDir = File(context.filesDir, "naenwa")
        internalDir.mkdirs()
        Log.i(TAG, "Using internal dir: ${internalDir.absolutePath}")
        cachedExternalDir = internalDir
        return internalDir
    }

    /**
     * Get Ubuntu rootfs path - MUST be in internal storage for execute permission
     * Android external storage (sdcard) doesn't support execute permission
     */
    private fun getUbuntuRootfsPath(context: Context): File {
        val internalDir = File(context.filesDir, "ubuntu-rootfs")
        internalDir.mkdirs()
        return internalDir
    }

    suspend fun install(
        context: Context,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefixPath = getPrefixPath(context)
            val homePath = getHomePath(context)
            val stagingPath = getStagingPath(context)

            // Check if bootstrap already exists
            val bootstrapExists = File(prefixPath, "bin/bash").exists()

            if (bootstrapExists) {
                Log.i(TAG, "Bootstrap already exists, skipping bootstrap installation")
                onProgress(40, "Bootstrap already installed...")
            } else {
                // Clean up and install bootstrap
                File(prefixPath).deleteRecursively()
                File(stagingPath).deleteRecursively()
                File(stagingPath).mkdirs()
                File(homePath).mkdirs()

                onProgress(3, "Downloading Termux bootstrap...")

                // Download bootstrap
                val bootstrapZip = File(stagingPath, "bootstrap.zip")
                downloadFile(BOOTSTRAP_URL, bootstrapZip) { progress ->
                    onProgress(3 + (progress * 0.25).toInt(), "Downloading bootstrap... $progress%")
                }

                onProgress(28, "Extracting bootstrap...")

                // Extract bootstrap
                extractBootstrap(bootstrapZip, prefixPath) { progress ->
                    onProgress(28 + (progress * 0.2).toInt(), "Extracting... $progress%")
                }

                // Cleanup bootstrap zip
                File(stagingPath).deleteRecursively()
            }

            // Always setup symlinks (needed even if bootstrap already exists)
            onProgress(48, "Setting up symlinks...")
            setupSymlinks(prefixPath)

            onProgress(50, "Fixing paths...")

            // Fix hardcoded termux paths in profile files
            fixTermuxPaths(prefixPath)

            // Set permissions first (needed for pkg/npm to work)
            setPermissions(prefixPath)

            onProgress(55, "Installing Node.js...")

            // Install Node.js and Claude CLI
            installNodeAndClaude(prefixPath, homePath, onProgress)

            onProgress(95, "Creating config files...")

            // Create bashrc with welcome message
            createBashConfig(homePath)

            // Copy startup scripts
            copySetupScript(context, homePath)

            onProgress(100, "Installation complete!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Install Node.js and Claude Code CLI during bootstrap installation
     */
    private fun installNodeAndClaude(prefixPath: String, homePath: String, onProgress: (Int, String) -> Unit) {
        val binPath = "$prefixPath/bin"
        val envVars = arrayOf(
            "PATH=$binPath:/system/bin:/system/xbin",
            "HOME=$homePath",
            "PREFIX=$prefixPath",
            "LD_LIBRARY_PATH=$prefixPath/lib",
            "TMPDIR=$prefixPath/tmp",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )

        try {
            // Step 1: pkg update (with auto-accept for config files)
            onProgress(58, "Updating package list...")
            runShellCommand("$binPath/bash", arrayOf("-c",
                "yes | pkg update -y -o Dpkg::Options::=--force-confnew"), homePath, envVars)

            // Step 2: Install Node.js
            onProgress(65, "Installing Node.js (this may take a while)...")
            runShellCommand("$binPath/bash", arrayOf("-c",
                "yes | pkg install -y -o Dpkg::Options::=--force-confnew nodejs-lts"), homePath, envVars)

            // Step 3: Install Claude Code CLI
            onProgress(80, "Installing Claude Code CLI...")
            runShellCommand("$binPath/bash", arrayOf("-c", "npm install -g @anthropic-ai/claude-code"), homePath, envVars)

            // Step 4: Fix Claude CLI shebang issue
            // The npm package uses #!/usr/bin/env node, but Termux doesn't have /usr/bin/env
            // Create a wrapper script that calls node directly
            onProgress(90, "Configuring Claude CLI...")
            fixClaudeShebang(prefixPath)

            Log.i(TAG, "Node.js and Claude Code CLI installed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Node.js/Claude: ${e.message}", e)
            // Don't throw - we'll create a setup script as fallback
            createFallbackSetupScript(homePath)
        }
    }

    private fun runShellCommand(shell: String, args: Array<String>, workDir: String, envVars: Array<String>) {
        val processBuilder = ProcessBuilder(shell, *args)
            .directory(File(workDir))
            .redirectErrorStream(true)

        processBuilder.environment().clear()
        envVars.forEach { env ->
            val parts = env.split("=", limit = 2)
            if (parts.size == 2) {
                processBuilder.environment()[parts[0]] = parts[1]
            }
        }

        val process = processBuilder.start()

        // Read output for logging
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            Log.d(TAG, "Shell: $line")
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            Log.w(TAG, "Shell command exited with code: $exitCode")
        }
    }

    private fun createFallbackSetupScript(homePath: String) {
        val setupScript = File(homePath, "setup-claude.sh")
        setupScript.writeText("""
#!/data/data/com.termux/files/usr/bin/bash
set -e
echo "Installing Node.js..."
pkg update -y && pkg install -y nodejs-lts
echo "Installing Claude Code CLI..."
npm install -g @anthropic-ai/claude-code
echo "Done! Type 'claude' to start."
""".trimIndent())
        setupScript.setExecutable(true, false)
        Log.i(TAG, "Created fallback setup script")
    }

    /**
     * Fix Claude CLI shebang issue
     * The npm package uses #!/usr/bin/env node, but Termux doesn't have /usr/bin/env
     * Create a wrapper script that calls node directly
     */
    private fun fixClaudeShebang(prefixPath: String) {
        val binPath = "$prefixPath/bin"
        val claudeSymlink = File(binPath, "claude")
        val claudeCliPath = "$prefixPath/lib/node_modules/@anthropic-ai/claude-code/cli.js"
        val nodePath = "$binPath/node"

        try {
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

            Log.i(TAG, "Fixed Claude CLI shebang with wrapper script")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fix Claude CLI shebang: ${e.message}", e)
        }
    }

    /**
     * Create .bashrc that auto-starts Claude
     */
    private fun createBashConfig(homePath: String) {
        val bashrcFile = File(homePath, ".bashrc")
        bashrcFile.writeText("""
# Naenwa Terminal Configuration
# Auto-start Claude on terminal open
if command -v claude &> /dev/null; then
    claude
fi
""".trimIndent())
        bashrcFile.setReadable(true, false)

        val bashProfileFile = File(homePath, ".bash_profile")
        bashProfileFile.writeText("""
# Source .bashrc for interactive login shells
if [ -f ~/.bashrc ]; then
    source ~/.bashrc
fi
""".trimIndent())
        bashProfileFile.setReadable(true, false)

        Log.i(TAG, "Created bash config at $homePath")
    }

    /**
     * Extract proot binary from .deb package
     */
    private fun extractProotFromDeb(debFile: File, prootDest: File, binDir: File) {
        Log.i(TAG, "Extracting proot from deb package...")

        // .deb is an ar archive containing data.tar.xz
        org.apache.commons.compress.archivers.ar.ArArchiveInputStream(
            FileInputStream(debFile)
        ).use { arInput ->
            var arEntry = arInput.nextArEntry
            while (arEntry != null) {
                if (arEntry.name.startsWith("data.tar")) {
                    Log.i(TAG, "Found ${arEntry.name} in deb")

                    // Determine compression type
                    val tarInput = when {
                        arEntry.name.endsWith(".xz") -> {
                            TarArchiveInputStream(
                                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(arInput)
                            )
                        }
                        arEntry.name.endsWith(".gz") -> {
                            TarArchiveInputStream(GZIPInputStream(arInput))
                        }
                        arEntry.name.endsWith(".zst") -> {
                            TarArchiveInputStream(
                                org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(arInput)
                            )
                        }
                        else -> {
                            TarArchiveInputStream(arInput)
                        }
                    }

                    // Extract proot binary and libraries
                    var tarEntry = tarInput.nextTarEntry
                    while (tarEntry != null) {
                        val name = tarEntry.name
                        when {
                            name.endsWith("/bin/proot") || name.endsWith("/proot") -> {
                                Log.i(TAG, "Found proot: $name")
                                FileOutputStream(prootDest).use { fos ->
                                    tarInput.copyTo(fos)
                                }
                                prootDest.setExecutable(true, false)
                            }
                            name.contains("/lib/") && name.endsWith(".so") -> {
                                // Also extract required libraries
                                val libName = name.substringAfterLast("/")
                                val libDest = File(binDir.parentFile, "lib/$libName")
                                libDest.parentFile?.mkdirs()
                                FileOutputStream(libDest).use { fos ->
                                    tarInput.copyTo(fos)
                                }
                                Log.i(TAG, "Extracted lib: $libName")
                            }
                        }
                        tarEntry = tarInput.nextTarEntry
                    }
                    break
                }
                arEntry = arInput.nextArEntry
            }
        }

        if (prootDest.exists()) {
            Log.i(TAG, "proot extracted successfully: ${prootDest.length()} bytes")
        } else {
            Log.e(TAG, "Failed to extract proot from deb")
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val connection = URL(url).openConnection()
        connection.connect()
        val totalSize = connection.contentLength
        var downloaded = 0

        connection.getInputStream().use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalSize > 0) {
                        onProgress((downloaded * 100 / totalSize))
                    }
                }
            }
        }
    }

    private fun extractBootstrap(zipFile: File, destDir: String, onProgress: (Int) -> Unit) {
        val dest = File(destDir)
        dest.mkdirs()

        val symlinkMap = mutableMapOf<String, String>()

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                count++
                if (count % 100 == 0) {
                    onProgress((count / 50).coerceAtMost(100))
                }

                val name = entry.name

                if (name == "SYMLINKS.txt") {
                    // Read symlinks file without closing the stream
                    val reader = InputStreamReader(zis)
                    val content = StringBuilder()
                    val buffer = CharArray(1024)
                    var len: Int
                    while (reader.read(buffer).also { len = it } != -1) {
                        content.append(buffer, 0, len)
                    }
                    content.toString().lines().forEach { line ->
                        val parts = line.split("←")
                        if (parts.size == 2) {
                            val linkPath = parts[0].trim()
                            val targetPath = parts[1].trim()
                            symlinkMap[linkPath] = targetPath
                        }
                    }
                } else {
                    val file = File(dest, name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Create symlinks
        for ((linkPath, targetPath) in symlinkMap) {
            try {
                val link = File(dest, linkPath)
                link.parentFile?.mkdirs()

                // Use Os.symlink for Android API 21+
                if (Build.VERSION.SDK_INT >= 21) {
                    val target = if (targetPath.startsWith("/")) targetPath else targetPath
                    try {
                        link.delete()
                        android.system.Os.symlink(target, link.absolutePath)
                    } catch (e: Exception) {
                        // Fallback: create a shell script that calls the target
                        if (link.name != link.parentFile?.name) {
                            link.writeText("#!/data/data/com.termux/files/usr/bin/sh\nexec $target \"\$@\"\n")
                            link.setExecutable(true)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore symlink errors
            }
        }
    }

    private fun setupSymlinks(prefixPath: String) {
        val binDir = File(prefixPath, "bin")
        val libDir = File(prefixPath, "lib")
        val etcDir = File(prefixPath, "etc")
        val aptDir = File(prefixPath, "lib/apt/methods")

        // 1. Create sh -> bash symlink
        val shFile = File(binDir, "sh")
        val bashFile = File(binDir, "bash")
        if (!shFile.exists() && bashFile.exists()) {
            try {
                android.system.Os.symlink("bash", shFile.absolutePath)
            } catch (e: Exception) {
                shFile.writeText("#!/data/data/com.termux/files/usr/bin/bash\nexec bash \"\$@\"\n")
                shFile.setExecutable(true)
            }
        }

        // 2. Create coreutils symlinks (CRITICAL for proot-distro)
        val coreutilsFile = File(binDir, "coreutils")
        if (coreutilsFile.exists()) {
            val coreutilsCommands = listOf(
                "basename", "cat", "chmod", "chown", "cp", "cut", "date", "dd", "df", "dirname",
                "du", "echo", "env", "expr", "false", "fold", "head", "id", "install", "ln",
                "ls", "md5sum", "mkdir", "mktemp", "mv", "od", "paste", "printf", "pwd",
                "readlink", "realpath", "rm", "rmdir", "seq", "sha256sum", "sleep", "sort",
                "stat", "sync", "tail", "tee", "touch", "tr", "true", "uname", "uniq", "wc",
                "whoami", "yes"
            )
            coreutilsCommands.forEach { cmd ->
                val cmdFile = File(binDir, cmd)
                if (!cmdFile.exists()) {
                    try {
                        android.system.Os.symlink("coreutils", cmdFile.absolutePath)
                        android.util.Log.i("TerminalInstaller", "Created coreutils symlink: $cmd")
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        // 3. Create gawk -> awk symlink
        val gawkFile = File(binDir, "gawk")
        val awkFile = File(binDir, "awk")
        if (gawkFile.exists() && !awkFile.exists()) {
            try {
                android.system.Os.symlink("gawk", awkFile.absolutePath)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 4. FIX LIBRARY SYMLINKS - Delete broken symlinks first, then create correct ones
        // First, find all actual .so files (not symlinks)
        val actualLibs = mutableMapOf<String, String>()  // baseName -> actualFileName
        libDir.listFiles()?.forEach { file ->
            // Check if it's a real file (not a symlink)
            if (file.isFile && !isSymlink(file)) {
                val name = file.name
                // Match patterns like libz.so.1.2.12, libbz2.so.1.0.8, etc.
                val regex = Regex("(.+\\.so)\\.([0-9]+)\\.([0-9]+)\\.?([0-9]*)")
                val match = regex.matchEntire(name)
                if (match != null) {
                    val baseSo = match.groupValues[1]  // libz.so
                    val majorVersion = match.groupValues[2]  // 1
                    val minorVersion = match.groupValues[3]  // 2 or 0
                    val soMajor = "$baseSo.$majorVersion"  // libz.so.1
                    val soMajorMinor = "$baseSo.$majorVersion.$minorVersion"  // libz.so.1.2

                    // Store ALL mappings including major.minor (needed for libbz2.so.1.0)
                    actualLibs[soMajorMinor] = name  // libz.so.1.2 -> libz.so.1.2.12
                    actualLibs[soMajor] = name       // libz.so.1 -> libz.so.1.2.12
                    actualLibs[baseSo] = name        // libz.so -> libz.so.1.2.12
                }
            }
        }

        // Now create/fix symlinks
        actualLibs.forEach { (linkName, targetName) ->
            // Skip if linkName == targetName (would delete the original file!)
            if (linkName == targetName) return@forEach

            val linkFile = File(libDir, linkName)
            try {
                // Delete existing (possibly broken) symlink, but NOT if it's the actual file
                if (isSymlink(linkFile)) {
                    linkFile.delete()
                } else if (linkFile.exists()) {
                    // It's a real file, don't delete
                    return@forEach
                }
                android.system.Os.symlink(targetName, linkFile.absolutePath)
                android.util.Log.i("TerminalInstaller", "Fixed lib symlink: $linkName -> $targetName")
            } catch (e: Exception) {
                // Fallback: copy the file
                try {
                    File(libDir, targetName).copyTo(linkFile, overwrite = true)
                } catch (e2: Exception) {
                    android.util.Log.e("TerminalInstaller", "Failed to create symlink $linkName: ${e.message}")
                }
            }
        }

        // 5. Create fake 'file' command (needed by proot-distro)
        val fileCmd = File(binDir, "file")
        if (!fileCmd.exists()) {
            try {
                fileCmd.writeText("#!/data/data/com.termux/files/usr/bin/sh\necho \"application/octet-stream\"\n")
                fileCmd.setExecutable(true, false)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 6. FIX APT HTTPS - Copy http method as https (SSL libs not available in bootstrap)
        val httpMethod = File(aptDir, "http")
        val httpsMethod = File(aptDir, "https")
        if (httpMethod.exists() && !httpsMethod.exists()) {
            try {
                httpMethod.copyTo(httpsMethod, overwrite = true)
                httpsMethod.setExecutable(true, false)
                android.util.Log.i("TerminalInstaller", "Created https method from http")
            } catch (e: Exception) {
                android.util.Log.e("TerminalInstaller", "Failed to create https method: ${e.message}")
            }
        }

        // 7. FIX APT SOURCES - Change https to http in sources.list
        val sourcesListFile = File(etcDir, "apt/sources.list")
        if (sourcesListFile.exists()) {
            try {
                var content = sourcesListFile.readText()
                if (content.contains("https://")) {
                    content = content.replace("https://", "http://")
                    sourcesListFile.writeText(content)
                    android.util.Log.i("TerminalInstaller", "Fixed sources.list to use http")
                }
            } catch (e: Exception) {
                android.util.Log.e("TerminalInstaller", "Failed to fix sources.list: ${e.message}")
            }
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val canonicalPath = file.canonicalPath
            val absolutePath = file.absolutePath
            canonicalPath != absolutePath
        } catch (e: Exception) {
            false
        }
    }

    private fun installProotDistro(context: Context, prefixPath: String) {
        val binDir = File(prefixPath, "bin")
        val etcDir = File(prefixPath, "etc/proot-distro")
        etcDir.mkdirs()

        // Proot binary URL for aarch64 (most Android devices)
        val arch = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            else -> "aarch64"
        }

        // Copy proot binary from assets (bundled with APK)
        val prootFile = File(binDir, "proot")
        if (!prootFile.exists()) {
            try {
                // proot is bundled as asset for reliability
                val assetName = "proot-$arch"
                Log.i(TAG, "Copying proot from assets: $assetName")

                context.assets.open(assetName).use { input ->
                    FileOutputStream(prootFile).use { output ->
                        input.copyTo(output)
                    }
                }
                prootFile.setExecutable(true, false)
                prootFile.setReadable(true, false)
                Log.i(TAG, "Installed proot from assets: ${prootFile.length()} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install proot from assets: ${e.message}", e)

                // Fallback: try to download from Termux packages
                try {
                    val prootDebUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-68_$arch.deb"
                    val prootDeb = File(binDir.parentFile, "proot.deb")
                    Log.i(TAG, "Fallback: downloading proot from $prootDebUrl")
                    downloadFile(prootDebUrl, prootDeb) { }
                    extractProotFromDeb(prootDeb, prootFile, binDir)
                    prootDeb.delete()
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to download proot fallback: ${e2.message}")
                }
            }
        }

        // Download proot-distro script
        val prootDistroFile = File(binDir, "proot-distro")
        if (!prootDistroFile.exists()) {
            try {
                val prootDistroUrl = "https://raw.githubusercontent.com/termux/proot-distro/master/proot-distro.sh"
                downloadFile(prootDistroUrl, prootDistroFile) { }

                // Fix template variables in proot-distro
                var content = prootDistroFile.readText()
                content = content.replace("@TERMUX_PREFIX@", "/data/data/com.termux/files/usr")
                content = content.replace("@TERMUX_HOME@", "/data/data/com.termux/files/home")
                content = content.replace("@TERMUX_APP_PACKAGE@", "com.termux")
                prootDistroFile.writeText(content)
                prootDistroFile.setExecutable(true, false)
                android.util.Log.i("TerminalInstaller", "Downloaded proot-distro script")
            } catch (e: Exception) {
                android.util.Log.e("TerminalInstaller", "Failed to download proot-distro: ${e.message}")
            }
        }

        // Download Ubuntu plugin
        val ubuntuPluginFile = File(etcDir, "ubuntu.sh")
        if (!ubuntuPluginFile.exists()) {
            try {
                val ubuntuPluginUrl = "https://raw.githubusercontent.com/termux/proot-distro/master/distro-plugins/ubuntu.sh"
                downloadFile(ubuntuPluginUrl, ubuntuPluginFile) { }
                android.util.Log.i("TerminalInstaller", "Downloaded Ubuntu plugin")
            } catch (e: Exception) {
                android.util.Log.e("TerminalInstaller", "Failed to download Ubuntu plugin: ${e.message}")
            }
        }

        // Create required directories for proot-distro
        File("$prefixPath/var/lib/proot-distro/installed-rootfs").mkdirs()
        File("$prefixPath/var/lib/proot-distro/dlcache").mkdirs()
        File("$prefixPath/tmp").mkdirs()
    }

    private fun fixTermuxPaths(prefixPath: String) {
        val oldPath = "/data/data/com.termux/files"
        val newPath = "/data/data/com.termux/files"

        // Fix profile files in etc/
        val etcDir = File(prefixPath, "etc")
        val filesToFix = listOf("profile", "bash.bashrc", "termux-login.sh")

        filesToFix.forEach { fileName ->
            val file = File(etcDir, fileName)
            if (file.exists()) {
                try {
                    val content = file.readText()
                    val fixed = content.replace(oldPath, newPath)
                    file.writeText(fixed)
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
                    val fixed = content.replace(oldPath, newPath)
                    file.writeText(fixed)
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }
    }

    private fun copySetupScript(context: Context, homePath: String) {
        // Copy naenwa-setup.sh
        try {
            context.assets.open("naenwa-setup.sh").use { input ->
                val scriptFile = File(homePath, "naenwa-setup.sh")
                FileOutputStream(scriptFile).use { output ->
                    input.copyTo(output)
                }
                scriptFile.setExecutable(true, false)
                scriptFile.setReadable(true, false)
            }
        } catch (e: Exception) {
            // Script might not exist in assets, that's ok
        }

        // Copy naenwa-startup.sh (terminal entry point)
        try {
            context.assets.open("naenwa-startup.sh").use { input ->
                val scriptFile = File(homePath, "naenwa-startup.sh")
                FileOutputStream(scriptFile).use { output ->
                    input.copyTo(output)
                }
                scriptFile.setExecutable(true, false)
                scriptFile.setReadable(true, false)
            }
        } catch (e: Exception) {
            // Script might not exist in assets, that's ok
        }
    }

    private fun createTermuxCompatSymlink(context: Context) {
        // Termux bootstrap has hardcoded paths to /data/data/com.termux/files
        // Create a symlink from our app's files dir to make it work
        try {
            val termuxFilesDir = File("/data/data/com.termux/files")
            val ourFilesDir = context.filesDir

            // We can't create /data/data/com.termux, but we can set environment
            // to point our paths. The real fix is in environment variables.
            // This function is a placeholder for future improvements.
        } catch (e: Exception) {
            // Ignore - this is expected to fail
        }
    }

    private fun setPermissions(prefixPath: String) {
        // Set executable permission on bin files (for all users)
        val binDir = File(prefixPath, "bin")
        binDir.listFiles()?.forEach { file ->
            file.setExecutable(true, false)  // executable for all
            file.setReadable(true, false)    // readable for all
        }

        // Set executable on libexec files
        val libexecDir = File(prefixPath, "libexec")
        libexecDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }

        // Set executable on apt methods (CRITICAL for pkg to work)
        val aptMethodsDir = File(prefixPath, "lib/apt/methods")
        aptMethodsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }

        // Set readable on lib files
        val libDir = File(prefixPath, "lib")
        libDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.setReadable(true, false)
            }
        }
    }
}
