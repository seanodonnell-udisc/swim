package swim.core.config

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** macOS keeps app data under Library; everything else follows XDG. */
actual fun configDir(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (os.contains("mac")) return "$home/Library/Application Support/swim"
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
    return "$xdg/swim"
}

internal actual fun readFileOrNull(path: String): String? = try {
    File(path).takeIf { it.isFile }?.readText()
} catch (e: Exception) {
    null
}

internal actual fun writePrivateFile(path: String, text: String) {
    val file = File(path)
    file.parentFile?.mkdirs()
    if (!file.exists()) file.createNewFile()
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (e: Exception) {
        // Not a POSIX filesystem. The content still has to be written.
    }
    file.writeText(text)
}
