package dev.ebataille.idebridge.core

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Translates between the coordinates a model works with (path, line, column, both 1-based as in
 * an editor) and those the PSI uses (absolute offset within the document).
 */
object Locations {

    fun findFile(project: Project, rawPath: String): VirtualFile? {
        val local = PathMapper.toLocal(rawPath).replace('\\', '/')
        val fs = LocalFileSystem.getInstance()
        val direct = fs.findFileByPath(local) ?: fs.refreshAndFindFileByPath(local)
        if (direct != null) {
            return direct
        }
        // Project-relative paths are tolerated, because models produce them constantly.
        val base = project.basePath ?: return null
        val joined = base.trimEnd('/') + "/" + local.trimStart('/')
        return fs.findFileByPath(joined) ?: fs.refreshAndFindFileByPath(joined)
    }

    /** 1-based (line, column) -> offset. Forgiving: clamps rather than failing. */
    fun toOffset(document: Document, line: Int, column: Int): Int {
        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        val safeLine = (line - 1).coerceIn(0, lastLine)
        val start = document.getLineStartOffset(safeLine)
        val end = document.getLineEndOffset(safeLine)
        return (start + (column - 1).coerceAtLeast(0)).coerceIn(start, end)
    }

    /** offset -> 1-based line. */
    fun lineOf(document: Document, offset: Int): Int {
        return document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1
    }

    /** offset -> 1-based column. */
    fun columnOf(document: Document, offset: Int): Int {
        val safe = offset.coerceIn(0, document.textLength)
        return safe - document.getLineStartOffset(document.getLineNumber(safe)) + 1
    }

    fun lineText(document: Document, line1Based: Int): String {
        val index = line1Based - 1
        if (index < 0 || index >= document.lineCount) {
            return ""
        }
        val range = TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index))
        return document.getText(range).trim()
    }
}
