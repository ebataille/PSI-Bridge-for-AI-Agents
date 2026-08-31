package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import dev.ebataille.idebridge.core.Editors
import dev.ebataille.idebridge.core.Locations
import dev.ebataille.idebridge.core.PathMapper
import dev.ebataille.idebridge.server.Args
import dev.ebataille.idebridge.server.Schema

/**
 * Moves or renames a file, updating imports.
 *
 * This is the refactoring that cannot be imitated by hand: every relative import pointing at the
 * moved file has to be recomputed from its own location, which no textual substitution gets right.
 */
object MoveFileTool : BridgeTool {

    override val name = "move_file"

    override val description =
        "Moves or renames a file, updating every import that references it, in both directions. " +
            "Use instead of `mv` followed by fixing import paths by hand."

    override val inputSchema = Schema.obj(
        "from" to Schema.string("File to move, absolute or project-relative."),
        "to" to Schema.string(
            "Destination: a directory (the file name is kept) or a full file path (the file is " +
                "renamed too). Missing directories are created.",
        ),
        required = listOf("from", "to"),
    )

    override fun call(context: ToolContext, args: JsonObject): String {
        val project = context.project
        DumbService.getInstance(project).waitForSmartMode()

        val fromPath = Args.requiredString(args, "from")
        val toPath = Args.requiredString(args, "to")

        val source = Locations.findFile(project, fromPath)
            ?: throw IllegalArgumentException("File not found: $fromPath")
        if (source.isDirectory) {
            throw IllegalArgumentException("move_file only handles files: $fromPath")
        }

        val destination = resolveDestination(context, toPath, source.name)
        val sourceLabel = context.display(source)

        val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
            PsiManager.getInstance(project).findFile(source)
        } ?: throw IllegalStateException("The PSI does not know about $fromPath")

        val currentParent = source.parent?.path
        val needsMove = currentParent != destination.directoryPath
        val needsRename = source.name != destination.fileName

        if (!needsMove && !needsRename) {
            return "Nothing to do: $sourceLabel is already there."
        }

        val steps = mutableListOf<String>()

        if (needsMove) {
            val targetDirectory = createDirectory(project, destination.directoryPath)
            var failure: Throwable? = null
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    MoveFilesOrDirectoriesProcessor(
                        project,
                        arrayOf(psiFile),
                        targetDirectory,
                        /* searchInComments = */ true,
                        /* searchInNonJavaFiles = */ true,
                        /* moveCallback = */ null,
                        /* prepareSuccessfulCallback = */ null,
                    ).run()
                } catch (e: Throwable) {
                    failure = e
                }
            }
            val error = failure
            if (error != null) {
                throw IllegalStateException("Move failed: ${error.message}", error)
            }
            steps += "moved to ${destination.directoryPath}"
        }

        if (needsRename) {
            var failure: Throwable? = null
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    RefactoringFactory.getInstance(project)
                        .createRename(psiFile, destination.fileName, false, false)
                        .run()
                } catch (e: Throwable) {
                    failure = e
                }
            }
            val error = failure
            if (error != null) {
                throw IllegalStateException("Rename failed: ${error.message}", error)
            }
            steps += "renamed to ${destination.fileName}"
        }

        val finalFile = psiFile.virtualFile
        val finalLabel = if (finalFile != null) {
            context.display(finalFile)
        } else {
            destination.directoryPath + "/" + destination.fileName
        }
        val saved = Editors.saveAll()
        return "$sourceLabel: ${steps.joinToString(", ")}.\nNew location: $finalLabel\n" +
            "Imports pointing at this file were updated by the IDE " +
            "($saved file(s) written to disk)."
    }

    private fun resolveDestination(context: ToolContext, rawTo: String, sourceName: String): Destination {
        val project = context.project
        val normalized = PathMapper.toLocal(rawTo).replace('\\', '/').trimEnd('/')
        val existing = Locations.findFile(project, rawTo)

        if (existing != null && existing.isDirectory) {
            return Destination(existing.path, sourceName)
        }

        val looksLikeFile = normalized.substringAfterLast('/').contains('.')
        val base = project.basePath?.trimEnd('/')
        val absolute = if (normalized.startsWith("/") || normalized.contains(":/")) {
            normalized
        } else {
            "$base/$normalized"
        }

        if (looksLikeFile) {
            return Destination(absolute.substringBeforeLast('/'), absolute.substringAfterLast('/'))
        }
        return Destination(absolute, sourceName)
    }

    private fun createDirectory(project: Project, path: String): PsiDirectory {
        var created: VirtualFile? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    created = VfsUtil.createDirectoryIfMissing(path)
                } catch (e: Throwable) {
                    failure = e
                }
            }
        }
        val error = failure
        if (error != null) {
            throw IllegalStateException("Could not create directory $path: ${error.message}", error)
        }
        val directory = created ?: throw IllegalArgumentException("Invalid destination directory: $path")
        return ReadAction.compute<PsiDirectory?, RuntimeException> {
            PsiManager.getInstance(project).findDirectory(directory)
        } ?: throw IllegalStateException("The PSI does not know about directory $path")
    }

    private data class Destination(val directoryPath: String, val fileName: String)
}
