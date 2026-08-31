package dev.ebataille.idebridge.core

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Token -> project directory. The token lives in the MCP URL path and is both the identifier of
 * the target project and the secret authorising the call.
 */
@Service(Service.Level.APP)
class BridgeService {

    private val byToken = ConcurrentHashMap<String, Project>()

    fun register(project: Project): String {
        val token = tokenFor(project)
        byToken[token] = project
        return token
    }

    fun unregister(project: Project) {
        byToken.entries.removeIf { it.value == project }
    }

    fun projectFor(token: String): Project? {
        val project = byToken[token] ?: return null
        if (project.isDisposed) {
            byToken.remove(token)
            return null
        }
        return project
    }

    /**
     * The token is stable across restarts: otherwise the MCP client would have to be
     * reconfigured every time the IDE restarts.
     */
    private fun tokenFor(project: Project): String {
        val properties = PropertiesComponent.getInstance(project)
        val existing = properties.getValue(TOKEN_KEY)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        properties.setValue(TOKEN_KEY, token)
        return token
    }

    companion object {
        private const val TOKEN_KEY = "dev.ebataille.idebridge.token"

        fun getInstance(): BridgeService = service()
    }
}
