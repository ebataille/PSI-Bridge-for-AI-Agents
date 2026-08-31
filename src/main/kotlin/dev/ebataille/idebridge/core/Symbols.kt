package dev.ebataille.idebridge.core

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Resolves a symbol from a position (file, line, column).
 *
 * Symbols are addressed by position rather than by name: a name is ambiguous the moment it
 * appears twice in a project, whereas the position comes straight from what the model read
 * moments earlier.
 */
object Symbols {

    class Located(
        val element: PsiElement,
        val file: VirtualFile,
        val line: Int,
        val column: Int,
        val name: String,
        val kind: String,
    )

    /** Call off the EDT: takes the read lock itself. */
    fun resolve(project: Project, file: VirtualFile, line: Int, column: Int): PsiElement? {
        return ReadAction.compute<PsiElement?, RuntimeException> {
            val psiFile: PsiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            val document: Document = FileDocumentManager.getInstance().getDocument(file)
                ?: return@compute null
            val offset = Locations.toOffset(document, line, column)

            // On a usage: follow the reference to its declaration.
            val reference = psiFile.findReferenceAt(offset)
            val resolved = reference?.resolve()
            if (resolved != null) {
                return@compute resolved
            }
            // On the declaration itself: walk up to the nearest named element.
            val leaf = psiFile.findElementAt(offset) ?: return@compute null
            PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
        }
    }

    /** Position and label of an element, for the reports handed back to the model. */
    fun locate(element: PsiElement): Located? {
        return ReadAction.compute<Located?, RuntimeException> {
            val containing = element.containingFile ?: return@compute null
            val file = containing.virtualFile ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            // Point at the name, not at the start of the declaration: this is the position the
            // model will feed back into rename_symbol or find_usages.
            val identifier = (element as? PsiNameIdentifierOwner)?.nameIdentifier
            val offset = (identifier ?: element).textRange?.startOffset ?: return@compute null
            val name = (element as? PsiNamedElement)?.name ?: element.text.take(60)
            Located(
                element = element,
                file = file,
                line = Locations.lineOf(document, offset),
                column = Locations.columnOf(document, offset),
                name = name,
                kind = describeKind(element),
            )
        }
    }

    private fun describeKind(element: PsiElement): String {
        // No dependency on a specific language: the PSI type name already reads well
        // (JSFunction, TypeScriptClass, ES6ImportSpecifier...).
        return element.javaClass.simpleName
            .removeSuffix("Impl")
            .removePrefix("JS")
            .removePrefix("TypeScript")
            .ifBlank { "symbol" }
    }
}
