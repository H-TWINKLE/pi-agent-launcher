package com.piagent.launcher.services

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs the bundled pi extension (`bridge/pi-ide-bridge.ts`) into pi's user extension
 * directory so pi discovers it without extra command-line flags.
 *
 * Location: `<agent-dir>/extensions/pi-ide-bridge.ts`, where `<agent-dir>` honours
 * `PI_CODING_AGENT_DIR` and defaults to `~/.pi/agent`.
 *
 * Updates are version-aware: a file carrying a newer `bridge-version` marker is never replaced
 * (the user or a newer plugin build may have installed it), an equal version is left untouched,
 * and a file without our marker is never overwritten.
 */
internal object PiBridgeInstaller {

    private const val RESOURCE = "/bridge/pi-ide-bridge.ts"
    private const val FILE_NAME = "pi-ide-bridge.ts"
    private const val VERSION_PATTERN = """bridge-version:\s*([0-9A-Za-z.\-]+)"""

    /** Fallback when the bundled resource has no marker; keep in sync with the resource file. */
    private const val BUNDLED_VERSION = "1.1.0"

    private val logger = Logger.getInstance(PiBridgeInstaller::class.java)
    private val versionRegex = Regex(VERSION_PATTERN)

    /** Ensures the extension is installed and returns its absolute path, or null when unavailable. */
    fun install(): Path? = try {
        val content = PiBridgeInstaller::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
        if (content == null) {
            logger.warn("Bundled pi bridge extension is missing from the plugin resources")
            null
        } else {
            val target = extensionsDir().resolve(FILE_NAME)
            writeIfNeeded(target, content)
            if (Files.exists(target)) target else null
        }
    } catch (e: Exception) {
        logger.warn("Failed to install the pi bridge extension: ${e.message}")
        null
    }

    /** pi's user extension directory (respects PI_CODING_AGENT_DIR). */
    private fun extensionsDir(): Path {
        val agentDir = System.getenv("PI_CODING_AGENT_DIR")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".pi", "agent").toString()
        return Path.of(agentDir, "extensions")
    }

    private fun writeIfNeeded(target: Path, content: ByteArray) {
        val bundledVersion = readVersion(String(content, Charsets.UTF_8)) ?: BUNDLED_VERSION

        if (Files.exists(target)) {
            val installedText = try {
                Files.readString(target)
            } catch (e: Exception) {
                logger.warn("Cannot read the installed pi bridge extension at $target: ${e.message}")
                return
            }

            val installedVersion = readVersion(installedText)
            when {
                installedVersion == null -> {
                    logger.warn("$target has no bridge version marker; leaving it untouched")
                    return
                }
                compareVersions(installedVersion, bundledVersion) >= 0 -> {
                    logger.info("pi bridge extension $installedVersion is current (bundled $bundledVersion)")
                    return
                }
                else -> logger.info("Updating pi bridge extension $installedVersion -> $bundledVersion")
            }
        }

        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "$FILE_NAME.", ".tmp")
        try {
            Files.write(tmp, content)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Some file systems refuse ATOMIC_MOVE; a plain replace is still safe here.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            logger.info("Installed pi bridge extension $bundledVersion at $target")
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun readVersion(text: String): String? =
        versionRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Numeric dotted comparison; missing segments count as 0, so "1.2" == "1.2.0". */
    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }
}
