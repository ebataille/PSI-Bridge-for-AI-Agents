package dev.ebataille.idebridge.core

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * The set of files a tool acts on.
 *
 * Every batch tool takes the same pair of parameters — explicit `paths`, or a symbolic `scope` —
 * so that the model can express "the change I am working on" in one call instead of enumerating
 * files it would first have to discover. Sharing the resolution also keeps the meaning of
 * `changed` identical across tools, which is what makes
 * `get_diagnostics` / `format_code` / `optimize_imports` composable on the same target.
 */
object Scopes {

    const val PATHS_DESCRIPTION =
        "Files or directories to act on, absolute or project-relative. Directories are expanded " +
            "recursively. Takes precedence over scope."

    const val SCOPE_DESCRIPTION =
        "Used when paths is empty. changed = files modified according to VCS (default), " +
            "open = files open in the editor."

    fun resolve(project: Project, paths: List<String>, scope: String?, maxFiles: Int): List<VirtualFile> {
        if (paths.isNotEmpty()) {
            val index = ProjectFileIndex.getInstance(project)
            return paths
                .mapNotNull { Locations.findFile(project, it) }
                .flatMap { expand(it, index, maxFiles) }
                .distinct()
                .take(maxFiles)
        }

        val open = FileEditorManager.getInstance(project).openFiles.toList()
        if (scope == "open") {
            return open.take(maxFiles)
        }
        val changed = changed(project)
        if (changed.isNotEmpty()) {
            return changed.take(maxFiles)
        }
        // An empty changeset is the normal state right after a commit: falling back to the open
        // files keeps the tool useful instead of answering "nothing to do".
        return open.take(maxFiles)
    }

    private fun changed(project: Project): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            ChangeListManager.getInstance(project).affectedFiles
                .filter { it.isValid && !it.isDirectory && index.isInContent(it) }
        }
    }

    private fun expand(root: VirtualFile, index: ProjectFileIndex, maxFiles: Int): List<VirtualFile> {
        if (!root.isDirectory) {
            return listOf(root)
        }
        val collected = mutableListOf<VirtualFile>()
        ReadAction.run<RuntimeException> {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (collected.size >= maxFiles) {
                        return false
                    }
                    if (file.isDirectory) {
                        // node_modules and build outputs are excluded from the content roots:
                        // walking into them would blow the cap on the first directory.
                        return index.isInContent(file) || file == root
                    }
                    if (index.isInContent(file)) {
                        collected += file
                    }
                    return true
                }
            })
        }
        return collected
    }
}
