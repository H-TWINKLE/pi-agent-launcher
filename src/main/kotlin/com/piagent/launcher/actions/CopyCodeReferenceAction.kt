package com.piagent.launcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.piagent.launcher.references.PiReference
import com.intellij.openapi.project.DumbAware
/**
 * Copy the current editor context to the clipboard as a Pi reference.
 * Selection: `@path/File.kt#L10-25`; no selection: `@path/File.kt`.
 */
class CopyCodeReferenceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = PiActionData.editorFile(e) ?: return

        val reference = PiReference.codeReference(project, file, editor)
        PiClipboard.copy(project, reference)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled =
            e.getData(CommonDataKeys.EDITOR) != null && PiActionData.editorFile(e) != null
    }
}
