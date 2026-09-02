@file:OptIn(ExperimentalTime::class)

package swim.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Linear OAuth credentials. Access tokens last 24 hours; the refresh token renews them. */
@Serializable
data class LinearTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Instant,
) {
    /** True when the access token is spent, or close enough that a call would race the expiry. */
    fun isExpired(skew: Duration = 60.seconds, now: Instant = Clock.System.now()): Boolean =
        now + skew >= expiresAt
}

/** Per-provider credential storage, backed by the platform secret store. */
interface TokenStore {
    fun getLinear(): LinearTokens?
    fun setLinear(tokens: LinearTokens)
    fun clearLinear()
    fun getGithub(): String?
    fun setGithub(token: String)
    fun clearGithub()
}

/** The platform TokenStore: Keychain on Apple targets, `security` on macOS JVM, Keystore on Android. */
expect fun createTokenStore(): TokenStore

/**
 * A TokenStore with three string operations. Each platform writes only its storage code.
 * Every platform then stores the same bytes for the same value.
 */
abstract class KeyValueTokenStore : TokenStore {
    protected abstract fun read(key: String): String?
    protected abstract fun write(key: String, value: String)
    protected abstract fun remove(key: String)

    final override fun getLinear(): LinearTokens? = read(LINEAR_KEY)?.let(::decodeLinear)
    final override fun setLinear(tokens: LinearTokens) = write(LINEAR_KEY, encodeLinear(tokens))
    final override fun clearLinear() = remove(LINEAR_KEY)
    final override fun getGithub(): String? = read(GITHUB_KEY)?.let(::decodeGithub)
    final override fun setGithub(token: String) = write(GITHUB_KEY, encodeGithub(token))
    final override fun clearGithub() = remove(GITHUB_KEY)
}

/** Keychain account name for the Linear credentials. Shared by the macOS app and the CLI. */
const val LINEAR_KEY: String = "swim.linear"

/** Keychain account name for the GitHub token. Shared by the macOS app and the CLI. */
const val GITHUB_KEY: String = "swim.github"

/** Keychain service name for every Swim item. */
const val KEYCHAIN_SERVICE: String = "swim"

@Serializable
private data class StoredGithubToken(val token: String)

private val tokenJson = Json { ignoreUnknownKeys = true }

internal fun encodeLinear(tokens: LinearTokens): String =
    tokenJson.encodeToString(LinearTokens.serializer(), tokens)

internal fun decodeLinear(stored: String): LinearTokens? = try {
    tokenJson.decodeFromString(LinearTokens.serializer(), stored)
} catch (e: Exception) {
    null
}

internal fun encodeGithub(token: String): String =
    tokenJson.encodeToString(StoredGithubToken.serializer(), StoredGithubToken(token))

internal fun decodeGithub(stored: String): String? = try {
    tokenJson.decodeFromString(StoredGithubToken.serializer(), stored).token
} catch (e: Exception) {
    null
}
