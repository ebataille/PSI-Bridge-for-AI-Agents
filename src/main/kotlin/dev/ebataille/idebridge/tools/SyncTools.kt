package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * IDE state: doubles as a connectivity check and tells the model whether the index is ready —
 * a precondition for find_usages and the refactorings to be correct at all.
 */
object IdeStatusTool : BridgeTool {

    override val name = "ide_status"

    override val description =
        "State of the JetBrains IDE attached to this project: product, open project, whether " +
            "indexing is still running, open files, unsaved documents. Call it whenever you are " +
            "unsure the other tools are usable."

    override val inputSchema = Schema.obj()

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        val info = ApplicationInfo.getInstance()
        val dumb = DumbService.getInstance(project).isDumb
        val open = FileEditorManager.getInstance(project).openFiles
        val unsaved = FileDocumentManager.getInstance().unsavedDocuments.size

        val lines = mutableListOf<String>()
        lines += "IDE      : ${info.fullApplicationName} (build ${info.build.asString()})"
        lines += "Project  : ${project.name} -> ${project.basePath}"
        lines += "Indexing : " + if (dumb) {
            "RUNNING - find_usages and refactorings are unavailable or incomplete until it finishes"
        } else {
            "done"
        }
        lines += "Editors  : ${open.size} open file(s), $unsaved unsaved document(s)"
        return lines.joinToString("\n")
    }
}

/**
 * Flushes in-memory documents to disk.
 *
 * This is the forgotten half of synchronisation: without it, an agent reading a file from disk
 * sees a stale version of what the user has on screen, and overwrites it.
 */
object SaveAllTool : BridgeTool {

    override val name = "ide_save_all"

    override val description =
        "Writes every modified-but-unsaved IDE document to disk. Call before reading files if " +
            "the user may have unsaved changes in the editor."

    override val inputSchema = Schema.obj()

    override fun call(context: ToolContext, args: JsonObject): String {
        val saved = Editors.saveAll()
        if (saved == 0) {
            return "No unsaved documents."
        }
        return "$saved document(s) written to disk."
    }
}

/**
 * Re-syncs the VFS with the disk.
 *
 * IntelliJ only re-reads the disk when its window regains focus, and filesystem notifications are
 * unreliable when the writes come from WSL. Without this refresh, the IDE keeps analysing a stale
 * version of the files an agent just wrote.
 */
object RefreshTool : BridgeTool {

    override val name = "ide_refresh"

    override val description =
        "Forces the IDE to re-read the given files from disk (or the whole project when no path " +
            "is given), so that its analysis reflects the current content. Call after modifying " +
            "files outside the IDE."

    override val inputSchema = Schema.obj(
        "paths" to Schema.arrayOf(
            "Files to re-sync, absolute or relative to the project root. Empty = whole project " +
                "(slower).",
            Schema.string("path"),
        ),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val paths = Args.stringList(args, "paths")
        if (paths.isEmpty()) {
            // Refreshing the VFS requires the write lock, hence the trip through the EDT.
            ApplicationManager.getApplication().invokeAndWait {
                VirtualFileManager.getInstance().syncRefresh()
            }
            return "Project re-synced with disk."
        }

        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val files = paths.mapNotNull { raw ->
            val file = Locations.findFile(context.project, raw)
            if (file == null) {
                missing += raw
                null
            } else {
                found += context.display(file)
                file
            }
        }
        if (files.isNotEmpty()) {
            ApplicationManager.getApplication().invokeAndWait {
                VfsUtil.markDirtyAndRefresh(false, true, true, *files.toTypedArray())
            }
        }

        val report = StringBuilder()
        if (found.isNotEmpty()) {
            report.append("Re-synced: ").append(found.joinToString(", "))
        }
        if (missing.isNotEmpty()) {
            if (report.isNotEmpty()) {
                report.append("\n")
            }
            report.append("Not found: ").append(missing.joinToString(", "))
        }
        return report.toString()
    }
}
