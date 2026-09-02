@file:OptIn(ExperimentalForeignApi::class)

package swim.core.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import swim.core.model.NetworkError

/**
 * The Kotlin/Native loopback OAuth redirect, for the CLI. The server accepts one connection,
 * reads one request, sends one response, and then closes the socket. ktor-server would add a
 * full server dependency for one request.
 *
 * ponytail: this blocks the calling thread. The CLI has nothing else to do while the user is
 * in the browser; give it a thread if that ever stops being true.
 */
class LoopbackServer(requestedPort: Int = 0, private val path: String = "/callback") {
    private val descriptor: Int = socket(AF_INET, SOCK_STREAM, 0)
    private var boundPort: Int = 0

    /** The port the OS assigned. */
    val port: Int get() = boundPort

    /** The redirect URI to send to Linear. */
    val redirectUri: String get() = "http://127.0.0.1:$boundPort$path"

    init {
        if (descriptor < 0) throw NetworkError("Could not open a loopback socket.")
        memScoped {
            val reuse = alloc<IntVar>()
            reuse.value = 1
            setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())

            val address = alloc<sockaddr_in>()
            memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
            address.sin_family = AF_INET.convert()
            address.sin_port = toNetworkShort(requestedPort)
            address.sin_addr.s_addr = toNetworkLong(LOOPBACK_ADDRESS)
            if (bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                close(descriptor)
                throw NetworkError("Could not bind the loopback callback port.")
            }
            if (listen(descriptor, 1) != 0) {
                close(descriptor)
                throw NetworkError("Could not listen on the loopback callback port.")
            }

            val bound = alloc<sockaddr_in>()
            val length = alloc<socklen_tVar>()
            length.value = sizeOf<sockaddr_in>().convert()
            getsockname(descriptor, bound.ptr.reinterpret(), length.ptr)
            boundPort = fromNetworkShort(bound.sin_port)
        }
    }

    /** Waits for the redirect and returns its query parameters. Blocks until it arrives. */
    fun awaitCallback(timeoutSeconds: Long = 300): Map<String, String> {
        memScoped {
            val timeout = alloc<timeval>()
            timeout.tv_sec = timeoutSeconds.convert()
            timeout.tv_usec = 0.convert()
            setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        }

        val client = accept(descriptor, null, null)
        if (client < 0) {
            close(descriptor)
            throw NetworkError("The browser did not return to Swim in time.")
        }

        val request = readRequest(client)
        respond(client)
        close(client)
        close(descriptor)

        val target = request.lineSequence().firstOrNull().orEmpty().split(" ").getOrNull(1).orEmpty()
        return parseCallbackQuery(target.substringAfter('?', ""))
    }

    private fun readRequest(client: Int): String {
        val buffer = ByteArray(REQUEST_BUFFER)
        val received = buffer.usePinned { recv(client, it.addressOf(0), buffer.size.convert(), 0) }
        if (received <= 0) return ""
        return buffer.decodeToString(0, received.toInt())
    }

    private fun respond(client: Int) {
        val body = CALLBACK_HTML.encodeToByteArray()
        val head = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).encodeToByteArray()
        val message = head + body
        message.usePinned { send(client, it.addressOf(0), message.size.convert(), 0) }
    }
}

// htons and htonl are macros on Darwin, so cinterop does not expose them. Both macOS targets
// are little-endian, which makes network order a plain byte swap.
private fun toNetworkShort(value: Int): UShort {
    val short = value.toUInt() and 0xFFFFu
    return (((short and 0xFFu) shl 8) or ((short shr 8) and 0xFFu)).toUShort()
}

private fun fromNetworkShort(value: UShort): Int = toNetworkShort(value.toInt()).toInt()

private fun toNetworkLong(value: UInt): UInt =
    ((value and 0xFFu) shl 24) or
        (((value shr 8) and 0xFFu) shl 16) or
        (((value shr 16) and 0xFFu) shl 8) or
        ((value shr 24) and 0xFFu)

private const val LOOPBACK_ADDRESS: UInt = 0x7F000001u
private const val REQUEST_BUFFER = 8192
