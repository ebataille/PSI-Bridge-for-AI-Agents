package dev.ebataille.idebridge.core

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.multiverse.CodeInsightContext
import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.psi.PsiFile

/**
 * Runs the IDE's highlighting chain over a file that is not open in an editor, and reads the
 * quick fixes registered on the resulting diagnostics.
 *
 * Shared by `get_diagnostics`, which reports them, and `apply_quick_fix`, which re-runs the same
 * analysis to re-resolve a fix against the current text: a `HighlightInfo` holds absolute offsets,
 * so it is stale the moment anything is edited, and caching it between the two calls would apply
 * fixes at the wrong place.
 */
object Highlighting {

    /**
     * Call off the EDT. Throws whatever the analysis throws: the caller has to surface the
     * failure, since silence would read as "no problem".
     */
    fun analyse(project: Project, psiFile: PsiFile, document: Document): List<HighlightInfo> {
        val analyzer = DaemonCodeAnalyzer.getInstance(project) as? DaemonCodeAnalyzerImpl
            ?: throw IllegalStateException("the IDE analysis engine is unavailable")

        // The passes look for the highlighting session attached to the current indicator: outside
        // an editor nobody created one, so we open it ourselves. And runMainPasses rejects any
        // indicator that is not a DaemonProgressIndicator.
        val insightContext = ReadAction.compute<CodeInsightContext, RuntimeException> {
            CodeInsightContextManager.getInstance(project).getCodeInsightContext(psiFile.viewProvider)
        }
        val indicator = DaemonProgressIndicator()
        var infos: List<HighlightInfo> = emptyList()
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
        return infos
    }

    /**
     * The quick fixes the IDE offers on a diagnostic.
     *
     * `findRegisteredQuickFix` is a search, not an iteration: it stops at the first non-null
     * result. Returning null every time turns it into the full walk we want.
     */
    fun fixes(info: HighlightInfo): List<HighlightInfo.IntentionActionDescriptor> {
        val collected = mutableListOf<HighlightInfo.IntentionActionDescriptor>()
        info.findRegisteredQuickFix<Any?> { descriptor, _ ->
            collected += descriptor
            null
        }
        return collected
    }

    /** Label as the IDE shows it in the intentions popup. Call under a read action. */
    fun label(action: IntentionAction): String {
        return try {
            action.text
        } catch (e: Throwable) {
            action.familyName
        }
    }
}
