package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInsight.multiverse.CodeInsightContext
import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.javascript.integration.JSAnnotationError
import com.intellij.lang.typescript.compiler.TypeScriptService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * IDE diagnostics over an explicit set of files.
 *
 * Two sources, because neither is sufficient on its own:
 *
 *  - `runMainPasses` runs the highlighting chain over a file **even when it is not open in an
 *    editor**, and returns once the analysis is finished. That is what yields syntax errors and
 *    IDE inspections. Querying the daemon's current state instead, as the official integration
 *    does, only reports files that are open and already analysed: an empty list the rest of the
 *    time, which is worse than having no tool at all.
 *  - the TypeScript service is queried directly, because its highlighting pass is not among the
 *    ones `runMainPasses` runs. Without that call, type errors are missing — precisely what one
 *    wanted to stop running `tsc` for.
 */
object DiagnosticsTool : BridgeTool {

    private const val MAX_FILES = 60

    /** Past this point, better to return the other diagnostics than to block on tsserver. */
    private const val TYPESCRIPT_TIMEOUT_SECONDS = 60L

    /** Extensions for which an answer from the TypeScript service is expected. */
    private val TYPESCRIPT_EXTENSIONS = setOf(
        "ts", "tsx", "mts", "cts",
        "js", "jsx", "mjs", "cjs",
        "vue", "svelte",
    )

    private val LOG = logger<DiagnosticsTool>()

    override val name = "get_diagnostics"

    override val description =
        "Errors and warnings the IDE sees in the given files: TypeScript type errors (from the " +
            "tsserver service, the same source of truth as tsc), syntax errors, and IDE " +
            "inspections. Prefer this over `tsc --noEmit` and a command-line eslint: it is " +
            "incremental, the service is already warm, it also analyses files that are not open " +
            "in the editor, and it waits for the analysis to finish before answering."

    override val inputSchema = Schema.obj(
        "paths" to Schema.arrayOf(
            "Files to analyse, absolute or project-relative. Takes precedence over scope.",
            Schema.string("path"),
        ),
        "scope" to Schema.enumOf(
            "Used when paths is empty. changed = files modified according to VCS (default), " +
                "open = files open in the editor.",
            "changed",
            "open",
        ),
        "min_severity" to Schema.enumOf(
            "Severity threshold. Default: warning.",
            "error",
            "warning",
            "weak_warning",
        ),
        "max_results" to Schema.integer("Maximum diagnostics returned. Default: 100."),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project

        // An index still being built yields wrong diagnostics: better to wait.
        DumbService.getInstance(project).waitForSmartMode()

        val minSeverity = when (Args.string(args, "min_severity")) {
            "error" -> HighlightSeverity.ERROR
            "weak_warning" -> HighlightSeverity.WEAK_WARNING
            else -> HighlightSeverity.WARNING
        }
        val maxResults = Args.int(args, "max_results", 100)
        val targets = targets(context, args)

        if (targets.isEmpty()) {
            return "No file to analyse (no path given, and the requested scope is empty)."
        }

        // On a file just written outside the IDE, the VFS is still stale: both sources would then
        // fail *together* and the tool would wrongly conclude that all is well. This refresh
        // cannot be left to the caller.
        val scope = targets.take(MAX_FILES)
        ApplicationManager.getApplication().invokeAndWait {
            VfsUtil.markDirtyAndRefresh(false, true, true, *scope.toTypedArray())
            // In-memory edits must be pushed into the PSI before analysing.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val rows = mutableListOf<Row>()
        val problems = mutableSetOf<String>()
        var analyzed = 0
        var failed = 0
        scope.forEach { file ->
            val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
                PsiManager.getInstance(project).findFile(file)
            }
            if (psiFile == null) {
                failed++
                problems += "file unreadable by the IDE (${context.display(file)})"
                return@forEach
            }
            analyzed++
            listOf(
                highlightRows(context, project, file, psiFile),
                typeScriptRows(context, project, file, psiFile),
            ).forEach { outcome ->
                rows += outcome.rows
                if (outcome.problem != null) {
                    problems += outcome.problem
                }
            }
        }

        val kept = rows
            .filter { it.severity >= minSeverity }
            .distinctBy { listOf(it.path, it.line, it.column, it.message) }

        return render(kept, analyzed, targets.size, maxResults, problems, failed)
    }

