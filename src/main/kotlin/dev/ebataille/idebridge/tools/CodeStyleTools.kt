package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Scopes
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema
import java.io.File

/**
 * Shared plumbing for the two code-style processors.
 *
 * Both are layout processors: same target resolution, same before/after comparison, and the same
 * obligation to flush to disk afterwards, since they only ever edit in-memory documents.
 */
private object LayoutRun {

    /** Beyond this the call stops being "the change I am working on" and becomes a mass rewrite. */
    const val MAX_FILES = 200

    class Outcome(val changed: List<String>, val untouched: Int, val skipped: List<String>)

    fun collect(context: ToolContext, args: JsonObject): List<PsiFile> {
        val project = context.project
        val files = Scopes.resolve(
            project,
            Args.stringList(args, "paths"),
            Args.string(args, "scope"),
            MAX_FILES,
        )
        return ReadAction.compute<List<PsiFile>, RuntimeException> {
            files
                .filter { it.isValid && !it.isDirectory && it.isWritable }
                .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                .filter { it.isWritable }
        }
    }

    /** Snapshot, run, compare: the model needs to know which files it now has to re-read. */
    fun apply(context: ToolContext, files: List<PsiFile>, body: (Array<PsiFile>) -> Unit): Outcome {
        val project = context.project
        val documents = FileDocumentManager.getInstance()
        val before = mutableMapOf<VirtualFile, String>()
        val skipped = mutableListOf<String>()
        val targets = mutableListOf<PsiFile>()

        ReadAction.run<RuntimeException> {
            files.forEach { psiFile ->
                val virtualFile = psiFile.virtualFile
                val document = virtualFile?.let { documents.getDocument(it) }
                if (virtualFile == null || document == null) {
                    skipped += psiFile.name
                } else {
                    before[virtualFile] = document.text
                    targets += psiFile
                }
            }
        }
        if (targets.isEmpty()) {
            return Outcome(emptyList(), 0, skipped)
        }

        ApplicationManager.getApplication().invokeAndWait {
            // These processors work on the PSI: anything still sitting in a document has to be
            // committed first, or the pending edit is silently reverted.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            body(targets.toTypedArray())
        }

        val changed = mutableListOf<String>()
        ReadAction.run<RuntimeException> {
            targets.forEach { psiFile ->
                val virtualFile = psiFile.virtualFile ?: return@forEach
                val text = documents.getDocument(virtualFile)?.text ?: return@forEach
                if (text != before[virtualFile]) {
                    changed += context.display(virtualFile)
                }
            }
        }
        return Outcome(changed.sorted(), targets.size - changed.size, skipped)
    }

    fun render(action: String, outcome: Outcome, extra: String = ""): String {
        val out = StringBuilder()
        if (outcome.changed.isEmpty()) {
            out.append("$action: nothing to change across ${outcome.untouched} file(s).")
        } else {
            out.append("$action: ${outcome.changed.size} file(s) modified")
            out.append(" (${outcome.untouched} already clean):\n")
            outcome.changed.forEach { out.append("  $it\n") }
            val saved = Editors.saveAll()
            out.append("\n$saved file(s) written to disk. Re-read them before editing them again.")
        }
        if (outcome.skipped.isNotEmpty()) {
            out.append("\nSkipped (no editable document): ${outcome.skipped.joinToString(", ")}")
        }
        if (extra.isNotEmpty()) {
            out.append("\n").append(extra)
        }
        return out.toString()
    }
}

/**
 * Import cleanup through the IDE optimizer.
 *
 * Removes what became unused, merges duplicates and reorders according to the project import
 * layout, resolving module specifiers through the real TypeScript path mapping. That last part is
 * what no textual pass can reproduce.
 */
object OptimizeImportsTool : BridgeTool {

    override val name = "optimize_imports"

    override val description =
        "Removes unused imports and reorders the remaining ones according to the project code " +
            "style, over a set of files. Run it after moving code around instead of pruning " +
            "import blocks by hand."

    override val inputSchema = Schema.obj(
        "paths" to Schema.arrayOf(Scopes.PATHS_DESCRIPTION, Schema.string("path")),
        "scope" to Schema.enumOf(Scopes.SCOPE_DESCRIPTION, "changed", "open"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        // Deciding that an import is unused means resolving it: on a partial index the optimizer
        // deletes imports that are in fact referenced.
        DumbService.getInstance(project).waitForSmartMode()

        val files = LayoutRun.collect(context, args)
        if (files.isEmpty()) {
            return "No file to process (no path given, and the requested scope is empty)."
        }
        val outcome = LayoutRun.apply(context, files) { targets ->
            OptimizeImportsProcessor(project, targets, "Optimize imports (idebridge)", null).run()
        }
        return LayoutRun.render("optimize_imports", outcome)
    }
}

/**
 * Reformatting, restricted by default to the lines the current change actually touched.
 *
 * Reformatting whole files is how an agent turns a three-line fix into an unreviewable diff. The
 * processor has a mode that only reformats the ranges differing from the VCS revision, and that
 * is the one that makes this safe to run unattended.
 */
object FormatCodeTool : BridgeTool {

