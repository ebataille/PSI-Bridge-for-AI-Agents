package dev.ebataille.idebridge.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.ebataille.idebridge.core.BridgeService
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import java.nio.charset.StandardCharsets
import org.jetbrains.ide.HttpRequestHandler

/**
 * MCP entry point, grafted onto the IDE's built-in web server (port 63342 by default).
 *
 * We deliberately do not reuse RestService: its access control is built around IDE tokens and
 * user confirmation dialogs, which are designed for requests coming from a browser. Here the
 * secret is the per-project token carried by the URL.
 */
class BridgeHttpHandler : HttpRequestHandler() {

    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.method() == HttpMethod.POST && tokenOf(request.uri()) != null
    }

    /**
     * Authorisation rests on the path token, not on the Origin header: a CLI client sends none,
     * and requiring one would reject every legitimate caller.
     *
     * What remains to defend against is DNS rebinding, where a web page resolves its own domain
     * to the loopback address in order to talk to this server. In that case the browser puts the
     * attacker's domain name in the Host header, so we only accept a local literal — "localhost"
     * or an IP address — which no rebinding can produce. Accepting the machine's own IP
     * addresses, and not just loopback, keeps the WSL setup working.
     */
    override fun isAccessible(request: HttpRequest): Boolean {
        return isLocalLiteralHost(request.headers().get(HttpHeaderNames.HOST))
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val token = tokenOf(request.uri()) ?: return false
        val project = BridgeService.getInstance().projectFor(token)
        // The body must be copied right away: the ByteBuf is recycled as soon as we return.
        val body = request.content().toString(StandardCharsets.UTF_8)
        val keepAlive = HttpUtil.isKeepAlive(request)

        if (project == null) {
            send(context, HttpResponseStatus.NOT_FOUND, "{\"error\":\"unknown or closed project\"}", keepAlive)
            return true
        }

        // A refactoring or a diagnostics pass can take a while: hand the I/O thread back.
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload = try {
                McpServer.handle(project, body)
            } catch (e: Throwable) {
                LOG.error("MCP request failed", e)
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}"
            }
            if (payload == null) {
                send(context, HttpResponseStatus.ACCEPTED, "", keepAlive)
            } else {
                send(context, HttpResponseStatus.OK, payload, keepAlive)
            }
        }
        return true
    }

    private fun send(
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        payload: String,
        keepAlive: Boolean,
    ) {
        val content = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
        val future = context.channel().writeAndFlush(response)
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    companion object {
        const val PREFIX = "/api/claude-bridge/"

        private val LOG = logger<BridgeHttpHandler>()
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** Extracts the project token from /api/claude-bridge/<token>[?...]. */
        fun tokenOf(uri: String): String? {
            if (!uri.startsWith(PREFIX)) {
                return null
            }
            val tail = uri.substring(PREFIX.length).substringBefore('?').substringBefore('/')
            if (tail.isBlank()) {
                return null
            }
            return tail
        }

        /**
         * True when the Host header names a local literal rather than a DNS name. Anything that
         * went through name resolution is refused, which is what stops a rebinding attack.
         */
        fun isLocalLiteralHost(rawHost: String?): Boolean {
            val raw = rawHost?.trim()?.lowercase()
            if (raw.isNullOrEmpty()) {
                return false
            }
            val host = when {
                // Bracketed IPv6, with or without a port: [::1]:63342
                raw.startsWith("[") -> raw.substringAfter('[').substringBefore(']')
                // Bare IPv6 has several colons, so none of them delimits a port.
                raw.count { it == ':' } > 1 -> raw
                else -> raw.substringBefore(':')
            }
            if (host == "localhost") {
                return true
            }
            // Any IP literal is fine: an attacker's domain name never reaches us in this form.
            return IPV4.matches(host) || host.contains(':')
        }
    }
}
