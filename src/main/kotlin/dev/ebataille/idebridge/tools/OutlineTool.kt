package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Scopes
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * The Structure view, as text.
 *
 * This is the tool that pays for itself the fastest, and it does not refactor anything: a model
 * that wants one function out of a 2000-line file has no choice but to ingest the whole file,
 * which costs tens of thousands of tokens and dilutes everything else in the context. The outline
 * costs a few hundred and hands back the line ranges, so the next read is `offset`/`limit` over
 * the twenty lines that matter.
 *
 * It goes through the same Structure view builder as the IDE panel, so it works on any language
 * the IDE supports rather than only the ones this plugin knows about. When a language has no
 * builder, a plain PSI walk over the named elements still produces something usable.
 */
object OutlineTool : BridgeTool {

    private const val MAX_FILES = 20
    private const val DEFAULT_DEPTH = 3
    private const val DEFAULT_MAX_SYMBOLS = 400

    override val name = "get_outline"

    override val description =
        "Symbol structure of one or more files - classes, functions, methods, fields - with the " +
            "line range of each. Read this before reading a file in full: it costs a fraction of " +
            "the tokens and gives you the exact lines to read afterwards."

    override val inputSchema = Schema.obj(
        "paths" to Schema.arrayOf(
            "Files to outline, absolute or project-relative. Directories are expanded.",
            Schema.string("path"),
        ),
        "max_depth" to Schema.integer(
            "Nesting levels to show. Default: $DEFAULT_DEPTH. 1 = top-level symbols only.",
        ),
        "max_symbols" to Schema.integer("Maximum symbols per file. Default: $DEFAULT_MAX_SYMBOLS."),
        required = listOf("paths"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val paths = Args.stringList(args, "paths")
        if (paths.isEmpty()) {
            throw IllegalArgumentException("paths is required and must not be empty.")
        }
        val maxDepth = Args.int(args, "max_depth", DEFAULT_DEPTH).coerceIn(1, 10)
        val maxSymbols = Args.int(args, "max_symbols", DEFAULT_MAX_SYMBOLS).coerceIn(1, 2000)
        val files = Scopes.resolve(project, paths, null, MAX_FILES)
        if (files.isEmpty()) {
            return "No file found for: ${paths.joinToString(", ")}"
        }

        val out = StringBuilder()
        files.forEach { file ->
            if (out.isNotEmpty()) {
                out.append("\n\n")
            }
            out.append(outlineOf(context, file, maxDepth, maxSymbols))
        }
        return out.toString()
    }

    private fun outlineOf(
        context: ToolContext,
        file: VirtualFile,
        maxDepth: Int,
        maxSymbols: Int,
    ): String {
        val project = context.project
        val label = context.display(file)

        var psiFile: PsiFile? = null
        var document: Document? = null
        val rows = mutableListOf<Row>()
        var source = "structure view"

        // Some Structure view builders touch UI state, so the model is built on the EDT; the walk
        // itself stays inside the read action it opens.
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runReadAction {
                psiFile = PsiManager.getInstance(project).findFile(file)
                document = FileDocumentManager.getInstance().getDocument(file)
                val currentFile = psiFile
                val currentDocument = document
                if (currentFile == null || currentDocument == null) {
                    return@runReadAction
                }
                val model = buildModel(currentFile)
                if (model != null) {
                    try {
                        collect(model.root, currentDocument, 0, maxDepth, maxSymbols, rows)
                    } finally {
                        Disposer.dispose(model)
                    }
                }
                if (rows.isEmpty()) {
                    source = "PSI walk (no structure view for this language)"
                    walk(currentFile, currentDocument, 0, maxDepth, maxSymbols, rows)
                }
            }
        }

        val currentDocument = document
            ?: return "$label: not readable by the IDE (binary file, or outside the project)."
        val lines = currentDocument.lineCount

        if (rows.isEmpty()) {
            return "$label - $lines lines, no symbol found. Read the file directly."
        }

        val head = "$label - $lines lines, ${rows.size} symbol(s), via $source"
        val width = rows.maxOf { it.range.length }
        val body = rows.joinToString("\n") { row ->
            "  ${row.range.padStart(width)}  ${"  ".repeat(row.depth)}${row.text}"
        }
        val tail = if (rows.size >= maxSymbols) {
            "\n  ... truncated at max_symbols=$maxSymbols"
        } else {
            ""
        }
        return "$head\n$body$tail"
    }

    private fun buildModel(psiFile: PsiFile): StructureViewModel? {
        return try {
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
            // Only the tree-based builders can produce a model without an editor, which is
            // precisely the situation here: nothing is open.
            (builder as? TreeBasedStructureViewBuilder)?.createStructureViewModel(null)
        } catch (e: Throwable) {
            null
        }
    }

    private fun collect(
        element: TreeElement,
        document: Document,
        depth: Int,
        maxDepth: Int,
        maxSymbols: Int,
        rows: MutableList<Row>,
    ) {
        element.children.forEach { child ->
            if (rows.size >= maxSymbols) {
                return
            }
            val text = describe(child)
            if (text != null) {
                rows += Row(range(child, document), depth, text)
            }
            if (depth + 1 < maxDepth) {
                collect(child, document, depth + 1, maxDepth, maxSymbols, rows)
            }
        }
    }

    private fun describe(element: TreeElement): String? {
        val presentation = element.presentation
        val main = presentation.presentableText?.trim().orEmpty()
        if (main.isEmpty()) {
            return null
        }
        // The location string carries the return type or the owning class depending on the
        // language; it is exactly the disambiguation the model would otherwise open the file for.
        val detail = presentation.locationString?.trim().orEmpty()
        return if (detail.isEmpty()) main else "$main  $detail"
    }

    private fun range(element: TreeElement, document: Document): String {
        val psi = (element as? StructureViewTreeElement)?.value as? PsiElement ?: return "?"
        val textRange = psi.textRange ?: return "?"
        val start = Locations.lineOf(document, textRange.startOffset)
        val end = Locations.lineOf(document, textRange.endOffset)
        return if (start == end) "$start" else "$start-$end"
    }

    /** Fallback for languages with no structure view: every named element, in source order. */
    private fun walk(
        element: PsiElement,
        document: Document,
        depth: Int,
        maxDepth: Int,
        maxSymbols: Int,
        rows: MutableList<Row>,
    ) {
        element.children.forEach { child ->
            if (rows.size >= maxSymbols) {
                return
            }
            val name = (child as? PsiNamedElement)?.name
            val nextDepth = if (name.isNullOrBlank()) {
                depth
            } else {
                val textRange = child.textRange
                val start = if (textRange != null) Locations.lineOf(document, textRange.startOffset) else 0
                val end = if (textRange != null) Locations.lineOf(document, textRange.endOffset) else 0
                val label = if (start == end) "$start" else "$start-$end"
                rows += Row(label, depth, "$name  ${kind(child)}")
                depth + 1
            }
            if (nextDepth < maxDepth) {
                walk(child, document, nextDepth, maxDepth, maxSymbols, rows)
            }
        }
    }

    private fun kind(element: PsiElement): String {
        return element.javaClass.simpleName.removeSuffix("Impl")
    }

    private class Row(val range: String, val depth: Int, val text: String)
}
