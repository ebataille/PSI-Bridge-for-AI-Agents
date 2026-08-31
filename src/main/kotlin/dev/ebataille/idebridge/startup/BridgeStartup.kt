package dev.ebataille.idebridge.startup

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ebataille.idebridge.core.BridgeService
import dev.ebataille.idebridge.server.BridgeHttpHandler
import org.jetbrains.ide.BuiltInServerManager
import java.awt.datatransfer.StringSelection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Publishes the bridge address for the current project.
 *
 * The descriptor is written to <project>/.claude/ide-bridge.json, which is where the hooks look
 * for it — no port or token to guess.
 */
class BridgeStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val basePath = project.basePath
        if (basePath == null) {
            LOG.info("Project has no base directory: bridge not published.")
            return
        }

        val token = BridgeService.getInstance().register(project)
        val port = BuiltInServerManager.getInstance().waitForStart().port
        val urls = candidateUrls(port, token)

        val descriptor = JsonObject()
        descriptor.addProperty("url", urls.first())
        descriptor.add("urls", JsonArray().also { array -> urls.forEach { array.add(it) } })
        descriptor.addProperty("port", port)
        descriptor.addProperty("token", token)
        descriptor.addProperty("ide", ApplicationInfo.getInstance().fullApplicationName)
        descriptor.addProperty("project", basePath)
        descriptor.addProperty("projectName", project.name)
        descriptor.addProperty("updatedAt", Instant.now().toString())

        val target = Path.of(basePath, ".claude", "ide-bridge.json")
        try {
            Files.createDirectories(target.parent)
            Files.writeString(target, GsonBuilder().setPrettyPrinting().create().toJson(descriptor))
            LOG.info("Bridge published at ${urls.first()}")
        } catch (e: Exception) {
            LOG.warn("Could not write $target", e)
        }

        notify(project, urls.first())
    }

    /**
     * The built-in server only listens on loopback by default. From WSL2 without mirrored
     * networking, 127.0.0.1 is the VM rather than the host, so we also publish the machine's IPv4
     * addresses and let the setup script probe them one by one.
     */
    private fun candidateUrls(port: Int, token: String): List<String> {
        val suffix = "${BridgeHttpHandler.PREFIX}$token"
        val urls = mutableListOf("http://127.0.0.1:$port$suffix")
        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .distinct()
                .forEach { urls += "http://$it:$port$suffix" }
        } catch (e: Exception) {
            LOG.debug("Could not enumerate network interfaces", e)
        }
        return urls
    }

    private fun notify(project: Project, url: String) {
        // Never "ide": Claude Code reserves that name for its own native IDE integration, and the
        // bridge would simply disappear behind it.
        val command = "claude mcp add --transport http idebridge $url"
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude IDE Bridge")
            .createNotification(
                "PSI Bridge is running",
                "Address published in .claude/ide-bridge.json. " +
                    "Run hooks/setup-claude.mjs, or configure your MCP client manually.",
                NotificationType.INFORMATION,
            )
        notification.addAction(
            object : AnAction("Copy Command") {
                override fun actionPerformed(e: AnActionEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(command))
                }
            },
        )
        notification.notify(project)
    }

    companion object {
        private val LOG = logger<BridgeStartup>()
    }
}
