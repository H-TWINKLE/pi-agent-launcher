package com.piagent.launcher.actions

import com.piagent.launcher.references.PiReference
import com.piagent.launcher.services.PiDiffWatcher
import com.piagent.launcher.services.PiTerminalService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
/**
 * Send selected code to the active terminal as a file reference.
 * Format: @relative/path/to/file.go#L10-25
 */
class SendSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = PiActionData.editorFile(e) ?: return

        val reference = PiReference.codeReference(project, file, editor)

        // Start diff watching so Pi's edits can be previewed
        val diffWatcher = PiDiffWatcher.getInstance(project)
        diffWatcher.snapshotFile(file.path)
        diffWatcher.startWatching()

        // Send to the currently active terminal tab; launches Pi when none exists
        val service = PiTerminalService.getInstance(project)
        if (!service.insertText("$reference ")) {
            PiClipboard.copyAfterSendFailure(project, reference)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }
}
