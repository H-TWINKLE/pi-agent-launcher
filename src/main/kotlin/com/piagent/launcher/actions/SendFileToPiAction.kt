package com.piagent.launcher.actions

import com.piagent.launcher.references.PiReference
import com.piagent.launcher.services.PiTerminalService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
/**
 * Send file(s) from the Project View / editor tab to the active terminal.
 * Supports single and multiple file selection.
 * Format: @relative/path/to/file.go
 */
class SendFileToPiAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val files = PiActionData.files(e).filter { file ->
            file.isValid && !file.isDirectory
        }
        if (files.isEmpty()) return

        val references = files.joinToString(" ") { PiReference.fileReference(project, it) }

        // Insert references (with trailing space for the user to type a question)
        val service = PiTerminalService.getInstance(project)
        if (!service.insertText("$references ")) {
            PiClipboard.copyAfterSendFailure(project, references)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = PiActionData.files(e).any { file ->
            file.isValid && !file.isDirectory
        }
    }
}
