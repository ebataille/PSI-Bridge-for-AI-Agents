package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.JSTypeOwner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Symbols
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Inferred type at a given position.
 *
 * Saves the model from guessing the shape of a value out of the surrounding code: it asks, and
 * gets what the TypeScript service actually knows.
 */
object TypeInfoTool : BridgeTool {

    /** How far up the PSI tree to look for a type owner. */
    private const val PARENT_LOOKUP_DEPTH = 12

    override val name = "get_type_info"

    override val description =
        "The type TypeScript infers at a given position, plus where the symbol is declared. Use " +
            "this instead of deducing the shape of a value by reading the surrounding code."

    override val inputSchema = Schema.obj(
        "file" to Schema.string("File, absolute or project-relative."),
        "line" to Schema.integer("Line, 1-based."),
        "column" to Schema.integer("Column, 1-based."),
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

        val target = Symbols.resolve(project, file, line, column)

        val typeText = ReadAction.compute<String?, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            val offset = Locations.toOffset(document, line, column)
            val leaf = psiFile.findElementAt(offset) ?: return@compute null

            // Walk up from the position to the first type owner (expression, variable, parameter,
            // property); failing that, take the type of the resolved declaration. JSTypeOwner is
            // not a PsiElement, so the walk is done by hand.
            val owner = generateSequence(leaf as PsiElement?) { it.parent }
                .take(PARENT_LOOKUP_DEPTH)
                .filterIsInstance<JSTypeOwner>()
                .firstOrNull()
                ?: target as? JSTypeOwner
            owner?.jsType?.getTypeText(JSType.TypeTextFormat.PRESENTABLE)
        }

        val declaration = target?.let { Symbols.locate(it) }

        val out = StringBuilder()
        out.append("Position   : ${context.display(file)}:$line:$column\n")
        if (typeText != null) {
            out.append("Type       : $typeText\n")
        } else {
            out.append("Type       : undetermined here (this may not be an expression)\n")
        }
        if (declaration != null) {
            out.append("Declared   : ${declaration.name} (${declaration.kind}) at ")
            out.append("${context.display(declaration.file)}:${declaration.line}:${declaration.column}")
        } else {
            out.append("Declared   : declaration not resolved")
        }
        return out.toString()
    }
}
