package com.naenwa.remote.terminal

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.URL
import java.util.zip.ZipInputStream

object TerminalInstaller {

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

    private fun getPrefixPath(context: Context) = "${context.filesDir.absolutePath}/usr"
    private fun getHomePath(context: Context) = "${context.filesDir.absolutePath}/home"
    private fun getStagingPath(context: Context) = "${context.filesDir.absolutePath}/staging"

    suspend fun install(
        context: Context,
        onProgress: (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefixPath = getPrefixPath(context)
            val homePath = getHomePath(context)
            val stagingPath = getStagingPath(context)

            // Clean up
            File(prefixPath).deleteRecursively()
            File(stagingPath).deleteRecursively()
            File(stagingPath).mkdirs()
            File(homePath).mkdirs()

            onProgress(5, "Downloading bootstrap...")

            // Download bootstrap
            val bootstrapZip = File(stagingPath, "bootstrap.zip")
            downloadFile(BOOTSTRAP_URL, bootstrapZip) { progress ->
                onProgress(5 + (progress * 0.4).toInt(), "Downloading bootstrap... $progress%")
            }

            onProgress(45, "Extracting bootstrap...")

            // Extract bootstrap
            extractBootstrap(bootstrapZip, prefixPath) { progress ->
                onProgress(45 + (progress * 0.4).toInt(), "Extracting... $progress%")
            }

            onProgress(80, "Setting up symlinks...")

            // Setup symlinks
            setupSymlinks(prefixPath)

            onProgress(85, "Installing proot-distro...")

            // Install proot and proot-distro
            installProotDistro(prefixPath)

            onProgress(92, "Copying setup script...")

            // Copy naenwa-setup.sh from assets
            copySetupScript(context, homePath)

            onProgress(92, "Fixing paths...")

            // Fix hardcoded termux paths in profile files
            fixTermuxPaths(prefixPath)

            onProgress(95, "Finalizing...")

            // Set permissions
            setPermissions(prefixPath)

            // Cleanup
            File(stagingPath).deleteRecursively()

            onProgress(100, "Installation complete!")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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

        // 4. Create library symlinks for ALL versioned formats
        // Handles: libz.so.1.2.12 -> libz.so.1 -> libz.so
        libDir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val name = file.name
            if (name.contains(".so.")) {
                // Pattern: libX.so.1.2.3 or libX.so.1.2 or libX.so.1
                val regex = Regex("(.+\\.so)\\.([0-9]+)(\\..*)?")
                val match = regex.matchEntire(name)
                if (match != null) {
                    val baseSo = match.groupValues[1]  // libX.so
                    val majorVersion = match.groupValues[2]  // 1

                    // Create libX.so.1 symlink
                    val soMajor = "$baseSo.$majorVersion"
                    if (soMajor != name) {
                        val soMajorFile = File(libDir, soMajor)
                        if (!soMajorFile.exists()) {
                            try {
                                android.system.Os.symlink(name, soMajorFile.absolutePath)
                                android.util.Log.i("TerminalInstaller", "Created lib symlink: $soMajor -> $name")
                            } catch (e: Exception) {
                                file.copyTo(soMajorFile, overwrite = true)
                            }
                        }
                    }

                    // Create libX.so symlink
                    val soFile = File(libDir, baseSo)
                    if (!soFile.exists()) {
                        try {
                            android.system.Os.symlink(name, soFile.absolutePath)
                            android.util.Log.i("TerminalInstaller", "Created lib symlink: $baseSo -> $name")
                        } catch (e: Exception) {
                            file.copyTo(soFile, overwrite = true)
                        }
                    }
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
    }

    private fun installProotDistro(prefixPath: String) {
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

        // Download proot binary from Termux packages
        val prootFile = File(binDir, "proot")
        if (!prootFile.exists()) {
            try {
                // Use proot from termux-packages release (static build)
                val prootUrl = "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-$arch-static"
                downloadFile(prootUrl, prootFile) { }
                prootFile.setExecutable(true, false)
                android.util.Log.i("TerminalInstaller", "Downloaded proot binary")
            } catch (e: Exception) {
                android.util.Log.e("TerminalInstaller", "Failed to download proot: ${e.message}")
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

        // Set readable on lib files
        val libDir = File(prefixPath, "lib")
        libDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.setReadable(true, false)
            }
        }
    }
}
