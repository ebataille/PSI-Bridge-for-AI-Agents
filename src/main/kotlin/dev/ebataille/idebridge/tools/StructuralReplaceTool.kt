package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.replace.ReplaceOptions
import com.intellij.structuralsearch.plugin.replace.ReplacementInfo
import com.intellij.structuralsearch.plugin.replace.impl.Replacer
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.Scopes
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Structural search and replace over the project.
 *
 * This is the tool that replaces the loop an agent runs today: grep a name, open twenty files to
 * tell the real matches from the ones inside a string or a comment, then issue twenty edits and
 * hope none of them was a false positive. The IDE matches on the syntax tree instead, so
 * `$x$.then($y$)` matches a promise chain written over four lines with a comment in the middle,
 * and does not match the same characters inside a template literal.
 *
 * Patterns use the IDE's own structural syntax: `$name$` is a placeholder, and the same
 * placeholder reused in the replacement carries the matched text over.
 */
object StructuralReplaceTool : BridgeTool {

    private const val MAX_FILES = 500
    private const val MAX_SHOWN = 60

    override val name = "structural_replace"

    override val description =
        "Structural search and replace across the project, matching on the syntax tree rather " +
            "than on text. Placeholders are written \$name\$ in both pattern and replacement, " +
            "e.g. pattern \"\$obj\$.getOrder(\$id\$)\" with replacement " +
            "\"\$obj\$.fetchOrder(\$id\$)\". Prefer this over a grep followed by a series of " +
            "edits: no false positives inside strings or comments, and one call instead of one " +
            "per file. Always run it with dry_run first. NOT a rename: it rewrites the matched " +
            "expressions only, and leaves the declaration and the imports untouched - use " +
            "rename_symbol when the target is a symbol."

    override val inputSchema = Schema.obj(
        "pattern" to Schema.string("Structural search pattern, with \$name\$ placeholders."),
        "replacement" to Schema.string(
            "Replacement, reusing the pattern placeholders. Omit to only search.",
        ),
        "language" to Schema.enumOf(
            "Language of the pattern. Default: ts.",
            "ts", "tsx", "js", "jsx", "vue", "html", "css", "json", "java", "kt", "xml",
        ),
        "paths" to Schema.arrayOf(
            "Restrict the search to these files or directories. Default: the whole project.",
            Schema.string("path"),
        ),
        "dry_run" to Schema.boolean(
            "Report the matches and what each would become, without changing anything. " +
                "Default: true - pass false explicitly to apply.",
        ),
        required = listOf("pattern"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val pattern = Args.requiredString(args, "pattern")
        val replacement = Args.string(args, "replacement")
        val dryRun = Args.boolean(args, "dry_run", true)
        val extension = Args.string(args, "language") ?: "ts"

        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
        if (fileType !is LanguageFileType) {
            throw IllegalArgumentException(
                "No language known for extension '$extension' in this IDE.",
            )
        }

        val paths = Args.stringList(args, "paths")
        val scope = if (paths.isEmpty()) {
            GlobalSearchScope.projectScope(project)
        } else {
            val files = Scopes.resolve(project, paths, null, MAX_FILES)
            if (files.isEmpty()) {
                return "No file found for: ${paths.joinToString(", ")}"
            }
            GlobalSearchScope.filesScope(project, files)
        }

        // Explicit setters rather than the synthetic properties: the platform annotates these
        // getters and setters inconsistently, which makes Kotlin expose some of them read-only.
        val matchOptions = MatchOptions()
        matchOptions.setFileType(fileType)
        matchOptions.setSearchPattern(pattern)
        matchOptions.setRecursiveSearch(true)
        matchOptions.setCaseSensitiveMatch(true)
        matchOptions.setScope(scope)

        val matches = try {
            Matcher.validate(project, matchOptions)
            val sink = CollectingMatchResultSink()
            ReadAction.run<RuntimeException> {
                Matcher(project, matchOptions).findMatches(sink)
            }
            sink.matches.orEmpty()
        } catch (e: Throwable) {
            // A malformed pattern is the common case here, and the message the engine produces
            // says exactly what is wrong with it.
            throw IllegalArgumentException(
                "Invalid search pattern: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        if (matches.isEmpty()) {
            return "structural_replace: no match for the pattern in the requested scope."
        }
        if (replacement == null) {
            return render("Matches", describe(context, matches, emptyList()), dryRun = true)
        }

        val replaceOptions = ReplaceOptions(matchOptions)
        replaceOptions.setReplacement(replacement)
        val replacer = Replacer(project, replaceOptions)
        try {
            Replacer.checkReplacementPattern(project, replaceOptions)
        } catch (e: Throwable) {
            throw IllegalArgumentException(
                "Invalid replacement pattern: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        val infos = ReadAction.compute<List<ReplacementInfo>, RuntimeException> {
            matches.map { replacer.buildReplacement(it) }
        }

        // Described before anything is applied: a replacement invalidates the matched PSI
        // elements, and reading their offsets afterwards yields either garbage or an exception.
        val rows = describe(context, matches, infos)
        if (dryRun) {
            return render("Would replace", rows, dryRun = true)
        }

        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project, "Structural replace (idebridge)", null, {
                    replacer.replaceAll(infos)
                })
            } catch (e: Throwable) {
                failure = e
            }
        }
        val error = failure
        if (error != null) {
            throw IllegalStateException("Replacement failed: ${error.message}", error)
        }

        val saved = Editors.saveAll()
        return render("Replaced", rows, dryRun = false) +
            "\n$saved file(s) written to disk. Re-read them before editing them again."
    }

    /** Positions and texts, snapshotted while the matched elements are still valid. */
    private fun describe(
        context: ToolContext,
        matches: List<MatchResult>,
        infos: List<ReplacementInfo>,
    ): List<Row> {
        return ReadAction.compute<List<Row>, RuntimeException> {
            matches.mapIndexedNotNull { index, match ->
                val element = match.match ?: return@mapIndexedNotNull null
                val file = element.containingFile?.virtualFile ?: return@mapIndexedNotNull null
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: return@mapIndexedNotNull null
                Row(
                    path = context.display(file),
                    line = Locations.lineOf(document, element.textRange.startOffset),
                    before = collapse(match.matchImage ?: element.text),
                    after = infos.getOrNull(index)?.replacement?.let { collapse(it) },
                )
            }
        }
    }

    private fun render(title: String, rows: List<Row>, dryRun: Boolean): String {
        val grouped = rows.sortedWith(compareBy({ it.path }, { it.line })).groupBy { it.path }
        val out = StringBuilder()
        out.append("$title: ${rows.size} match(es) across ${grouped.size} file(s)\n\n")
        var shown = 0
        grouped.forEach { (path, group) ->
            if (shown >= MAX_SHOWN) {
                return@forEach
            }
            out.append(path).append('\n')
            group.forEach { row ->
                if (shown >= MAX_SHOWN) {
                    return@forEach
                }
                out.append("  ${row.line}: ${row.before}")
                if (row.after != null) {
                    out.append("\n      -> ${row.after}")
                }
                out.append('\n')
                shown++
            }
            out.append('\n')
        }
        if (rows.size > shown) {
            out.append("... ${rows.size - shown} match(es) not shown\n")
        }
        if (dryRun && rows.any { it.after != null }) {
            out.append("\nNothing was modified (dry_run). Re-run with dry_run=false to apply.")
        }
        return out.toString()
    }

    /** Matches span several lines; one line each keeps the report readable. */
    private fun collapse(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim().take(160)
    }

    private class Row(val path: String, val line: Int, val before: String, val after: String?)
}
