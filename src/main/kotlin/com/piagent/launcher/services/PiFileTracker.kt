package com.piagent.launcher.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.piagent.launcher.settings.PiSettings
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Applies file changes reported by the pi IDE bridge (`pi-ide-bridge.ts`).
 *
 * Only files pi itself announced as modified reach this service, so unlike a VFS listener it can
 * never pick up the IDE's own writes. Editor work is posted to the EDT without a write action.
 */
@Service(Service.Level.PROJECT)
class PiFileTracker(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiFileTracker::class.java)
    private val pendingOpen = ConcurrentHashMap.newKeySet<String>()
    private val flushScheduled = AtomicBoolean(false)

    /**
     * Changed-file counters per pi session. Several Pi instances may run at once (or several IDE
     * windows may report into the same project), so each session settles only its own count.
     */
    private val modifiedBySession = ConcurrentHashMap<String, AtomicInteger>()

    /** Absolute, slash-separated paths reported by the bridge for one pi session. */
    fun onFiles(session: String?, paths: List<String>) {
        val settings = PiSettings.getInstance().state
        if (!settings.autoOpenFiles && !settings.showNotifications) return
        val key = session ?: DEFAULT_SESSION

        var queued = false
        for (path in paths) {
            if (!isTrackedPath(path)) continue
            if (settings.showNotifications) {
                modifiedBySession.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
            }
            if (settings.autoOpenFiles && pendingOpen.add(path)) queued = true
        }
        if (queued) scheduleFlush()
    }

    /** One pi run finished: report how many project files it changed. */
    fun onSettled(session: String?) {
        val count = modifiedBySession.remove(session ?: DEFAULT_SESSION)?.get() ?: 0
        if (count <= 0) return
        if (!PiSettings.getInstance().state.showNotifications) return

        val message = if (count == 1) "Pi modified 1 file" else "Pi modified $count files"
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Pi Agent")
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project)
        }
    }

    /** Projects sources only; IDE-internal and generated folders are ignored. */
    private fun isTrackedPath(path: String): Boolean {
        val root = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return false
        val normalized = path.replace('\\', '/')
        if (normalized != root && !normalized.startsWith("$root/")) return false
        return normalized.removePrefix("$root/").split('/').none { it in IGNORED_DIRECTORIES }
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            flushScheduled.set(false)
            flushPendingOpens()
        }
    }

    private fun flushPendingOpens() {
        if (project.isDisposed || pendingOpen.isEmpty()) return
        val batch = ArrayList(pendingOpen)
        pendingOpen.removeAll(batch.toSet())

        // Resolving or refreshing VFS entries does file I/O, so it stays off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = batch.mapNotNull { path ->
                try {
                    VfsUtil.findFile(Path.of(path), true)
                } catch (e: Exception) {
                    logger.info("Skipping $path: ${e.message}")
                    null
                }
            }
            if (files.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                openInEditor(files)
            }
        }
    }

    private fun openInEditor(files: List<VirtualFile>) {
        val editorManager = try {
            FileEditorManager.getInstance(project)
        } catch (e: Exception) {
            logger.warn("Editor manager unavailable: ${e.message}")
            return
        }

        var opened = 0
        for (file in files) {
            if (opened >= MAX_OPEN_PER_BATCH) break
            if (!file.isValid || editorManager.isFileOpen(file)) continue
            try {
                editorManager.openFile(file, false)
                opened++
            } catch (e: Exception) {
                logger.warn("Failed to open ${file.path}: ${e.message}")
            }
        }
    }

    override fun dispose() {
        pendingOpen.clear()
        modifiedBySession.clear()
    }

    companion object {
        fun getInstance(project: Project): PiFileTracker = project.service()

        /** Folders whose changes must not pop open editor tabs. */
        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".idea", ".gradle", ".run", ".venv", ".next",
            "build", "out", "target", "dist", "node_modules", "__pycache__"
        )

        private const val MAX_OPEN_PER_BATCH = 10

        /** Bucket for events from a pi build that does not report a session id. */
        private const val DEFAULT_SESSION = "default"
    }
}
