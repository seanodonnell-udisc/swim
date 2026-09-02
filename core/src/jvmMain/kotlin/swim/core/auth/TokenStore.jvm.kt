package swim.core.auth

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import swim.core.config.configDir
import swim.core.config.readFileOrNull
import swim.core.config.writePrivateFile

actual fun createTokenStore(): TokenStore = JvmTokenStore()

/**
 * Desktop JVM credentials. On macOS this is the same generic-password item that the native
 * builds use through KeychainSettings. The service is `swim`. The account is `swim.linear` or
 * `swim.github`. The value is the UTF-8 JSON. One sign-in therefore serves the app and the CLI.
 *
 * On other systems, and after any `security` failure, the tokens go to a 0600 JSON file.
 */
class JvmTokenStore(
    private val filePath: String = "${configDir()}/tokens.json",
    private val useKeychain: Boolean = isMacOs(),
) : KeyValueTokenStore() {

    override fun read(key: String): String? {
        if (useKeychain) {
            security("find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", key, "-w")
                ?.trimEnd('\n')
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return fileEntries()[key]
    }

    override fun write(key: String, value: String) {
        if (useKeychain) {
            // ponytail: the secret travels in argv, which other processes of this user can read.
            // `security` has no stdin input for -w; move to a JNA Keychain binding if that matters.
            val stored = security("add-generic-password", "-U", "-s", KEYCHAIN_SERVICE, "-a", key, "-w", value)
            if (stored != null) {
                // The file is only the fallback. A copy left there by an earlier keychain outage
                // would be served as a live token the next time `security` fails.
                val entries = fileEntries()
                if (key in entries) writeFileEntries(entries - key)
                return
            }
            warnKeychainUnavailable()
        }
        writeFileEntries(fileEntries() + (key to value))
    }

    override fun remove(key: String) {
        if (useKeychain) {
            security("delete-generic-password", "-s", KEYCHAIN_SERVICE, "-a", key)
        }
        val entries = fileEntries()
        if (key in entries) writeFileEntries(entries - key)
    }

    private fun fileEntries(): Map<String, String> {
        val text = readFileOrNull(filePath) ?: return emptyMap()
        return try {
            fileJson.decodeFromString(entriesSerializer, text)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeFileEntries(entries: Map<String, String>) {
        writePrivateFile(filePath, fileJson.encodeToString(entriesSerializer, entries) + "\n")
    }

    /** Runs `/usr/bin/security` and returns its output, or null when the call failed. */
    private fun security(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf(SECURITY_BINARY) + args).start()
        val output = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        if (process.waitFor() == 0) output else null
    } catch (e: Exception) {
        null
    }
}

internal fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private var keychainWarned = false

private fun warnKeychainUnavailable() {
    if (keychainWarned) return
    keychainWarned = true
    System.err.println("swim: the macOS keychain is not available. Tokens go to a 0600 file instead.")
}

private const val SECURITY_BINARY = "/usr/bin/security"
private val fileJson = Json { prettyPrint = true }
private val entriesSerializer = MapSerializer(String.serializer(), String.serializer())
