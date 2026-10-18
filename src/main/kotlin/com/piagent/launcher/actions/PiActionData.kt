package com.piagent.launcher.actions

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem

/**
 * Shared data lookups for the Pi context-menu actions.
 *
 * The Project View exposes different keys depending on IDE version/UI:
 * virtual files, navigatables, PSI elements or the generic selected items.
 * They are merged here instead of relying on a single key.
 */
internal object PiActionData {

    /**
     * Files selected in the Project View, or the file of the current editor.
     * Includes directories — callers filter them out when needed.
     */
    fun files(e: AnActionEvent): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()

        fun add(file: VirtualFile?) {
            if (file != null && file.isValid) result.add(file)
        }

        // Editors, editor tabs and some project views.
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { add(it) }
        add(e.getData(CommonDataKeys.VIRTUAL_FILE))

        // A real file from the virtual-file keys is authoritative (editor context).
        if (result.any { !it.isDirectory }) return result.toList()

        // Project View: PSI elements (primary for file tree selections).
        e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.forEach { add(toVirtualFile(it)) }
        add(toVirtualFile(e.getData(CommonDataKeys.PSI_ELEMENT)))

        // Project View: navigatable selection.
        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.forEach { add(toVirtualFile(it)) }
        add(toVirtualFile(e.getData(CommonDataKeys.NAVIGATABLE)))

        // New UI: generic selected items (VirtualFile / PsiElement).
        e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.forEach { add(toVirtualFile(it)) }

        add(e.getData(CommonDataKeys.PSI_FILE)?.virtualFile)

        if (result.isEmpty()) {
            addProjectViewSelection(e.project, ::add)
        }
        if (result.isEmpty()) {
            val editor = e.getData(CommonDataKeys.EDITOR)
            if (editor != null && !editor.isDisposed) {
                add(FileDocumentManager.getInstance().getFile(editor.document))
            }
        }
        return result.toList()
    }

    /** The single file (or PSI file's VirtualFile) behind the current editor. */
    fun editorFile(e: AnActionEvent): VirtualFile? {
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { if (it.isValid) return it }
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { if (it.isValid) return it }
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { toVirtualFile(it)?.let { file -> return file } }

        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        if (editor.isDisposed) return null
        return FileDocumentManager.getInstance().getFile(editor.document)
    }

    /** Fallback: ask the Project View directly for its current selection. */
    private fun addProjectViewSelection(project: Project?, add: (VirtualFile?) -> Unit) {
        if (project == null) return
        try {
            val pane = ProjectView.getInstance(project).currentProjectViewPane ?: return
            pane.selectedUserObjects.forEach { add(toVirtualFile(it)) }
        } catch (_: Exception) {
            // Project View is not available in this context.
        }
    }

    private fun toVirtualFile(item: Any?): VirtualFile? = when (item) {
        is VirtualFile -> item
        is PsiFileSystemItem -> item.virtualFile
        is ProjectViewNode<*> -> item.virtualFile
        is AbstractTreeNode<*> -> toVirtualFile(item.value)
        is PsiElement -> item.containingFile?.virtualFile
        else -> null
    }
}
