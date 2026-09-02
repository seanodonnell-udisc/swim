@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package swim.core.config

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSHomeDirectory
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy

/** Both iOS and macOS keep app data under Library. On iOS this is inside the app container. */
actual fun configDir(): String = NSHomeDirectory() + "/Library/Application Support/swim"

actual fun envVar(name: String): String? = getenv(name)?.toKString()

internal actual fun readFileOrNull(path: String): String? =
    NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()

internal actual fun writePrivateFile(path: String, text: String) {
    val manager = NSFileManager.defaultManager
    val directory = path.substringBeforeLast('/', "")
    if (directory.isNotEmpty()) {
        // The desktop app may put tokens.json here. Other users may not even list the directory.
        val directoryAttributes = mapOf<Any?, Any?>(NSFilePosixPermissions to OWNER_ONLY_DIRECTORY)
        manager.createDirectoryAtPath(directory, true, directoryAttributes, null)
    }
    val attributes = mapOf<Any?, Any?>(NSFilePosixPermissions to OWNER_ONLY)
    manager.createFileAtPath(path, text.encodeToByteArray().toNSData(), attributes)
    manager.setAttributes(attributes, path, null)
}

private const val OWNER_ONLY = 384 // 0600
private const val OWNER_ONLY_DIRECTORY = 448 // 0700

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), source, size.convert()) }
    return out
}
