package dev.ebataille.idebridge.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ebataille.idebridge.core.PathMapper
import dev.ebataille.idebridge.core.PathStyle
import dev.ebataille.idebridge.tools.ToolContext
import dev.ebataille.idebridge.tools.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal MCP implementation: JSON-RPC 2.0 over unary HTTP (streamable HTTP without SSE), one
 * request per POST, one response. That is enough as long as the server has no notifications to
 * push to the client, and it saves embedding yet another SDK.
 *
 * Being stateless also means a shell hook can invoke a tool with a single POST, with no session
 * handshake on every file edit.
 */
object McpServer {

    private const val PROTOCOL_VERSION = "2025-06-18"
    private val LOG = logger<McpServer>()

    /** Last path dialect seen per project, so replies come back in the same one. */
    private val styles = ConcurrentHashMap<String, PathStyle>()

    /** Returns the response body, or null for a notification (204 expected). */
    fun handle(project: Project, body: String): String? {
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?: return error(null, -32700, "invalid JSON").toString()

        if (parsed.isJsonArray) {
            val responses = JsonArray()
            parsed.asJsonArray.forEach { element ->
                val response = dispatch(project, element)
                if (response != null) {
                    responses.add(response)
                }
            }
            if (responses.isEmpty()) {
                return null
            }
            return responses.toString()
        }
        return dispatch(project, parsed)?.toString()
    }

    private fun dispatch(project: Project, element: JsonElement): JsonObject? {
        if (!element.isJsonObject) {
            return error(null, -32600, "invalid request")
        }
        val request = element.asJsonObject
        val id = request.get("id")
        val method = request.get("method")?.asString ?: return error(id, -32600, "missing method")
        val params = request.getAsJsonObject("params") ?: JsonObject()

        // A notification carries no id and expects no response.
        val isNotification = id == null || id.isJsonNull

        return when (method) {
            "initialize" -> result(id, initialize(params))
            "notifications/initialized", "notifications/cancelled" -> null
            "ping" -> result(id, JsonObject())
            "tools/list" -> result(id, toolsList())
            "tools/call" -> result(id, toolsCall(project, params))
            else -> {
                if (isNotification) {
                    null
                } else {
                    error(id, -32601, "unknown method: $method")
                }
            }
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.asString
        val payload = JsonObject()
        payload.addProperty("protocolVersion", requested ?: PROTOCOL_VERSION)

        val capabilities = JsonObject()
        capabilities.add("tools", JsonObject())
        payload.add("capabilities", capabilities)

        val info = JsonObject()
        info.addProperty("name", "idebridge")
        info.addProperty("version", "0.1.0")
        payload.add("serverInfo", info)

        payload.addProperty(
            "instructions",
            "Tools from the JetBrains IDE currently open on this project. Prefer them over their " +
                "shell equivalents: get_diagnostics replaces `tsc --noEmit` and eslint " +
                "(incremental, the TypeScript service is already warm, and it also covers IDE " +
                "inspections); find_usages replaces grep on a symbol name (real resolved " +
                "references, not text matches); rename_symbol and move_file perform genuine " +
                "refactorings that update every reference and import.",
        )
        return payload
    }

    private fun toolsList(): JsonObject {
        val tools = JsonArray()
        ToolRegistry.all().forEach { tool ->
            val node = JsonObject()
            node.addProperty("name", tool.name)
            node.addProperty("description", tool.description)
            node.add("inputSchema", tool.inputSchema)
            tools.add(node)
        }
        val payload = JsonObject()
        payload.add("tools", tools)
        return payload
    }

    private fun toolsCall(project: Project, params: JsonObject): JsonObject {
        val name = params.get("name")?.asString
            ?: return textResult("Missing tool name.", isError = true)
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        val tool = ToolRegistry.find(name)
            ?: return textResult("Unknown tool: $name", isError = true)

        val context = ToolContext(project, detectStyle(project, args))

        return try {
            textResult(tool.call(context, args), isError = false)
        } catch (e: IllegalArgumentException) {
            // Usage errors go back to the model so it can correct itself, not to the transport.
            textResult(e.message ?: "Invalid arguments.", isError = true)
        } catch (e: Throwable) {
            LOG.warn("Tool $name failed", e)
            textResult("Tool $name failed: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    /**
     * The client may speak WSL (/mnt/d/...) while the IDE speaks Windows. Infer the dialect from
     * the incoming arguments and remember it for calls that carry no path at all.
     */
    private fun detectStyle(project: Project, args: JsonObject): PathStyle {
        val key = project.locationHash
        val detected = args.entrySet()
            .asSequence()
            .flatMap { entry ->
                val value = entry.value
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> sequenceOf(value.asString)
                    value.isJsonArray -> value.asJsonArray.asSequence()
                        .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        .map { it.asString }
                    else -> emptySequence()
                }
            }
            .mapNotNull { PathMapper.detectStyle(it) }
            .firstOrNull()

        if (detected != null) {
            styles[key] = detected
            return detected
        }
        return styles[key] ?: PathStyle.NATIVE
    }

    private fun textResult(text: String, isError: Boolean): JsonObject {
        val block = JsonObject()
        block.addProperty("type", "text")
        block.addProperty("text", text)

        val content = JsonArray()
        content.add(block)

        val payload = JsonObject()
        payload.add("content", content)
        if (isError) {
            payload.addProperty("isError", true)
        }
        return payload
    }

    private fun result(id: JsonElement?, payload: JsonObject): JsonObject {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", id)
        response.add("result", payload)
        return response
    }

    private fun error(id: JsonElement?, code: Int, message: String): JsonObject {
        val error = JsonObject()
        error.addProperty("code", code)
        error.addProperty("message", message)

        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", id)
        response.add("error", error)
        return response
    }
}