    /** Syntax and inspections: the full highlighting chain, outside any editor. */
    private fun highlightRows(
        context: ToolContext,
        project: Project,
        file: VirtualFile,
        psiFile: PsiFile,
    ): SourceOutcome {
        val analyzer = DaemonCodeAnalyzer.getInstance(project) as? DaemonCodeAnalyzerImpl
            ?: return SourceOutcome(emptyList(), "the IDE analysis engine is unavailable")
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return SourceOutcome(
            emptyList(),
            "content unreadable by the IDE (${context.display(file)})",
        )

        // The passes look for the highlighting session attached to the current indicator: outside
        // an editor nobody created one, so we open it ourselves. And runMainPasses rejects any
        // indicator that is not a DaemonProgressIndicator.
        val insightContext = ReadAction.compute<CodeInsightContext, RuntimeException> {
            CodeInsightContextManager.getInstance(project).getCodeInsightContext(psiFile.viewProvider)
        }
        val indicator = DaemonProgressIndicator()
        var infos: List<HighlightInfo> = emptyList()
        try {
            ProgressManager.getInstance().runProcess(
                {
                    HighlightingSessionImpl.runInsideHighlightingSession(
                        psiFile,
                        insightContext,
                        null,
                        ProperTextRange(0, document.textLength),
                        false,
                    ) {
                        infos = analyzer.runMainPasses(psiFile, document, indicator)
                    }
                },
                indicator,
            )
        } catch (e: Throwable) {
            LOG.warn("Highlighting analysis failed on ${file.path}", e)
            // This failure has to reach the response: otherwise a broken source reads as an
            // absence of problems.
            return SourceOutcome(
                emptyList(),
                "IDE analysis failed on ${context.display(file)} " +
                    "(${e.javaClass.simpleName}): syntax and inspections were not checked",
            )
        }

        val rows = infos.mapNotNull { info ->
            val message = clean(info.description ?: info.toolTip ?: return@mapNotNull null)
            if (message.isBlank()) {
                return@mapNotNull null
            }
            Row(
                path = context.display(file),
                line = Locations.lineOf(document, info.startOffset),
                column = Locations.columnOf(document, info.startOffset),
                severity = info.severity,
                message = message,
            )
        }
        return SourceOutcome(rows, null)
    }

    /** Type errors: tsserver queried directly. */
    private fun typeScriptRows(
        context: ToolContext,
        project: Project,
        file: VirtualFile,
        psiFile: PsiFile,
    ): SourceOutcome {
        // Outside the TypeScript perimeter (markdown, json, yaml...), the absence of type
        // information is not a degradation: reporting it would cry wolf on every documentation
        // edit.
        if (file.extension?.lowercase() !in TYPESCRIPT_EXTENSIONS) {
            return SourceOutcome(emptyList(), null)
        }

        // tsserver only takes a file into account once it is open on the IDE side: on a closed
        // file the request simply never answers, until the internal timeout. So we open it
        // without focus, and close it behind us if we were the ones who opened it.
        val editors = FileEditorManager.getInstance(project)
        val alreadyOpen = editors.isFileOpen(file)
        if (!alreadyOpen) {
            ApplicationManager.getApplication().invokeAndWait {
                editors.openFile(file, false)
            }
        }

        val errors = try {
            val service = TypeScriptService.getForFile(project, file)
                ?: return SourceOutcome(
                    emptyList(),
                    "no TypeScript service is associated with this file",
                )
            // The request has to be posted the way a highlighting pass would: from the EDT
            // (otherwise it blocks against the write lock and times out) and under a read action,
            // which the EDT no longer grants implicitly. Waiting for the result stays off the EDT.
            var future: Future<List<JSAnnotationError>>? = null
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runReadAction {
                    future = service.highlight(psiFile)
                }
            }
            future?.get(TYPESCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: return SourceOutcome(
                    emptyList(),
                    "the service did not accept the request for this file",
                )
        } catch (e: Throwable) {
            LOG.info("TypeScript service unavailable on ${file.path}: ${e.javaClass.simpleName}")
            return SourceOutcome(
                emptyList(),
                "tsserver did not answer (${e.javaClass.simpleName})",
            )
        } finally {
            if (!alreadyOpen) {
                ApplicationManager.getApplication().invokeAndWait {
                    editors.closeFile(file)
                }
            }
        }

        val path = context.display(file)
        val rows = errors.mapNotNull { error ->
            val message = clean(error.description ?: return@mapNotNull null)
            if (message.isBlank()) {
                return@mapNotNull null
            }
            Row(
                path = path,
                // The service counts lines and columns from zero.
                line = error.line + 1,
                column = error.column + 1,
                severity = error.severity ?: HighlightSeverity.ERROR,
                message = message,
            )
        }
        return SourceOutcome(rows, null)
    }

