package swim.core.auth

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import swim.core.config.configDir
import swim.core.config.readFileOrNull
import swim.core.config.writePrivateFile
import swim.core.model.AuthError

actual fun createTokenStore(): TokenStore = JvmTokenStore()

/**
 * Desktop JVM credentials. On macOS this is the same generic-password item that the native
 * builds use through KeychainSettings. The service is `swim`. The account is `swim.linear` or
 * `swim.github`. The value is the UTF-8 JSON. One sign-in therefore serves the app and the CLI.
 *
 * On other systems, and when the caller turns the keychain off, the tokens go to a 0600 JSON
 * file and the store says so on stderr. A keychain that refuses a write is an error, not a
 * reason to put the secret on disk.
 */
class JvmTokenStore(
    private val filePath: String = "${configDir()}/tokens.json",
    private val useKeychain: Boolean = isMacOs(),
    private val keychainWrite: (String) -> Boolean = ::runSecurityStdin,
) : KeyValueTokenStore() {

    init {
        if (!useKeychain) warnInsecureStorage(filePath)
    }

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
            if (keychainWrite(keychainWriteCommand(key, value))) {
                // The file is only the fallback. A copy left there by an earlier keychain outage
                // would be served as a live token the next time `security` fails.
                val entries = fileEntries()
                if (key in entries) writeFileEntries(entries - key)
                return
            }
            // A locked keychain must not divert the secret to disk behind the user's back.
            throw AuthError(
                "The macOS keychain refused to store the credential. Unlock the login keychain, " +
                    "then try again. To keep credentials in a 0600 file instead, start swim with " +
                    "-Dswim.insecureStorage=true."
            )
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

/** The argv of a `security` call that carries a secret. It holds the flag and nothing else. */
internal val SECURITY_STDIN_ARGV: List<String> = listOf(SECURITY_BINARY, "-i")

/**
 * Runs one `security` command that carries a secret. `-i` reads commands from stdin, so neither
 * this process nor `security` ever holds the secret in argv, where every local user reads it.
 */
internal fun runSecurityStdin(command: String): Boolean = try {
    val process = ProcessBuilder(SECURITY_STDIN_ARGV).start()
    process.outputStream.bufferedWriter().use { it.write(command + "\n") }
    process.inputStream.bufferedReader().readText()
    process.errorStream.bufferedReader().readText()
    process.waitFor() == 0
} catch (e: Exception) {
    false
}

/** The one line `security -i` reads to store `value`. No part of it reaches argv. */
internal fun keychainWriteCommand(key: String, value: String): String =
    "add-generic-password -U" +
        " -s ${securityQuote(KEYCHAIN_SERVICE)}" +
        " -a ${securityQuote(key)}" +
        " -w ${securityQuote(value)}"

/**
 * One argument for the `security -i` parser. It splits on spaces, so every argument needs
 * quotes, and it removes a backslash before any character, so both characters double up.
 */
private fun securityQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private var insecureWarned = false

/** One line each run. Nobody should keep credentials in a file without knowing it. */
private fun warnInsecureStorage(path: String) {
    if (insecureWarned) return
    insecureWarned = true
    System.err.println("swim: credentials are in the 0600 file $path, not in a keychain.")
}

private const val SECURITY_BINARY = "/usr/bin/security"
private val fileJson = Json { prettyPrint = true }
private val entriesSerializer = MapSerializer(String.serializer(), String.serializer())
