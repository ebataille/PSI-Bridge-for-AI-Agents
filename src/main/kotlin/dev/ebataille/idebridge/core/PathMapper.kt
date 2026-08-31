package dev.ebataille.idebridge.core

import com.intellij.openapi.util.SystemInfo

/**
 * Path dialect spoken by the client.
 *
 * Coding agents very often run inside WSL while the IDE runs on Windows: paths arrive as
 * /mnt/d/project/src/x.ts and must go back out in the same dialect, otherwise the model receives
 * paths it can neither read back nor quote.
 */
enum class PathStyle {
    NATIVE,
    WSL,
}

object PathMapper {

    private const val BACKSLASH = '\\'

    private val WSL_MOUNT = Regex("^/mnt/([a-zA-Z])(/.*)?$")
    private val WINDOWS_DRIVE = Regex("^([a-zA-Z]):[\\\\/](.*)$", RegexOption.DOT_MATCHES_ALL)

    /** Infers the dialect of a client-supplied path, or null when undecidable. */
    fun detectStyle(raw: String?): PathStyle? {
        if (raw.isNullOrBlank()) {
            return null
        }
        if (WSL_MOUNT.matches(raw)) {
            return PathStyle.WSL
        }
        if (WINDOWS_DRIVE.matches(raw)) {
            return PathStyle.NATIVE
        }
        return null
    }

    /** Client-supplied path -> path the IDE can work with. */
    fun toLocal(raw: String): String {
        val path = raw.trim()
        if (!SystemInfo.isWindows) {
            return path
        }
        val mount = WSL_MOUNT.matchEntire(path)
        if (mount != null) {
            val drive = mount.groupValues[1].uppercase()
            val rest = mount.groupValues[2].trimStart('/').replace('/', BACKSLASH)
            return drive + ":" + BACKSLASH + rest
        }
        return path
    }

    /**
     * IDE-local path -> path the client knows how to read back.
     *
     * Note: the IntelliJ VFS always exposes `/` separators, even on Windows.
     */
    fun toClient(local: String, style: PathStyle): String {
        if (!SystemInfo.isWindows) {
            return local
        }
        if (style == PathStyle.NATIVE) {
            return local.replace('/', BACKSLASH)
        }
        val drive = WINDOWS_DRIVE.matchEntire(local) ?: return local.replace(BACKSLASH, '/')
        return "/mnt/" + drive.groupValues[1].lowercase() + "/" + drive.groupValues[2].replace(BACKSLASH, '/')
    }
}
