package com.piagent.launcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Loopback endpoint that the bundled pi extension (`pi-ide-bridge.ts`) reports to.
 *
 * pi is started with `--ide-bridge=<url>`; the extension turns every file-modifying tool call into
 * one POST with a tiny line protocol:
 *
 * ```
 * v=1
 * tool=edit
 * cwd=C:/work/project
 * file=src/Main.kt
 * ```
 *
 * and reports `settled=1` when the agent run finishes.
 *
 * The server binds 127.0.0.1 on a random port with a random token in the path, so nothing except
 * the pi process we launched can reach it. Event handling only parses and forwards to
 * [PiFileTracker]; no editor is touched from the HTTP thread.
 */
@Service(Service.Level.APP)
class PiBridgeServer : Disposable {

    private val logger = Logger.getInstance(PiBridgeServer::class.java)
    private val lock = Any()

    @Volatile
    private var bridgeUrl: String? = null
    private var server: HttpServer? = null

    /** URL to pass as `--ide-bridge=`, starting the server on first use. */
    fun url(): String? {
        bridgeUrl?.let { return it }
        synchronized(lock) {
            bridgeUrl?.let { return it }
            return try {
                val http = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
                val token = UUID.randomUUID().toString().replace("-", "")
                val path = "/$token"
                http.createContext(path) { exchange -> handle(exchange) }
                http.executor = Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "pi-ide-bridge").apply { isDaemon = true }
                }
                http.start()
                server = http

                val url = "http://$LOOPBACK:${http.address.port}$path"
                bridgeUrl = url
                logger.info("Pi IDE bridge listening on $url")
                url
            } catch (e: Exception) {
                logger.warn("Failed to start the Pi IDE bridge: ${e.message}")
                null
            }
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405)
                return
            }
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            respond(exchange, 200)
            dispatch(body)
        } catch (e: Exception) {
            logger.warn("Failed to handle a Pi bridge event: ${e.message}")
            runCatching { respond(exchange, 500) }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun dispatch(body: String) {
        var cwd: String? = null
        var session: String? = null
        var settled = false
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("cwd=") -> cwd = line.removePrefix("cwd=").replace('\\', '/')
                line.startsWith("session=") -> session = line.removePrefix("session=")
                line == "settled=1" -> settled = true
            }
        }

        // Relative paths are resolved against the cwd reported by pi, so parse it first.
        val files = ArrayList<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("file=")) resolve(line.removePrefix("file="), cwd)?.let { files.add(it) }
        }
        if (files.isEmpty() && !settled) return
        val project = projectFor(files.firstOrNull() ?: cwd) ?: run {
            logger.info("Ignoring Pi bridge event outside any open project: ${files.firstOrNull()}")
            return
        }
        if (project.isDisposed) return

        val tracker = project.service<PiFileTracker>()
        if (files.isNotEmpty()) tracker.onFiles(session, files)
        if (settled) tracker.onSettled(session)
    }

    /** pi may report paths relative to its working directory. */
    private fun resolve(file: String, cwd: String?): String? {
        val normalized = file.replace('\\', '/')
        if (normalized.isBlank()) return null
        val path = try {
            Path.of(normalized)
        } catch (e: Exception) {
            logger.info("Ignoring unusable path from Pi: $normalized")
            return null
        }
        if (path.isAbsolute) return path.normalize().toString().replace('\\', '/')
        if (cwd == null) return null
        return try {
            Path.of(cwd).resolve(normalized).normalize().toString().replace('\\', '/')
        } catch (e: Exception) {
            null
        }
    }

    /** Longest matching project root wins (nested projects). */
    private fun projectFor(path: String?): Project? {
        if (path.isNullOrBlank()) return null
        val normalized = path.replace('\\', '/')
        return ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .mapNotNull { project ->
                project.basePath?.replace('\\', '/')?.trimEnd('/')?.let { root -> project to root }
            }
            .filter { (_, root) -> normalized == root || normalized.startsWith("$root/") }
            .maxByOrNull { (_, root) -> root.length }
            ?.first
    }

    override fun dispose() {
        synchronized(lock) {
            server?.stop(0)
            server = null
            bridgeUrl = null
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        fun getInstance(): PiBridgeServer = service()
    }
}
