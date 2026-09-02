package swim.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/** User settings. Tokens are NOT here: they live in the platform TokenStore. */
@Serializable
data class SwimConfig(
    val repos: List<String> = emptyList(),
    val showVersionLabels: Boolean = false,
    val repoGlobs: List<String> = DEFAULT_REPO_GLOBS,
    val identifierPattern: String = DEFAULT_IDENTIFIER_PATTERN,
)

/** The source files `swim refs` and `swim comment-cleanup` read. */
val DEFAULT_REPO_GLOBS: List<String> = listOf(
    "*.swift", "*.kt", "*.java", "*.ts", "*.tsx", "*.js",
    "*.jsx", "*.md", "*.py", "*.go", "*.rs", "*.rb",
)

/** The shape of an issue identifier in source code, for example `ENG-123`. */
const val DEFAULT_IDENTIFIER_PATTERN: String = "[A-Z]{2,10}-[0-9]+"

/** The per-OS directory holding `config.json`, without a trailing slash. */
expect fun configDir(): String

/** One environment variable, or null when it is not set. */
expect fun envVar(name: String): String?

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
