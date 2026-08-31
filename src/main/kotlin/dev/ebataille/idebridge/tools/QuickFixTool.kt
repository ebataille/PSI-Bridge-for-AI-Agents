package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Highlighting
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Applies the IDE quick fixes that `get_diagnostics` already listed.
 *
 * The point is the batch. A tool that applied one fix per call would be slower than letting the
 * model edit the file itself: it already read the error, it already knows the correction, and it
 * would be paying two round trips instead of one to have it applied. Taking N fixes at once is
 * what turns a fifteen-turn "compile, read error, edit, recompile" loop into a single call.
 *
 * Each fix is re-resolved against a fresh analysis right before being applied, one at a time. A
 * `HighlightInfo` holds absolute offsets and every applied fix shifts them - adding an import
 * moves the entire file down - so a batch resolved up front would land its later fixes in the
 * wrong place.
 */
object QuickFixTool : BridgeTool {

    private const val MAX_FIXES = 60

    override val name = "apply_quick_fix"

    override val description =
        "Applies IDE quick fixes, several at once. Feed it the `fix:` lines returned by " +
            "get_diagnostics, at the file and line of their diagnostic. Prefer this over " +
            "correcting the errors by hand when the IDE already offers the fix: one call " +
            "replaces the whole edit-recompile loop."

    override val inputSchema = Schema.obj(
        "fixes" to Schema.arrayOf(
            "Fixes to apply, in order. Up to $MAX_FIXES per call.",
            Schema.obj(
                "file" to Schema.string("File, absolute or project-relative."),
                "line" to Schema.integer("Line of the diagnostic, 1-based."),
                "fix" to Schema.string(
                    "Label of the fix, as printed by get_diagnostics. A distinctive fragment is " +
                        "enough; matching is case-insensitive.",
                ),
                required = listOf("file", "line", "fix"),
            ),
        ),
        "dry_run" to Schema.boolean(
            "Report what would be applied, and the fixes available at each position, without " +
                "changing anything. Default: false.",
        ),
        required = listOf("fixes"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val requests = Args.objectList(args, "fixes")
        if (requests.isEmpty()) {
            throw IllegalArgumentException("fixes is required and must not be empty.")
        }
        if (requests.size > MAX_FIXES) {
            throw IllegalArgumentException(
                "Too many fixes in one call (${requests.size} > $MAX_FIXES). Split the batch.",
            )
        }
        val dryRun = Args.boolean(args, "dry_run", false)

        val openedByUs = mutableSetOf<VirtualFile>()
        val report = StringBuilder()
        var applied = 0
        var failed = 0

        try {
            requests.forEach { request ->
                val outcome = handle(context, request, dryRun, openedByUs)
                if (outcome.ok) {
                    applied++
                } else {
                    failed++
                }
                report.append(outcome.line).append('\n')
            }
        } finally {
            closeAll(project, openedByUs)
        }

        val verb = if (dryRun) "applicable" else "applied"
        val head = "apply_quick_fix: $applied $verb, $failed failed"
        val tail = if (dryRun || applied == 0) {
            ""
        } else {
            val saved = Editors.saveAll()
            "\n$saved file(s) written to disk. Re-read them before editing them again."
        }
        return "$head\n$report$tail"
    }

    private class Outcome(val ok: Boolean, val line: String)

    private fun handle(
        context: ToolContext,
        request: JsonObject,
        dryRun: Boolean,
        openedByUs: MutableSet<VirtualFile>,
    ): Outcome {
        val project = context.project
        val path = Args.requiredString(request, "file")
        val line = Args.int(request, "line", 0)
        val wanted = Args.requiredString(request, "fix")

        val file = Locations.findFile(project, path)
            ?: return Outcome(false, "  FAIL $path:$line - file not found")
        val label = "${context.display(file)}:$line"

        val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
            PsiManager.getInstance(project).findFile(file)
        } ?: return Outcome(false, "  FAIL $label - not readable by the IDE")
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return Outcome(false, "  FAIL $label - no document")

        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val infos = try {
            Highlighting.analyse(project, psiFile, document)
        } catch (e: Throwable) {
            return Outcome(false, "  FAIL $label - analysis failed (${e.javaClass.simpleName})")
        }

        val candidates = ReadAction.compute<List<Candidate>, RuntimeException> {
            infos
                .filter { Locations.lineOf(document, it.startOffset) == line }
                .flatMap { info ->
                    Highlighting.fixes(info).map { descriptor ->
                        Candidate(
                            action = descriptor.action,
                            label = Highlighting.label(descriptor.action),
                            offset = info.startOffset,
                        )
                    }
                }
                .filter { it.label.isNotBlank() }
        }

        if (candidates.isEmpty()) {
            return Outcome(false, "  FAIL $label - no quick fix at this line (stale diagnostic?)")
        }
        val chosen = match(candidates, wanted)
            ?: return Outcome(
                false,
                "  FAIL $label - no fix matching \"$wanted\". Available: " +
                    candidates.map { it.label }.distinct().joinToString(" | "),
            )

        if (dryRun) {
            return Outcome(true, "  OK   $label - would apply \"${chosen.label}\"")
        }

        val editor = editorFor(project, file, chosen.offset, openedByUs)
            ?: return Outcome(false, "  FAIL $label - no editor could be opened for the fix")

        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val available = ApplicationManager.getApplication().runReadAction<Boolean> {
                    chosen.action.isAvailable(project, editor, psiFile)
                }
                if (!available) {
                    failure = IllegalStateException("the fix is no longer applicable")
                    return@invokeAndWait
                }
                // Most fixes declare that they want the write lock taken for them; the ones that
                // do not take it themselves, and wrapping them would deadlock.
                if (chosen.action.startInWriteAction()) {
                    WriteCommandAction.runWriteCommandAction(project, "Quick fix (idebridge)", null, {
                        chosen.action.invoke(project, editor, psiFile)
                    }, psiFile)
                } else {
                    CommandProcessor.getInstance().executeCommand(
                        project,
                        { chosen.action.invoke(project, editor, psiFile) },
                        "Quick fix (idebridge)",
                        null,
                    )
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            } catch (e: Throwable) {
                failure = e
            }
        }

