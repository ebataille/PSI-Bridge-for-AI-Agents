package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringFactory
import com.intellij.usageView.UsageInfo
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Symbols
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Rename through the IDE's refactoring engine.
 *
 * What grep-then-sed does not do: follow import aliases, rename the file when it carries the
 * symbol's name, leave same-named symbols in other scopes alone, and keep the whole thing
 * undoable with a single Ctrl+Z in the IDE.
 *
 * We go through RefactoringFactory rather than RenameProcessor.run(): `doRefactoring(usages)`
 * applies the change without ever opening a preview window or a conflicts dialog, either of
 * which would block an automated call.
 */
object RenameSymbolTool : BridgeTool {

    override val name = "rename_symbol"

    override val description =
        "Renames the symbol at a given position and updates every reference and import across " +
            "the project. Always prefer this over a manual search-and-replace. Use dry_run to " +
            "inspect the blast radius before applying."

    override val inputSchema = Schema.obj(
        "file" to Schema.string("File containing the symbol, absolute or project-relative."),
        "line" to Schema.integer("Line of the symbol, 1-based."),
        "column" to Schema.integer("Column of the symbol, 1-based (anywhere inside the name)."),
        "new_name" to Schema.string("New name."),
        "dry_run" to Schema.boolean("Change nothing, just report the impact. Default: false."),
        "search_in_comments" to Schema.boolean(
            "Also rename textual occurrences inside comments and strings. Default: false.",
        ),
        required = listOf("file", "line", "column", "new_name"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val path = Args.requiredString(args, "file")
        val newName = Args.requiredString(args, "new_name")
        val file = Locations.findFile(project, path)
            ?: throw IllegalArgumentException("File not found: $path")
        val line = Args.int(args, "line", 0)
        val column = Args.int(args, "column", 1)
        val dryRun = Args.boolean(args, "dry_run", false)
        val searchInComments = Args.boolean(args, "search_in_comments", false)

        val target: PsiElement = Symbols.resolve(project, file, line, column)
            ?: throw IllegalArgumentException(
                "No resolvable symbol at ${context.display(file)}:$line:$column.",
            )
        val located = Symbols.locate(target)
        val oldName = located?.name ?: "?"

        val refactoring = RefactoringFactory.getInstance(project)
            .createRename(target, newName, searchInComments, searchInComments)

        val usages: Array<UsageInfo> = ReadAction.compute<Array<UsageInfo>, RuntimeException> {
            refactoring.findUsages()
        }
        val touched = describeTouchedFiles(context, usages)

        if (dryRun) {
            return buildString {
                append("Dry run: $oldName -> $newName\n")
                append("${usages.size} reference(s) across ${touched.size} file(s):\n")
                touched.forEach { (path, count) -> append("  $path ($count)\n") }
                append("\nNothing was modified (dry_run).")
            }
        }

        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                refactoring.doRefactoring(usages)
            } catch (e: Throwable) {
                failure = e
            }
        }
        val error = failure
        if (error != null) {
            throw IllegalStateException("Rename failed: ${error.message}", error)
        }

        // The refactoring only touches in-memory documents: without this save, reading the file
        // right afterwards would still return the old version.
        val saved = Editors.saveAll()

        return buildString {
            append("Renamed: $oldName -> $newName\n")
            append("${usages.size} reference(s) updated across ${touched.size} file(s):\n")
            touched.forEach { (path, count) -> append("  $path ($count)\n") }
            append("\n$saved file(s) written to disk.")
        }
    }

    private fun describeTouchedFiles(context: ToolContext, usages: Array<UsageInfo>): Map<String, Int> {
        return ReadAction.compute<Map<String, Int>, RuntimeException> {
            usages
                .mapNotNull { it.file?.virtualFile }
                .groupingBy { context.display(it) }
                .eachCount()
                .toSortedMap()
        }
    }
}
