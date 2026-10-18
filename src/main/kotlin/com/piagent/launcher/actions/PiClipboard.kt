package com.piagent.launcher.actions

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * Clipboard helpers shared by the reference actions.
 */
internal object PiClipboard {

    /** Copy the reference and show the usual "copied" feedback. */
    fun copy(project: Project, detail: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(detail))
        PiNotifications.copied(project, detail)
    }

    /** A terminal rejected the reference — keep it on the clipboard and say so. */
    fun copyAfterSendFailure(project: Project, detail: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(detail))
        PiNotifications.sendFailed(project, detail)
    }
}
