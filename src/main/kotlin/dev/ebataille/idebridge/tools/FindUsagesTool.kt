package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Symbols
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Real references to a symbol, through the PSI index.
 *
 * The difference with grep is not cosmetic: only genuine resolved references show up — scope and
 * aliases accounted for — with no same-named symbols from other modules and no hits inside
 * comments. Imports are included, because they are real references.
 */
object FindUsagesTool : BridgeTool {

    override val name = "find_usages"

    override val description =
        "Every reference to the symbol at a given position (declaration, imports, calls, " +
            "aliases). Prefer this over grepping for a name: these are references resolved by " +
            "the IDE, with no same-named decoys and no false positives."

    override val inputSchema = Schema.obj(
        "file" to Schema.string("File containing the symbol, absolute or project-relative."),
        "line" to Schema.integer("Line of the symbol, 1-based."),
        "column" to Schema.integer("Column of the symbol, 1-based (anywhere inside the name)."),
        "max_results" to Schema.integer("Maximum references returned. Default: 200."),
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
        val maxResults = Args.int(args, "max_results", 200)

        val target: PsiElement = Symbols.resolve(project, file, line, column)
            ?: throw IllegalArgumentException(
                "No resolvable symbol at ${context.display(file)}:$line:$column. " +
                    "Check that the column falls inside an identifier.",
            )
        val located = Symbols.locate(target)

        val references = ReadAction.compute<List<Reference>, RuntimeException> {
            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                .findAll()
                .mapNotNull { reference ->
                    val element = reference.element
                    val referenceFile = element.containingFile?.virtualFile ?: return@mapNotNull null
                    val document = FileDocumentManager.getInstance().getDocument(referenceFile)
                        ?: return@mapNotNull null
                    val offset = element.textRange.startOffset
                    val referenceLine = Locations.lineOf(document, offset)
                    Reference(
                        path = context.display(referenceFile),
                        line = referenceLine,
                        column = Locations.columnOf(document, offset),
                        text = Locations.lineText(document, referenceLine),
                    )
                }
        }

        return render(context, located, references, maxResults)
    }

    private fun render(
        context: ToolContext,
        located: Symbols.Located?,
        references: List<Reference>,
        maxResults: Int,
    ): String {
        val out = StringBuilder()
        if (located != null) {
            out.append("Symbol: ${located.name} (${located.kind}) declared at ")
                .append("${context.display(located.file)}:${located.line}:${located.column}")
                .append("\n\n")
        }

        if (references.isEmpty()) {
            out.append("No references in the project.")
            return out.toString()
        }

        val grouped = references
            .sortedWith(compareBy({ it.path }, { it.line }, { it.column }))
            .take(maxResults)
            .groupBy { it.path }

        grouped.forEach { (path, rows) ->
            out.append(path).append('\n')
            rows.forEach { row ->
                out.append("  ")
                    .append("${row.line}:${row.column}".padEnd(9))
                    .append(row.text.take(160))
                    .append('\n')
            }
            out.append('\n')
        }

        out.append("${references.size} reference(s) across ${grouped.size} file(s)")
        if (references.size > maxResults) {
            out.append(" - ${references.size - maxResults} not shown")
        }
        out.append('.')
        return out.toString()
    }

    private data class Reference(
        val path: String,
        val line: Int,
        val column: Int,
        val text: String,
    )
}
