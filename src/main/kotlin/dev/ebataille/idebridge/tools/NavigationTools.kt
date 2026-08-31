package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Symbols
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/** The three coordinates every symbol-addressed tool takes. */
private object Position {

    fun schema(vararg extra: Pair<String, JsonObject>): JsonObject {
        val base = arrayOf(
            "file" to Schema.string("File containing the symbol, absolute or project-relative."),
            "line" to Schema.integer("Line of the symbol, 1-based."),
            "column" to Schema.integer("Column of the symbol, 1-based (anywhere inside the name)."),
        )
        return Schema.obj(*base, *extra, required = listOf("file", "line", "column"))
    }

    fun resolve(context: ToolContext, args: JsonObject): PsiElement {
        val project = context.project
        val path = Args.requiredString(args, "file")
        val file = Locations.findFile(project, path)
            ?: throw IllegalArgumentException("File not found: $path")
        val line = Args.int(args, "line", 0)
        val column = Args.int(args, "column", 1)
        return Symbols.resolve(project, file, line, column)
            ?: throw IllegalArgumentException(
                "No resolvable symbol at ${context.display(file)}:$line:$column. " +
                    "Check that the column falls inside an identifier.",
            )
    }

    fun describe(context: ToolContext, element: PsiElement): String {
        val located = Symbols.locate(element) ?: return "?"
        return "${located.name} (${located.kind}) at " +
            "${context.display(located.file)}:${located.line}:${located.column}"
    }
}

/**
 * Implementations and overrides of a symbol.
 *
 * `find_usages` answers "who mentions this", which is a different question: on an interface it
 * returns the imports and the type annotations, and buries the four classes that actually
 * implement it. This is the "go to implementation" of the IDE, which resolves through the type
 * hierarchy rather than through references.
 */
object FindImplementationsTool : BridgeTool {

    override val name = "find_implementations"

    override val description =
        "Classes implementing an interface, or methods overriding the one at a given position. " +
            "Use this rather than find_usages when the question is \"what are the concrete " +
            "implementations\": references drown them in imports and type annotations."

    override val inputSchema = Position.schema(
        "max_results" to Schema.integer("Maximum implementations returned. Default: 100."),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val target = Position.resolve(context, args)
        val maxResults = Args.int(args, "max_results", 100)

        val rows = ReadAction.compute<List<String>, RuntimeException> {
            DefinitionsScopedSearch.search(target)
                .findAll()
                .mapNotNull { describe(context, it) }
                .distinct()
                .sorted()
        }

        val head = "Implementations of ${Position.describe(context, target)}\n"
        if (rows.isEmpty()) {
            return head + "\nNone found. Either the symbol is not a type or a method, or nothing " +
                "in the project implements it."
        }
        val shown = rows.take(maxResults)
        return buildString {
            append(head).append('\n')
            shown.forEach { append("  ").append(it).append('\n') }
            append("\n${rows.size} implementation(s)")
            if (rows.size > shown.size) {
                append(" - ${rows.size - shown.size} not shown")
            }
            append('.')
        }
    }

    private fun describe(context: ToolContext, element: PsiElement): String? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset
            ?: element.textRange?.startOffset
            ?: return null
        val line = Locations.lineOf(document, offset)
        val name = (element as? PsiNameIdentifierOwner)?.name ?: "?"
        return "${context.display(file)}:$line:${Locations.columnOf(document, offset)}  $name"
    }
}

/**
 * Who calls this, transitively.
 *
 * Answering it with `find_usages` costs one round trip per level, and the model has to work out
 * which function encloses each reference by reading the file around it. Walking the tree here
 * turns a five-call investigation into one, which is the whole point of the exercise.
 *
 * Only the callers direction is exposed. Callees are already in front of the model: they are in
 * the body of the function it is looking at.
 */
object FindCallersTool : BridgeTool {

    private const val DEFAULT_DEPTH = 2
    private const val MAX_NODES = 300

    /** How far up the PSI tree an import declaration can sit above the reference. */
    private const val IMPORT_LOOKUP_DEPTH = 8

    override val name = "find_callers"

    override val description =
        "Call chain leading to the function at a given position, walked transitively through " +
            "resolved references. Use it to measure the blast radius of a change instead of " +
            "chaining find_usages calls level by level."

