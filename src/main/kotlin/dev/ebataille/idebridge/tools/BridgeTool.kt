package dev.ebataille.idebridge.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ebataille.idebridge.core.PathMapper
import dev.ebataille.idebridge.core.PathStyle

class ToolContext(val project: Project, val style: PathStyle) {

    private val base: String? = project.basePath?.trimEnd('/')

    /**
     * The path as handed back to the model: project-relative whenever possible.
     *
     * Shorter, easier to read, and it sidesteps the whole WSL/Windows question, since the tools
     * accept relative paths on the way in too.
     */
    fun display(file: VirtualFile): String {
        val path = file.path
        if (base != null && path.startsWith("$base/")) {
            return path.substring(base.length + 1)
        }
        return PathMapper.toClient(path, style)
    }
}

interface BridgeTool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    /** Returns the text handed to the model. Throw IllegalArgumentException for a usage error. */
    fun call(context: ToolContext, args: JsonObject): String
}

object ToolRegistry {

    private val tools: List<BridgeTool> = listOf(
        IdeStatusTool,
        SaveAllTool,
        RefreshTool,
        DiagnosticsTool,
        OutlineTool,
        FindUsagesTool,
        RenameSymbolTool,
        MoveFileTool,
        TypeInfoTool,
        FindImplementationsTool,
        FindCallersTool,
    )

    fun all(): List<BridgeTool> = tools

    fun find(name: String): BridgeTool? = tools.firstOrNull { it.name == name }
}
