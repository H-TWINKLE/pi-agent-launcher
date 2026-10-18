package com.piagent.launcher.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.piagent.launcher.settings.PiSettings

/**
 * User-feedback notifications for explicit actions (clipboard copy).
 * These respect the "Notify when a reference is copied" setting.
 */
internal object PiNotifications {

    fun copied(project: Project, detail: String) {
        if (!PiSettings.getInstance().state.showCopyNotifications) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(
                "Copied to clipboard",
                StringUtil.escapeXmlEntities(detail),
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    /** Terminal injection failed; the reference is still on the clipboard. */
    fun sendFailed(project: Project, detail: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(
                "Could not send to Pi",
                "Pi is not reachable. The reference is on the clipboard — paste it into Pi:<br/>" +
                    StringUtil.escapeXmlEntities(detail),
                NotificationType.WARNING
            )
            .notify(project)
    }

}