    override val inputSchema = Position.schema(
        "depth" to Schema.integer(
            "Levels of callers to walk. Default: $DEFAULT_DEPTH. Beyond 3 the tree gets large.",
        ),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val target = Position.resolve(context, args)
        val depth = Args.int(args, "depth", DEFAULT_DEPTH).coerceIn(1, 5)

        val out = StringBuilder("Callers of ${Position.describe(context, target)}\n\n")
        var nodes = 0
        val seen = mutableSetOf<PsiElement>()

        ReadAction.run<RuntimeException> {
            nodes = expand(context, target, 0, depth, seen, out)
        }

        if (nodes == 0) {
            out.append("  No caller found in the project.")
        } else {
            out.append("\n$nodes caller(s), depth $depth.")
            if (nodes >= MAX_NODES) {
                out.append(" Truncated - narrow the depth.")
            }
        }
        return out.toString()
    }

    /** Call under a read action. Returns the number of callers appended. */
    private fun expand(
        context: ToolContext,
        target: PsiElement,
        level: Int,
        maxDepth: Int,
        seen: MutableSet<PsiElement>,
        out: StringBuilder,
    ): Int {
        if (level >= maxDepth || !seen.add(target)) {
            return 0
        }
        var count = 0
        val callers = mutableListOf<Caller>()

        ReferencesSearch.search(target, GlobalSearchScope.projectScope(target.project))
            .findAll()
            .forEach { reference ->
                val element = reference.element
                // An import mentions the symbol without calling it. Keeping those would add one
                // line per importing file at every level of the tree, which is exactly the noise
                // this tool exists to remove.
                if (insideImport(element)) {
                    return@forEach
                }
                val file = element.containingFile?.virtualFile ?: return@forEach
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@forEach
                val offset = element.textRange.startOffset
                val enclosing = enclosingFunction(element)
                callers += Caller(
                    path = context.display(file),
                    line = Locations.lineOf(document, offset),
                    name = (enclosing as? PsiNameIdentifierOwner)?.name ?: "(module level)",
                    enclosing = enclosing,
                )
            }

        callers
            .distinctBy { it.label }
            // Sorted on the line as a number: sorting the rendered label puts :10 before :4.
            .sortedWith(compareBy({ it.path }, { it.line }))
            .forEach { caller ->
                if (count >= MAX_NODES) {
                    return count
                }
                out.append("  ".repeat(level + 1)).append(caller.label).append('\n')
                count++
                if (caller.enclosing != null) {
                    count += expand(context, caller.enclosing, level + 1, maxDepth, seen, out)
                }
            }
        return count
    }

    /**
     * An import declaration references the symbol without calling it.
     *
     * Matching on the PSI class name rather than on `ES6ImportDeclaration` keeps this working for
     * every language the IDE supports - `PsiImportStatement`, `KtImportDirective` and the rest all
     * carry the word - and it is the same trick `Symbols.describeKind` already relies on.
     */
    private fun insideImport(element: PsiElement): Boolean {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < IMPORT_LOOKUP_DEPTH) {
            if (current.javaClass.simpleName.contains("Import")) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private class Caller(
        val path: String,
        val line: Int,
        val name: String,
        val enclosing: PsiElement?,
    ) {
        val label: String get() = "$path:$line  $name"
    }

    /**
     * The named function a reference sits inside.
     *
     * `JSFunction` covers methods and arrow functions, but an arrow function assigned to a const
     * has no name of its own, so an unnamed match is not the answer: keep walking up until
     * something is actually nameable, and fall back to the nearest named element for languages
     * outside the JavaScript family.
     */
    private fun enclosingFunction(element: PsiElement): PsiElement? {
        var current: PsiElement? = PsiTreeUtil.getParentOfType(element, JSFunction::class.java, true)
        while (current != null) {
            if ((current as? PsiNameIdentifierOwner)?.name?.isNotBlank() == true) {
                return current
            }
            current = PsiTreeUtil.getParentOfType(current, JSFunction::class.java, true)
        }
        val named = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, true)
        return if (named?.name?.isNotBlank() == true) named else null
    }
}
