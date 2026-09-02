package swim.core.config

import swim.core.androidContext
import java.io.File

/** The app's private files directory, which Android already keeps inaccessible to other apps. */
actual fun configDir(): String = File(androidContext().filesDir, "swim").absolutePath

actual fun envVar(name: String): String? = System.getenv(name)

internal actual fun readFileOrNull(path: String): String? = try {
    File(path).takeIf { it.isFile }?.readText()
} catch (e: Exception) {
    null
}

// No explicit mode: java.nio.file needs API 26, and everything under filesDir is already
// private to this app.
internal actual fun writePrivateFile(path: String, text: String) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(text)
}