        val error = failure
        if (error != null) {
            return Outcome(false, "  FAIL $label - ${error.message ?: error.javaClass.simpleName}")
        }
        return Outcome(true, "  OK   $label - ${chosen.label}")
    }

    /**
     * Exact label first, then a fragment.
     *
     * Quick fix labels embed the symbol they act on ("Import 'OrderService' from ..."), so a model
     * quoting them back from a slightly older diagnostic will be close but not identical. Refusing
     * on a mismatch would cost a round trip for nothing.
     */
    private fun match(candidates: List<Candidate>, wanted: String): Candidate? {
        val needle = wanted.trim().lowercase()
        return candidates.firstOrNull { it.label.lowercase() == needle }
            ?: candidates.firstOrNull { it.label.lowercase().startsWith(needle) }
            ?: candidates.firstOrNull { it.label.lowercase().contains(needle) }
            ?: candidates.firstOrNull { needle.contains(it.label.lowercase()) }
    }

    /**
     * Intentions are written against an editor: many read the caret, and a null editor makes them
     * either bail out or throw. Opening the file without focus is the cheapest way to give them a
     * real one, and it is the same trick the TypeScript path already uses.
     */
    private fun editorFor(
        project: Project,
        file: VirtualFile,
        offset: Int,
        openedByUs: MutableSet<VirtualFile>,
    ): Editor? {
        val manager = FileEditorManager.getInstance(project)
        var editor: Editor? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (!manager.isFileOpen(file)) {
                openedByUs += file
            }
            editor = manager.openTextEditor(OpenFileDescriptor(project, file, offset), false)
        }
        return editor
    }

    private fun closeAll(project: Project, files: Set<VirtualFile>) {
        if (files.isEmpty()) {
            return
        }
        val manager = FileEditorManager.getInstance(project)
        ApplicationManager.getApplication().invokeAndWait {
            files.forEach { manager.closeFile(it) }
        }
    }

    private class Candidate(val action: IntentionAction, val label: String, val offset: Int)
}
