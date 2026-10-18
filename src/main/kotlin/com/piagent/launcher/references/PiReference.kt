package com.piagent.launcher.references

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Builds Pi file references.
 * - File: `@relative/path/File.kt`
 * - Selection: `@relative/path/File.kt#L10-25`
 */
object PiReference {

    /** Path relative to the project root, `/`-separated; absolute path when outside the project. */
    fun relativePath(project: Project, file: VirtualFile): String {
        val basePath = project.basePath ?: return normalize(file.path)
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
        if (baseDir != null) {
            VfsUtilCore.getRelativePath(file, baseDir, '/')?.let { return it }
        }
        val normalizedBase = normalize(basePath).trimEnd('/')
        val normalizedFile = normalize(file.path)
        return when {
            normalizedFile == normalizedBase -> file.name
            normalizedFile.startsWith("$normalizedBase/") -> normalizedFile.removePrefix("$normalizedBase/")
            else -> normalizedFile
        }
    }

    /** Whole-file reference: `@relative/path/File.kt`. */
    fun fileReference(project: Project, file: VirtualFile): String =
        "@${relativePath(project, file)}"

    /**
     * Selection reference, or a whole-file reference when the editor has no selection,
     * is disposed, or does not belong to [file].
     */
    fun codeReference(project: Project, file: VirtualFile, editor: Editor?): String {
        if (editor == null || editor.isDisposed || !isEditorFile(file, editor)) {
            return fileReference(project, file)
        }

        val selection = editor.selectionModel
        if (!selection.hasSelection()) return fileReference(project, file)

        val document = editor.document
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        var endOffset = selection.selectionEnd
        // A selection ending at column 0 includes the trailing newline — do not count the next line.
        if (endOffset > selection.selectionStart && editor.offsetToLogicalPosition(endOffset).column == 0) {
            endOffset--
        }
        val endLine = document.getLineNumber(endOffset) + 1

        val lineRange = if (startLine == endLine) "L$startLine" else "L$startLine-$endLine"
        return "${fileReference(project, file)}#$lineRange"
    }

    private fun isEditorFile(file: VirtualFile, editor: Editor): Boolean =
        FileDocumentManager.getInstance().getFile(editor.document) == file

    private fun normalize(path: String): String = path.replace('\\', '/')
}
