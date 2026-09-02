package swim.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/** User settings. Tokens are NOT here: they live in the platform TokenStore. */
@Serializable
data class SwimConfig(
    val repos: List<String> = emptyList(),
    val showVersionLabels: Boolean = false,
)

/** The per-OS directory holding `config.json`, without a trailing slash. */
expect fun configDir(): String

/** The config file path. */
fun configPath(): String = "${configDir()}/config.json"

private val configJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Reads the config. Any missing file, unreadable file, or parse failure yields the defaults. */
fun loadConfig(path: String = configPath()): SwimConfig {
    val text = readFileOrNull(path) ?: return SwimConfig()
    return try {
        configJson.decodeFromString(SwimConfig.serializer(), text)
    } catch (e: Exception) {
        SwimConfig()
    }
}

/** Writes the config, creating the directory and restricting the file to the owner. */
fun saveConfig(config: SwimConfig, path: String = configPath()) {
    writePrivateFile(path, configJson.encodeToString(SwimConfig.serializer(), config) + "\n")
}

/** Reads a whole file, or null when it cannot be read. */
internal expect fun readFileOrNull(path: String): String?

/** Writes a whole file with mode 0600, creating parent directories. */
internal expect fun writePrivateFile(path: String, text: String)
