package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Symbols
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Deletes a symbol, and refuses when something still uses it.
 *
 * Dead-code removal is where an agent is most confidently wrong: it greps a name, sees no hit
 * outside the file, and deletes something reached through an alias, a re-export or a
 * dependency-injection token. Here the check is the index, and the default is to abort with the
 * list of references rather than to leave a broken build behind - the interesting answer is
 * usually "you cannot", and it costs one call to get it.
 */
object SafeDeleteTool : BridgeTool {

    private const val MAX_SHOWN = 40

    override val name = "safe_delete"

    override val description =
        "Deletes the symbol at a given position after checking, through the IDE index, that " +
            "nothing references it. Refuses and lists the references when there are any. Use " +
            "this to remove dead code instead of deleting lines and hoping the build agrees."

    override val inputSchema = Schema.obj(
        "file" to Schema.string("File containing the symbol, absolute or project-relative."),
        "line" to Schema.integer("Line of the symbol, 1-based."),
        "column" to Schema.integer("Column of the symbol, 1-based (anywhere inside the name)."),
        "force" to Schema.boolean(
            "Delete even though references exist. Default: false. Leaves the project broken " +
                "unless you are about to delete those references too.",
        ),
        required = listOf("file", "line", "column"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val path = Args.requiredString(args, "file")
        val file = Locations.findFile(project, path)
            ?: throw IllegalArgumentException("File not found: $path")
        val line = Args.int(args, "line", 0)
        val column = Args.int(args, "column", 1)
        val force = Args.boolean(args, "force", false)

        val target: PsiElement = Symbols.resolve(project, file, line, column)
            ?: throw IllegalArgumentException(
                "No resolvable symbol at ${context.display(file)}:$line:$column.",
            )
        val located = Symbols.locate(target)
        val name = located?.name ?: "?"

        val references = ReadAction.compute<List<String>, RuntimeException> {
            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                .findAll()
                .mapNotNull { reference ->
                    val element = reference.element
                    // The declaration is its own reference in some languages; deleting it is the
                    // whole point, so it must not count against the operation.
                    if (located != null && element.containingFile?.virtualFile == located.file &&
                        element.textRange.startOffset == target.textRange?.startOffset
                    ) {
                        return@mapNotNull null
                    }
                    val referenceFile = element.containingFile?.virtualFile ?: return@mapNotNull null
                    val document = FileDocumentManager.getInstance().getDocument(referenceFile)
                        ?: return@mapNotNull null
                    val offset = element.textRange.startOffset
                    val referenceLine = Locations.lineOf(document, offset)
                    "${context.display(referenceFile)}:$referenceLine  " +
                        Locations.lineText(document, referenceLine).take(140)
                }
                .distinct()
                .sorted()
        }

        if (references.isNotEmpty() && !force) {
            return buildString {
                append("REFUSED: $name is still referenced ${references.size} time(s).\n")
                references.take(MAX_SHOWN).forEach { append("  $it\n") }
                if (references.size > MAX_SHOWN) {
                    append("  ... ${references.size - MAX_SHOWN} more\n")
                }
                append("\nNothing was deleted. Remove these references first, or pass force=true.")
            }
        }

        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val processor = SafeDeleteProcessor.createInstance(
                    project,
                    null,
                    arrayOf(target),
                    false,
                    false,
                    true,
                )
                // A preview would open a tool window and wait for a click nobody is there to give.
                processor.setPreviewUsages(false)
                processor.run()
            } catch (e: Throwable) {
                failure = e
            }
        }
        val error = failure
        if (error != null) {
            throw IllegalStateException("Safe delete failed: ${error.message}", error)
        }

        val saved = Editors.saveAll()
        return buildString {
            append("Deleted: $name (${located?.kind ?: "symbol"})")
            if (references.isNotEmpty()) {
                append("\nWARNING: forced past ${references.size} reference(s); the project no ")
                append("longer compiles until they are removed too.")
            }
            append("\n$saved file(s) written to disk.")
        }
    }
}