    /** Daemon descriptions often arrive as HTML; the model has no use for it. */
    private fun clean(raw: String): String {
        return raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun targets(context: ToolContext, args: JsonObject): List<VirtualFile> {
        val project = context.project
        val explicit = Args.stringList(args, "paths")
        if (explicit.isNotEmpty()) {
            return explicit.mapNotNull { Locations.findFile(project, it) }.filter { !it.isDirectory }
        }

        val open = FileEditorManager.getInstance(project).openFiles.toList()
        if (Args.string(args, "scope") == "open") {
            return open
        }

        val index = ProjectFileIndex.getInstance(project)
        val changed = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            ChangeListManager.getInstance(project).affectedFiles
                .filter { it.isValid && !it.isDirectory && index.isInContent(it) }
        }
        if (changed.isNotEmpty()) {
            return changed
        }
        return open
    }

    private fun render(
        rows: List<Row>,
        analyzed: Int,
        requested: Int,
        maxResults: Int,
        problems: Set<String>,
        failed: Int,
    ): String {
        val complete = problems.isEmpty() && failed == 0
        val errors = rows.count { it.severity >= HighlightSeverity.ERROR }

        // Status line FIRST, and machine-readable: any reader — human or model — stops after the
        // opening words. "No diagnostics" as a headline while a source has failed reads as "it
        // compiles", which is exactly the misreading to avoid.
        val status = if (complete) "ok" else "incomplete"
        val header = "idebridge status=$status errors=$errors total=${rows.size} " +
            "files=$analyzed/$requested"

        val caveat = if (complete) {
            ""
        } else {
            "\n\nWARNING: analysis INCOMPLETE, the absence of diagnostics is not a clearance " +
                "(${problems.joinToString("; ")}). Verify with `tsc --noEmit` and the project's " +
                "linter."
        }

        if (rows.isEmpty()) {
            val verdict = if (complete) {
                "No diagnostics across $analyzed analysed file(s)."
            } else {
                "INDETERMINATE: no source could report on $analyzed file(s)."
            }
            return "$header\n$verdict$caveat"
        }

        val sorted = rows.sortedWith(compareBy({ it.path }, { it.line }, { it.column }))
        val shown = sorted.take(maxResults)
        val width = shown.maxOf { "${it.path}:${it.line}:${it.column}".length }

        val out = StringBuilder()
        out.append(header).append('\n')
        shown.forEach { row ->
            out.append("${row.path}:${row.line}:${row.column}".padEnd(width))
                .append("  ")
                .append(label(row.severity).padEnd(12))
                .append(row.message)
                .append('\n')
        }

        val others = rows.size - errors
        out.append('\n')
        out.append("$errors error(s), $others warning(s) across $analyzed analysed file(s)")
        if (requested > analyzed) {
            out.append(" (capped at $MAX_FILES files out of $requested requested)")
        }
        if (sorted.size > shown.size) {
            out.append(" - ${sorted.size - shown.size} diagnostic(s) not shown")
        }
        out.append('.')
        out.append(caveat)
        return out.toString()
    }

    private fun label(severity: HighlightSeverity): String {
        return when {
            severity >= HighlightSeverity.ERROR -> "error"
            severity >= HighlightSeverity.WARNING -> "warning"
            else -> "weak warning"
        }
    }

    private data class Row(
        val path: String,
        val line: Int,
        val column: Int,
        val severity: HighlightSeverity,
        val message: String,
    )

    /** Result of one diagnostics source, with the reason for any silence. */
    private class SourceOutcome(val rows: List<Row>, val problem: String?)
}
