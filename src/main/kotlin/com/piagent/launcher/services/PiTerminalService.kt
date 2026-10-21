package com.piagent.launcher.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.piagent.launcher.settings.PiSettings
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Manages the Pi terminal session lifecycle.
 * Creates a "Pi" tab inside the IDE's Terminal tool window.
 */
@Service(Service.Level.PROJECT)
class PiTerminalService(private val project: Project) {

    private val logger = Logger.getInstance(PiTerminalService::class.java)
    private val terminalComponent = AtomicReference<JComponent?>(null)
    private val terminalContent = AtomicReference<Content?>(null)
    /** The widget created by this plugin, used to tell when its session is really up. */
    private val terminalWidget = AtomicReference<Any?>(null)

    /** Cached result of the (expensive) process table scan, refreshed off the EDT. */
    private val piProcessRunning = AtomicBoolean(false)
    private val processScanInFlight = AtomicBoolean(false)
    /** Callers waiting for the running scan, guarded by [processConsumersLock]. */
    private val processConsumers = ArrayList<(Boolean) -> Unit>()
    private val processConsumersLock = Any()

    @Volatile
    private var lastProcessScanAt = 0L
    /** Returns true when the text was accepted by the terminal. */
    private var sendTextFn: ((String, Boolean) -> Boolean)? = null
    private var isInitialized = false

    /** Input queued while the Pi tab is still booting: (text, execute). */
    private val pendingInput = AtomicReference<Pair<String, Boolean>?>(null)

