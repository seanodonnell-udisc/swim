package swim.core.auth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume

/**
 * This server receives the one OAuth redirect that the browser sends back. Linear's manifest
 * accepts only http and https redirect URIs. The desktop app therefore uses loopback, and not
 * a custom scheme.
 *
 * There is no expect declaration on purpose. Mobile uses app links and the GitHub device flow.
 * No code in commonMain needs a server.
 */
class LoopbackServer(port: Int = 0, private val path: String = "/callback") : AutoCloseable {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1)

    /** The port the OS assigned. Register this in the authorize request. */
    val port: Int get() = server.address.port

    /** The redirect URI to send to Linear. */
    val redirectUri: String get() = "http://127.0.0.1:$port$path"

    /** Waits for the redirect and returns its query parameters, `code` and `state` among them. */
    suspend fun awaitCallback(): Map<String, String> = suspendCancellableCoroutine { continuation ->
        server.createContext(path) { exchange ->
            val parameters = parseCallbackQuery(exchange.requestURI.rawQuery)
            val body = CALLBACK_HTML.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            if (!continuation.isCompleted) continuation.resume(parameters)
        }
        continuation.invokeOnCancellation { server.stop(0) }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
