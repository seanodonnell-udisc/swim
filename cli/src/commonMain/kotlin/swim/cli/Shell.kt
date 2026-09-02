@file:OptIn(ExperimentalForeignApi::class)

package swim.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.free
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.realpath

/** Runs `command` in a shell and returns its stdout. Returns null when the command fails. */
fun shell(command: String): String? {
    val pipe = popen(command, "r") ?: return null
    val output = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(READ_BUFFER)
        while (fgets(buffer, READ_BUFFER, pipe) != null) output.append(buffer.toKString())
    }
    return if (pclose(pipe) == 0) output.toString() else null
}

/** Wraps `value` in single quotes so a shell reads it as one literal word. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** The value of an environment variable, or null. */
fun env(name: String): String? = getenv(name)?.toKString()

/** True when the path exists. */
fun pathExists(path: String): Boolean = access(path, F_OK) == 0

/** The absolute path, with symlinks resolved. Returns the input when the path does not exist. */
fun absolutePath(path: String): String {
    val resolved = realpath(path, null) ?: return path
    val text = resolved.toKString()
    free(resolved)
    return text
}

/** The last path segment. */
fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** Opens a URL in the default browser. */
fun openInBrowser(url: String) {
    shell("open ${shellQuote(url)} >/dev/null 2>&1")
}

private const val READ_BUFFER = 8192
