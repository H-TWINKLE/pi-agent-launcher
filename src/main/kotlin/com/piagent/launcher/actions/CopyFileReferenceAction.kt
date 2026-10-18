package com.piagent.launcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.piagent.launcher.references.PiReference
import com.intellij.openapi.project.DumbAware
/**
 * Copy file reference(s) to the clipboard: `@path/File.kt`, one per line for multi-selection.
 */
class CopyFileReferenceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = PiActionData.files(e).filter { file ->
            file.isValid && !file.isDirectory
        }
        if (files.isEmpty()) return

        val references = files.joinToString("\n") { PiReference.fileReference(project, it) }
        val message = if (files.size == 1) references else "${files.size} file references:\n$references"
        PiClipboard.copy(project, message)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = PiActionData.files(e).any { file ->
            file.isValid && !file.isDirectory
        }
    }
}
