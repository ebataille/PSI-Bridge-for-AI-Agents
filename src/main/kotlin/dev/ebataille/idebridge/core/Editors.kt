package dev.ebataille.idebridge.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager

object Editors {

    /**
     * Flushes in-memory documents to disk.
     *
     * Essential after a refactoring: the IDE first edits its own documents, and an agent reading
     * the file straight afterwards would still see the old version. It is the same asymmetry that
     * makes an agent read stale content before an edit, taken in the other direction.
     */
    fun saveAll(): Int {
        val manager = FileDocumentManager.getInstance()
        val pending = manager.unsavedDocuments.size
        if (pending == 0) {
            return 0
        }
        ApplicationManager.getApplication().invokeAndWait {
            manager.saveAllDocuments()
        }
        return pending
    }
}