    /** Extensions Prettier owns in a typical front-end project. */
    private val PRETTIER_EXTENSIONS = setOf(
        "ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs",
        "vue", "svelte", "css", "scss", "less", "json", "md", "mdx", "yaml", "yml", "html",
    )

    private val PRETTIER_CONFIGS = listOf(
        ".prettierrc", ".prettierrc.json", ".prettierrc.json5", ".prettierrc.yaml",
        ".prettierrc.yml", ".prettierrc.toml", ".prettierrc.js", ".prettierrc.cjs",
        ".prettierrc.mjs", ".prettierrc.ts", "prettier.config.js", "prettier.config.cjs",
        "prettier.config.mjs", "prettier.config.ts",
    )

    override val name = "format_code"

    override val description =
        "Reformats code with the project code style. By default only the lines modified since " +
            "the last VCS revision are touched, so the diff stays reviewable; set whole_file to " +
            "reformat entire files."

    override val inputSchema = Schema.obj(
        "paths" to Schema.arrayOf(Scopes.PATHS_DESCRIPTION, Schema.string("path")),
        "scope" to Schema.enumOf(Scopes.SCOPE_DESCRIPTION, "changed", "open"),
        "whole_file" to Schema.boolean(
            "Reformat entire files instead of only the lines changed since the last VCS " +
                "revision. Default: false. Expect a large diff on legacy files.",
        ),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val wholeFile = Args.boolean(args, "whole_file", false)
        val files = LayoutRun.collect(context, args)
        if (files.isEmpty()) {
            return "No file to process (no path given, and the requested scope is empty)."
        }

        val outcome = LayoutRun.apply(context, files) { targets ->
            ReformatCodeProcessor(
                project,
                targets,
                "Reformat (idebridge)",
                null,
                !wholeFile,
            ).run()
        }
        val warnings = listOf(prettierWarning(project, files), editorConfigWarning(project, files))
            .filter { it.isNotEmpty() }
        return LayoutRun.render("format_code", outcome, warnings.joinToString("\n"))
    }

    /**
     * Prettier and the IDE formatter disagree, and on a front-end repo Prettier is the source of
     * truth the CI enforces. Silently applying the IDE style there produces a diff the pipeline
     * rejects, so the model is told rather than left to find out.
     *
     * Read off the disk rather than through the VFS: a config file that arrived with a git pull,
     * or that an agent just wrote, is invisible to the VFS until the IDE refreshes, and a warning
     * that fails to appear is worse than one that appears twice.
     */
    private fun prettierWarning(project: Project, files: List<PsiFile>): String {
        val base = project.basePath?.let { File(it) } ?: return ""
        val config = PRETTIER_CONFIGS.firstOrNull { File(base, it).isFile }
            ?: if (packageJsonMentionsPrettier(base)) "package.json" else return ""

        val owned = ReadAction.compute<Int, RuntimeException> {
            files.count { it.virtualFile?.extension?.lowercase() in PRETTIER_EXTENSIONS }
        }
        if (owned == 0) {
            return ""
        }
        return "NOTE: this project configures Prettier ($config) and $owned formatted file(s) " +
            "fall under it. The IDE formatter only delegates to Prettier when the setting " +
            "Run Prettier on Reformat Code is enabled; otherwise the result may not match what " +
            "the project prettier check expects."
    }

    /**
     * Without an .editorconfig the IDE applies its own global defaults, which routinely disagree
     * with the indentation already in the file. Combined with the changed-lines-only default that
     * leaves a file indented two different ways, so the model is told to look at the diff rather
     * than assume the formatting is now canonical.
     */
    private fun editorConfigWarning(project: Project, files: List<PsiFile>): String {
        val base = project.basePath?.let { File(it).absoluteFile } ?: return ""
        val covered = ReadAction.compute<Boolean, RuntimeException> {
            files.any { psiFile ->
                var directory = psiFile.virtualFile?.let { File(it.path).absoluteFile.parentFile }
                while (directory != null) {
                    if (File(directory, ".editorconfig").isFile) {
                        return@any true
                    }
                    if (directory == base) {
                        return@any false
                    }
                    directory = directory.parentFile
                }
                false
            }
        }
        if (covered) {
            return ""
        }
        return "NOTE: no .editorconfig covers these files, so the IDE global code style was " +
            "applied. It may disagree with the indentation already in the file - check the diff " +
            "before committing."
    }

    private fun packageJsonMentionsPrettier(base: File): Boolean {
        val packageJson = File(base, "package.json")
        if (!packageJson.isFile) {
            return false
        }
        return try {
            packageJson.readText().contains("\"prettier\"")
        } catch (e: Throwable) {
            false
        }
    }
}