    /** Interface implemented by terminal widget components (resolved lazily, engine-independent). */
    private val terminalWidgetClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.terminal.ui.TerminalWidget")
        } catch (_: Throwable) {
            null
        }
    }

    /** New terminal engine view (2025.3+): not a Swing component itself, exposes sendText(). */
    private val terminalViewClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.terminal.frontend.view.TerminalView")
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Launch Pi as a new tab in the IDE's Terminal tool window.
     * Only one Pi session at a time — if already running, focus it.
     */
    fun launch() {
        if (isInitialized && isTerminalAlive()) {
            focusPiTab()
            return
        }

        if (isInitialized) {
            reset()
        }

        try {
            showProcessNotification("Starting Pi...", NotificationType.INFORMATION)
            val workingDir = project.basePath ?: System.getProperty("user.home")
            createTerminalTab(workingDir)
            isInitialized = true
            PiStatusWidget.update(project)
            startPiWithDelay()
        } catch (e: Exception) {
            logger.error("Failed to launch Pi terminal", e)
            showProcessNotification("Failed to start Pi: ${e.message}", NotificationType.ERROR)
        }
    }

    /**
     * Check if the Pi terminal tab is still alive.
     */
    private fun isTerminalAlive(): Boolean {
        terminalContent.get()?.let { content ->
            return content.isValid && content.manager != null
        }
        val component = terminalComponent.get() ?: return false
        // Check if component is still attached to a parent (tab exists even if not focused)
        return component.parent != null
    }

    /**
     * Create a new tab named "Pi" in the Terminal tool window.
     *
     * Uses the classic terminal engine (same as 0.1.4). The new TerminalView engine
     * registers a focus-save listener that can deadlock WebStorm 2026.2 together
     * with the platform write lock, so it is only used for sending to user terminals.
     */
    private fun createTerminalTab(workingDir: String) {
        try {
            createLegacyTab(workingDir)
            logger.info("Pi terminal tab created (classic terminal engine)")
        } catch (e: Exception) {
            logger.error("Failed to create terminal tab", e)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacyTab(workingDir: String) {
        // Use TerminalToolWindowManager to create a tab in the Terminal window
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val getInstanceMethod = managerClass.getMethod("getInstance", Project::class.java)
        val manager = getInstanceMethod.invoke(null, project)

        // createLocalShellWidget(workingDir, tabName, requestFocus) with focus disabled.
        val widget = try {
            managerClass.getMethod(
                "createLocalShellWidget",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(manager, workingDir, TAB_NAME, false)
        } catch (_: NoSuchMethodException) {
            managerClass.getMethod(
                "createLocalShellWidget",
                String::class.java,
                String::class.java
            ).invoke(manager, workingDir, TAB_NAME)
        }

        // Get component
        val component = widget.javaClass.getMethod("getComponent").invoke(widget) as JComponent
        terminalComponent.set(component)
        terminalWidget.set(widget)
        terminalContent.set(findContentByName(TAB_NAME))

        // Store sendText function for the terminal created by this plugin
        sendTextFn = { text, execute -> sendToWidget(widget, text, execute) }

        // Select the Pi tab and bring the Terminal tool window up. A fresh
        // createLocalShellWidget does not show the window by itself, so a closed Terminal
        // window would stay closed and the user would see nothing happen.
        showPiTab()

        // Monitor terminal process for unexpected exit
        monitorProcess(widget)
    }

    private fun findContentByName(name: String): Content? {
        return try {
            val toolWindow = terminalToolWindow() ?: return null
            val contentManager = contentManagerIfCreated(toolWindow) ?: return null
            contentManager.contents.firstOrNull { it.displayName == name }
        } catch (_: Exception) {
            null
        }
    }

    private fun terminalToolWindow(): ToolWindow? {
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.let { return it }
        // Some IDE versions register the tool window through the terminal plugin only.
        return try {
            val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            managerClass.getMethod("getToolWindow").invoke(manager) as? ToolWindow
        } catch (e: Exception) {
            logger.warn("Failed to resolve the Terminal tool window: ${e.message}")
            null
        }
    }

    /** Show the Terminal tool window and select the Pi tab created by this plugin. */
    private fun focusPiTab() {
        showPiTab()
    }

    /**
     * Bring the Terminal tool window up with the Pi tab selected and focused.
     *
     * `ToolWindow.show()` is not enough here: it neither activates an already open window nor
     * surfaces one that the terminal plugin just created without showing. `activate` does both,
     * which is also what makes the window appear when the user clicks the toolbar icon while the
     * Terminal window is closed.
     */
    private fun showPiTab() {
        val toolWindow = terminalToolWindow() ?: return
        // When the window is already open, select first so activation does not briefly focus
        // whichever tab was selected before.
        selectPiTab(toolWindow)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                // The callback runs after the window is activated, so the content manager and
                // the newly created tab exist by then.
                toolWindow.activate({
                    selectPiTab(toolWindow)
                    terminalComponent.get()?.requestFocusInWindow()
                }, true)
            } catch (e: Exception) {
                logger.warn("Failed to show the Terminal window for Pi: ${e.message}")
            }
        }
    }

    /** Select the Pi tab; a no-op while the Terminal window content is not created yet. */
    private fun selectPiTab(toolWindow: ToolWindow) {
        try {
            val content = terminalContent.get()?.takeIf { it.isValid }
                ?: terminalWidget.get()?.let { contentForWidget(it) }
                ?: findContentByName(TAB_NAME)
                ?: return
            terminalContent.set(content)
            toolWindow.contentManager.setSelectedContent(content, true)
        } catch (e: Exception) {
            logger.warn("Failed to select the Pi tab: ${e.message}")
        }
    }

    /**
     * Content hosting a terminal widget (`TerminalToolWindowManager.getContainer(widget).content`).
     * More reliable than matching the tab title, which needs the content manager to exist already.
     */
    private fun contentForWidget(widget: Any): Content? = try {
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        val getContainer = managerClass.methods.firstOrNull { it.name == "getContainer" && it.parameterCount == 1 }
        val container = getContainer?.invoke(manager, widget)
        container?.javaClass?.methods
            ?.firstOrNull { it.name == "getContent" && it.parameterCount == 0 }
            ?.invoke(container) as? Content
    } catch (_: Exception) {
        null
    }

    /** Bring the Terminal tool window up; when [focusWidget] is given it also gets the focus. */
    private fun showTerminalWindow(focusWidget: Any? = null) {
        val toolWindow = terminalToolWindow() ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                toolWindow.activate({ if (focusWidget != null) requestFocus(focusWidget) }, true)
            } catch (e: Exception) {
                logger.warn("Failed to activate the Terminal window: ${e.message}")
            }
        }
    }

    /**
     * Send the launch command once the shell session is really up.
     *
     * The widget exists right after creation, but the PTY/shell process is started
     * asynchronously afterwards. Injecting before that loses the text at best; on the new
     * terminal engine the injection can also wait for the session on the EDT and freeze the IDE.
     */
    private fun startPiWithDelay() {
        val command = buildPiCommand()

        // Poll until the terminal owns a live session, then send the command.
        var attempts = 0
        val timer = object : Timer(300, null) {
            init {
                isRepeats = true
                addActionListener {
                    attempts++
                    val fn = sendTextFn
                    if (fn != null && isTerminalReady(terminalWidget.get(), attempts) && fn(command, true)) {
                        stop()
                        schedulePendingInputFlush()
                    } else if (attempts > 40) {
                        logger.warn("Terminal not ready after 12s; Pi was not started")
                        stop()
                    }
                }
            }
        }
        timer.start()
    }

    /**
     * True when the given widget already owns a live session.
     * Widget types that do not expose a connector fall back after a short grace period.
     */
    private fun isTerminalReady(widget: Any?, attempts: Int): Boolean {
        if (widget == null) return false
        if (terminalViewClass?.isInstance(widget) == true) {
            return isTerminalViewRunning(widget)
        }

        val connector = try {
            widget.javaClass.getMethod("getTtyConnector").invoke(widget)
        } catch (_: Exception) {
            null
        } ?: return attempts > READY_FALLBACK_ATTEMPTS

        return try {
            connector.javaClass.getMethod("isConnected").invoke(connector) as? Boolean ?: true
        } catch (_: Exception) {
            true
        }
    }

    /** Connection state of a widget's TTY; null when the widget can no longer be inspected. */
    private fun isTerminalConnected(widget: Any): Boolean? {
        val connector = try {
            widget.javaClass.getMethod("getTtyConnector").invoke(widget)
        } catch (_: Exception) {
            return null
        } ?: return null

        return try {
            connector.javaClass.getMethod("isConnected").invoke(connector) as? Boolean
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPiCommand(): String {
        val settings = PiSettings.getInstance().state
        return buildString {
            append(settings.piCommand)

            val model = if (settings.customModelId.isNotBlank()) {
                settings.customModelId
            } else if (settings.model != "Default") {
                settings.model
            } else null

            if (model != null) {
                append(" --model $model")
            }

            if (settings.thinkingLevel != "Default" && settings.thinkingLevel.isNotBlank()) {
                append(" --thinking ${settings.thinkingLevel}")
            }

            if (settings.disableThemes) {
                append(" --no-themes")
            }

            // The bridge flag goes before the user's extra arguments so a stray `--` in them
            // cannot turn it into a positional argument.
            append(buildBridgeArgs())

            if (settings.extraArgs.isNotBlank()) {
                append(" ")
                append(settings.extraArgs)
            }
        }
    }

    /**
     * Installs the bundled pi extension and returns the `--ide-bridge=` argument for it.
     *
     * The extension lives in pi's own extension directory and is inert unless this flag is passed,
     * so only sessions started from this IDE report file changes back.
     */
    private fun buildBridgeArgs(): String {
        val settings = PiSettings.getInstance().state
        if (!settings.autoOpenFiles && !settings.showNotifications) return ""

        val extension = PiBridgeInstaller.install() ?: return ""
        val url = PiBridgeServer.getInstance().url() ?: return ""
        logger.info("Pi IDE bridge ready: ${extension.toAbsolutePath()} -> $url")
        return " --ide-bridge=$url"
    }

    /** Replay queued reference text once the terminal is ready (retries while it boots). */
    private fun schedulePendingInputFlush(attempt: Int = 0) {
        if (pendingInput.get() == null) return
        if (attempt > 40) {
            logger.warn("Pi terminal never became ready; dropping queued input")
            pendingInput.set(null)
            return
        }
        val timer = Timer(500, null)
        timer.isRepeats = false
        timer.addActionListener {
            val pending = pendingInput.get() ?: return@addActionListener
            val fn = sendTextFn
            if (fn != null && fn(pending.first, pending.second)) {
                pendingInput.compareAndSet(pending, null)
                focusPiTab()
            } else {
                schedulePendingInputFlush(attempt + 1)
            }
        }
        timer.start()
    }

    /**
     * Send text to the active terminal and press Enter.
     */
    fun sendText(text: String) {
        val target = activeTerminalWidget()
        if (target != null) {
            if (sendToWidget(target, text, execute = true)) {
                showTerminalWindow(target)
            } else {
                logger.warn("Active terminal rejected injected text")
            }
            return
        }

        sendTextFn?.let { fn ->
            if (fn(text, true)) {
                focusPiTab()
                return
            }
            pendingInput.set(text to true)
            schedulePendingInputFlush()
            return
        }

        if (hasTerminalTabs()) {
            logger.warn("Cannot reach a Pi terminal of this IDE (terminal tabs exist but none is usable)")
            return
        }

        // Only start Pi when no Pi runs somewhere else; the check runs in the background.
        resolvePiProcessState { piRunning ->
            if (piRunning) {
                logger.warn("Pi already runs outside this IDE; cannot type into it")
                return@resolvePiProcessState
            }
            pendingInput.set(text to true)
            launch()
        }
    }

    /**
     * Insert text into the terminal input without executing.
     * Prefers the terminal tab that is currently active in the Terminal tool window,
     * so references also reach a Pi started outside the plugin.
     *
     * @return false when a terminal exists but rejected the text
     *         (the caller may fall back to the clipboard).
     */
    fun insertText(text: String): Boolean {
        val target = activeTerminalWidget()
        if (target != null) {
            if (sendToWidget(target, text, execute = false)) {
                showTerminalWindow(target)
                return true
            }
            logger.warn("Active terminal rejected injected text")
            return false
        }

        // The plugin-owned Pi tab exists but is not the active tab.
        sendTextFn?.let { fn ->
            if (fn(text, false)) {
                focusPiTab()
                return true
            }
            pendingInput.set(text to false)
            schedulePendingInputFlush()
            return true
        }

        // Never spawn a second Pi when this IDE already holds terminal tabs.
        if (hasTerminalTabs()) {
            logger.warn("Cannot reach a Pi terminal of this IDE (terminal tabs exist but none is usable)")
            return false
        }

        // No terminal at all: start Pi and replay the text once it is ready. The process check
        // runs in the background; a Pi from an external terminal is left untouched.
        resolvePiProcessState { piRunning ->
            if (piRunning) {
                logger.warn("Pi already runs outside this IDE; cannot type into it")
                return@resolvePiProcessState
            }
            pendingInput.set(text to false)
            launch()
        }
        return true
    }

    /**
     * Resolve the terminal widget the user is currently working in.
     * Split terminals: the focused widget wins; otherwise the selected tab is used.
     * Works for terminals created by any source, not just this plugin.
     */
    private fun activeTerminalWidget(): Any? {
        val toolWindow = terminalToolWindow() ?: return null
        val legacyWidgets = LinkedHashSet(terminalWidgets())
        val viewTabs = terminalViewTabs()

        // 1. Focused terminal (split panes), legacy or new engine.
        legacyWidgets.firstOrNull { hasFocus(it) }?.let { return it }
        viewTabs.firstNotNullOfOrNull { tab -> tabView(tab)?.takeIf { hasFocus(it) } }?.let { return it }

        val contentManager = contentManagerIfCreated(toolWindow)
        val selectedContent = contentManager?.selectedContent

        // 2. Selected tab.
        if (selectedContent != null) {
            // New-engine tab bound to the selected content
            viewTabs.firstOrNull { tabContent(it) === selectedContent }
                ?.let { tab -> tabView(tab)?.let { return it } }
            // Legacy content data / Swing tree
            terminalWidgetInContent(selectedContent)?.let { return it }

            val contentComponent = selectedContent.component
            legacyWidgets.firstOrNull { widget ->
                widgetComponent(widget)?.let { SwingUtilities.isDescendingFrom(it, contentComponent) } == true
            }?.let { return it }
            viewTabs.firstOrNull { tab ->
                val component = tabView(tab)?.let { widgetComponent(it) }
                component != null && SwingUtilities.isDescendingFrom(component, contentComponent)
            }?.let { tab -> tabView(tab)?.let { return it } }
        }

        // 3. New-engine tabs: selected flag, or the only open tab.
        viewTabs.firstOrNull { tabContent(it)?.isSelected == true }
            ?.let { tab -> tabView(tab)?.let { return it } }
        if (viewTabs.size == 1) return tabView(viewTabs.first())

        // 4. Legacy widgets of any tab.
        contentManager?.contents?.forEach { content ->
            findWidgetByContent(content)?.let { legacyWidgets.add(it) }
        }
        legacyWidgets.firstOrNull { hasFocus(it) }?.let { return it }
        if (legacyWidgets.size == 1) return legacyWidgets.first()

        // 5. Last resort: scan the visible tool window tree for a legacy terminal component.
        val visibleWidgets = findTerminalWidgetsIn(toolWindow.component)
        return visibleWidgets.firstOrNull { hasFocus(it) } ?: visibleWidgets.singleOrNull()
    }

    /** Tabs of the new terminal engine (2025.3+); empty on older IDEs. */
    private fun terminalViewTabs(): List<Any> = try {
        val managerClass = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        @Suppress("UNCHECKED_CAST")
        (managerClass.getMethod("getTabs").invoke(manager) as? Collection<Any>)?.toList().orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun tabContent(tab: Any): Content? = try {
        tab.javaClass.getMethod("getContent").invoke(tab) as? Content
    } catch (_: Exception) {
        null
    }

    private fun tabView(tab: Any): Any? = try {
        tab.javaClass.getMethod("getView").invoke(tab)
    } catch (_: Exception) {
        null
    }

    /** Terminal widget for one tab, independent of the engine's content user data. */
    private fun terminalWidgetInContent(content: Content): Any? {
        findWidgetByContent(content)?.let { return it }

        val components = findTerminalWidgetsIn(content.component)
        return components.firstOrNull { hasFocus(it) } ?: components.firstOrNull()
    }

    /** Depth-first search for terminal widgets inside a Swing component tree. */
    private fun findTerminalWidgetsIn(component: Component?, result: MutableList<Component> = mutableListOf()): List<Component> {
        if (component == null) return result
        if (isTerminalWidgetComponent(component)) result.add(component)
        if (component is Container) {
            component.components.forEach { findTerminalWidgetsIn(it, result) }
        }
        return result
    }

    /** Terminal widgets extend JComponent; class-name matching survives engine changes. */
    private fun isTerminalWidgetComponent(component: Component): Boolean {
        terminalWidgetClass?.let { if (it.isInstance(component)) return true }
        return generateSequence<Class<*>>(component.javaClass) { it.superclass }
            .any { clazz -> clazz.name.contains("TerminalWidget") }
    }

    /**
     * Content manager that does not lazily create the tool window content,
     * so a "Local" tab is not spawned just because we looked for a target terminal.
     */
    private fun contentManagerIfCreated(toolWindow: ToolWindow): ContentManager? {
        try {
            (toolWindow.javaClass.getMethod("getContentManagerIfCreated").invoke(toolWindow) as? ContentManager)
                ?.let { return it }
        } catch (e: Exception) {
            logger.info("getContentManagerIfCreated unavailable: ${e.message}")
        }
        // Visible tool windows are initialized already, so this does not create phantom tabs.
        return if (toolWindow.isVisible) toolWindow.contentManager else null
    }

    /** True when the Terminal tool window already holds tabs, even if no widget could be resolved. */
    private fun hasTerminalTabs(): Boolean {
        val toolWindow = terminalToolWindow() ?: return false
        val contentManager = contentManagerIfCreated(toolWindow) ?: return false
        return contentManager.contents.isNotEmpty()
    }

    /**
     * Resolve whether a Pi process exists somewhere else, without blocking the caller.
     *
     * The scan walks the whole process table and reads every command line, which takes seconds on
     * Windows, so it never runs on the EDT. A fresh cached value is delivered immediately; a stale
     * one triggers a background scan whose result reaches [consumer] later on the EDT.
     */
    private fun resolvePiProcessState(consumer: (Boolean) -> Unit) {
        val now = System.currentTimeMillis()
        if (lastProcessScanAt != 0L && now - lastProcessScanAt <= PROCESS_SCAN_CACHE_MS) {
            consumer(piProcessRunning.get())
            return
        }

        // A scan already running serves this request too, so no caller ever gets a stale answer.
        synchronized(processConsumersLock) { processConsumers.add(consumer) }
        if (!processScanInFlight.compareAndSet(false, true)) return

        lastProcessScanAt = now
        ApplicationManager.getApplication().executeOnPooledThread {
            val running = try {
                scanForPiProcess()
            } catch (e: Exception) {
                logger.warn("Failed to inspect running processes: ${e.message}")
                false
            } finally {
                processScanInFlight.set(false)
            }
            piProcessRunning.set(running)

            val consumers = synchronized(processConsumersLock) {
                ArrayList(processConsumers).also { processConsumers.clear() }
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                consumers.forEach { it(running) }
            }
        }
    }

    private fun scanForPiProcess(): Boolean = ProcessHandle.allProcesses().anyMatch { handle ->
        looksLikePiCommand(handle.info().commandLine().orElse(""))
    }

    private fun looksLikePiCommand(commandLine: String): Boolean {
        if (commandLine.isBlank()) return false
        val lower = commandLine.lowercase()
        // The npm-installed CLI runs as `node .../pi-coding-agent/...`.
        if (lower.contains("pi-coding-agent")) return true

        // Only the executable itself counts: matching any argument would hit unrelated processes.
        val executable = lower.split(' ', '\t', '"', '\'')
            .firstOrNull { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?: return false
        return executable in PI_EXECUTABLES
    }

    private fun findWidgetByContent(content: Content): Any? = try {
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        managerClass.getMethod("findWidgetByContent", Content::class.java).invoke(null, content)
    } catch (e: Exception) {
        logger.warn("Failed to resolve terminal widget for tab '${content.displayName}': ${e.message}")
        null
    }


    private fun terminalWidgets(): List<Any> = try {
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        // getTerminalWidgets() covers every terminal engine; getWidgets() is the legacy view.
        @Suppress("UNCHECKED_CAST")
        val widgets = try {
            managerClass.getMethod("getTerminalWidgets").invoke(manager) as? Collection<Any>
        } catch (_: NoSuchMethodException) {
            managerClass.getMethod("getWidgets").invoke(manager) as? Collection<Any>
        }
        widgets.orEmpty().toList()
    } catch (e: Exception) {
        logger.warn("Failed to list terminal widgets: ${e.message}")
        emptyList()
    }

    private fun hasFocus(target: Any): Boolean {
        try {
            if (target.javaClass.getMethod("hasFocus").invoke(target) as? Boolean == true) return true
        } catch (_: Exception) {
            // TerminalView has no hasFocus(); use its component below.
        }
        return widgetComponent(target)?.hasFocus() == true
    }

    private fun requestFocus(target: Any) {
        try {
            target.javaClass.getMethod("requestFocus").invoke(target)
            return
        } catch (_: Exception) {
            // Fall through to the component (new terminal engine views).
        }
        try {
            widgetComponent(target)?.requestFocusInWindow()
        } catch (e: Exception) {
            logger.warn("Failed to focus terminal: ${e.message}")
        }
    }

    /** Swing component behind a terminal widget/view, if any. */
    private fun widgetComponent(target: Any): Component? = try {
        (target as? Component) ?: target.javaClass.getMethod("getComponent").invoke(target) as? Component
    } catch (_: Exception) {
        null
    }

    /**
     * Reflection-based text injection so the plugin keeps working across terminal API changes.
     * The terminal plugin classes differ between IDE versions, hence no direct imports.
     */
    private fun sendToWidget(widget: Any, text: String, execute: Boolean): Boolean {
        // New terminal engine (2025.3+): TerminalView.sendText() / sendTextBuilder.
        val isTerminalView = terminalViewClass?.isInstance(widget) == true ||
            if (execute) hasMethod(widget, "createSendTextBuilder", 0) else hasMethod(widget, "sendText", 1)
        if (isTerminalView) {
            if (sendViaTerminalView(widget, text, execute)) {
                logger.info("Injected text via TerminalView")
                return true
            }
        }

        return try {
            if (execute) {
                widget.javaClass.getMethod("executeCommand", String::class.java)
                    .invoke(widget, text)
            } else {
                val starter = widget.javaClass.getMethod("getTerminalStarter").invoke(widget)
                    ?: throw IllegalStateException("terminal starter unavailable")
                starter.javaClass.getMethod(
                    "sendString",
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                ).invoke(starter, text, false)
            }
            logger.info("Injected text into ${widget.javaClass.name}")
            true
        } catch (e: Exception) {
            // The active terminal may not expose the JediTerm starter; write to the TTY directly.
            logger.warn("Terminal API injection failed (${e.message}); writing to the TTY directly")
            writeToTty(widget, if (execute) "$text\r" else text)
        }
    }

    /** Text injection for the new terminal engine view. */
    private fun sendViaTerminalView(view: Any, text: String, execute: Boolean): Boolean {
        if (!isTerminalViewRunning(view)) return false

        return try {
            val viewClass = terminalViewClass
                ?: Class.forName("com.intellij.terminal.frontend.view.TerminalView")
            if (execute) {
                val builder = viewClass.getMethod("createSendTextBuilder").invoke(view)
                val builderClass = Class.forName("org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder")
                builderClass.getMethod("shouldExecute").invoke(builder)
                builderClass.getMethod("send", String::class.java).invoke(builder, text)
            } else {
                viewClass.getMethod("sendText", String::class.java).invoke(view, text)
            }
            true
        } catch (e: Exception) {
            logger.warn("TerminalView injection failed: ${e.message}")
            false
        }
    }

    /** The new engine must have started its session before text can be sent. */
    private fun isTerminalViewRunning(view: Any): Boolean {
        return try {
            val stateFlow = view.javaClass.getMethod("getSessionState").invoke(view)
            val stateFlowClass = Class.forName("kotlinx.coroutines.flow.StateFlow")
            val state = stateFlowClass.getMethod("getValue").invoke(stateFlow)
            state != null && state.javaClass.simpleName.contains("Running", ignoreCase = true)
        } catch (_: Exception) {
            true
        }
    }

    private fun hasMethod(target: Any, name: String, parameterCount: Int): Boolean =
        target.javaClass.methods.any { it.name == name && it.parameterCount == parameterCount }

    /** Last-resort injection that bypasses the terminal UI layer. */
    private fun writeToTty(widget: Any, text: String): Boolean = try {
        val connector = widget.javaClass.getMethod("getTtyConnector").invoke(widget)
            ?: throw IllegalStateException("TTY connector unavailable")
        connector.javaClass.getMethod("write", String::class.java).invoke(connector, text)
        true
    } catch (e: Exception) {
        logger.warn("Failed to write to terminal TTY: ${e.message}")
        false
    }

    /**
     * Focus the terminal widget.
     */
    fun focus() {
        focusPiTab()
    }

    fun isReady(): Boolean = isInitialized

    /**
     * Reset state so terminal can be re-launched.
     */
    fun reset() {
        terminalComponent.set(null)
        terminalWidget.set(null)
        terminalContent.set(null)
        sendTextFn = null
        isInitialized = false
        pendingInput.set(null)
        PiStatusWidget.update(project)
        logger.info("Pi terminal reset")
    }

    private fun showProcessNotification(message: String, type: NotificationType) {
        if (!PiSettings.getInstance().state.showProcessNotifications) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(message, type)
            .notify(project)
    }

    /**
     * Monitor the terminal process. If it exits unexpectedly, offer to restart.
     */
    private fun monitorProcess(widget: Any) {
        var disconnectedChecks = 0
        val checkTimer = object : Timer(2000, null) {
            init {
                isRepeats = true
                addActionListener {
                    val connected = isTerminalConnected(widget)
                    if (connected == null) {
                        // The widget is gone (tab closed/disposed), stop monitoring.
                        stop()
                        return@addActionListener
                    }
                    if (connected) {
                        disconnectedChecks = 0
                        return@addActionListener
                    }
                    // One miss happens while a tab is detached or a shell restarts,
                    // so only a repeat means Pi really exited.
                    disconnectedChecks++
                    if (disconnectedChecks < DISCONNECT_CHECKS_BEFORE_EXIT) return@addActionListener
                    stop()
                    if (isInitialized) handleProcessExit()
                }
            }
        }
        // Start monitoring after pi has had time to start
        Timer(5000, null).apply {
            isRepeats = false
            addActionListener {
                checkTimer.start()
            }
            start()
        }
    }

    private fun handleProcessExit() {
        reset()
        if (!PiSettings.getInstance().state.showProcessNotifications) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(
                "Pi process exited unexpectedly",
                "Click to restart Pi",
                NotificationType.WARNING
            )
        notification.addAction(object : com.intellij.notification.NotificationAction("Restart Pi") {
            override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                notification.expire()
                launch()
            }
        })
        notification.notify(project)
    }

    companion object {
        private const val TAB_NAME = "Pi"

        /** Attempts (300 ms each) before a widget without a connector is typed into anyway. */
        private const val READY_FALLBACK_ATTEMPTS = 10

        /** How long a process table scan result is considered fresh. */
        private const val PROCESS_SCAN_CACHE_MS = 5_000L

        private const val DISCONNECT_CHECKS_BEFORE_EXIT = 2

        private val PI_EXECUTABLES = setOf("pi", "pi.exe", "pi.cmd", "pi.ps1", "pi.js", "pi.mjs")

        fun getInstance(project: Project): PiTerminalService = project.service()
    }
}
