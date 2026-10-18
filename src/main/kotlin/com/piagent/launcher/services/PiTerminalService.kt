package com.piagent.launcher.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
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
            return content.manager != null
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

        // createLocalShellWidget(workingDir, tabName) -> ShellTerminalWidget
        val createMethod = managerClass.getMethod(
            "createLocalShellWidget",
            String::class.java,
            String::class.java
        )
        val widget = createMethod.invoke(manager, workingDir, TAB_NAME)

        // Get component
        val component = widget.javaClass.getMethod("getComponent").invoke(widget) as JComponent
        terminalComponent.set(component)
        terminalContent.set(findContentByName(TAB_NAME))

        // Store sendText function for the terminal created by this plugin
        sendTextFn = { text, execute -> sendToWidget(widget, text, execute) }

        // Show and focus the Terminal tool window
        focusPiTab()

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
        val toolWindow = terminalToolWindow() ?: return
        toolWindow.show {
            val contentManager = toolWindow.contentManager
            for (content in contentManager.contents) {
                if (content.displayName == "Pi") {
                    contentManager.setSelectedContent(content, true)
                    break
                }
            }
            terminalComponent.get()?.requestFocusInWindow()
        }
    }

    /** Bring the Terminal tool window up; when [focusWidget] is given it also gets the focus. */
    private fun showTerminalWindow(focusWidget: Any? = null) {
        terminalToolWindow()?.show {
            if (focusWidget != null) requestFocus(focusWidget)
        }
    }

    private fun startPiWithDelay() {
        val command = buildPiCommand()

        // Poll until terminal is ready, then send command
        var attempts = 0
        val timer = object : Timer(300, null) {
            init {
                isRepeats = true
                addActionListener {
                    attempts++
                    val fn = sendTextFn
                    if (fn != null && fn(command, true)) {
                        stop()
                        schedulePendingInputFlush()
                    } else if (attempts > 20) {
                        logger.warn("Terminal not ready after 6s")
                        stop()
                    }
                }
            }
        }
        timer.start()
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

            if (settings.extraArgs.isNotBlank()) {
                append(" ")
                append(settings.extraArgs)
            }
        }
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

        val hasTabs = hasTerminalTabs()
        val piRunning = isPiProcessRunning()
        if (hasTabs || piRunning) {
            logger.warn("Cannot reach a Pi terminal (terminalTabs=$hasTabs, piProcess=$piRunning)")
            return
        }

        pendingInput.set(text to true)
        launch()
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

        // Never spawn a second Pi when one is already running or terminal tabs exist.
        val hasTabs = hasTerminalTabs()
        val piRunning = isPiProcessRunning()
        if (hasTabs || piRunning) {
            logger.warn("Cannot reach a Pi terminal (terminalTabs=$hasTabs, piProcess=$piRunning)")
            return false
        }

        // No terminal at all: start Pi and replay the text once it is ready.
        pendingInput.set(text to false)
        launch()
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
     * Best-effort check for a Pi process that this plugin cannot type into,
     * e.g. Pi started in an external terminal. Prevents spawning a second Pi.
     */
    private fun isPiProcessRunning(): Boolean = try {
        ProcessHandle.allProcesses().anyMatch { handle ->
            val commandLine = handle.info().commandLine().orElse("")
            looksLikePiCommand(commandLine)
        }
    } catch (e: Exception) {
        logger.warn("Failed to inspect running processes: ${e.message}")
        false
    }

    private fun looksLikePiCommand(commandLine: String): Boolean {
        if (commandLine.isBlank()) return false
        val lower = commandLine.lowercase()
        if (lower.contains("pi-coding-agent")) return true
        return lower.split(' ', '\t', '"', '\'').any { token ->
            when (token.substringAfterLast('/').substringAfterLast('\\')) {
                "pi", "pi.exe", "pi.cmd", "pi.ps1", "pi.js", "pi.mjs" -> true
                else -> false
            }
        }
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
        val checkTimer = object : Timer(2000, null) {
            init {
                isRepeats = true
                addActionListener {
                    try {
                        val connector = widget.javaClass.getMethod("getTtyConnector").invoke(widget)
                        if (connector != null) {
                            val isConnected = connector.javaClass.getMethod("isConnected").invoke(connector) as Boolean
                            if (!isConnected && isInitialized) {
                                stop()
                                handleProcessExit()
                            }
                        }
                    } catch (_: Exception) {
                        // Widget disposed, stop monitoring
                        stop()
                    }
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

        fun getInstance(project: Project): PiTerminalService = project.service()
    }
}
