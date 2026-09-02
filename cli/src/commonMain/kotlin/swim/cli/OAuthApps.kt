package swim.cli

/**
 * The OAuth client ids. Both are PLACEHOLDERS. No OAuth app is registered yet, so `swim auth`
 * without `--key` cannot complete. See `docs/tasks/distribution/0004-register-oauth-apps.md`.
 * An environment variable overrides the compiled-in value, which lets you test a new
 * registration without a rebuild.
 */
object OAuthApps {
    /** Replace after the Linear OAuth app exists. */
    const val LINEAR_DEFAULT: String = "PLACEHOLDER_LINEAR_CLIENT_ID"

    /** Replace after the GitHub OAuth app exists. */
    const val GITHUB_DEFAULT: String = "PLACEHOLDER_GITHUB_CLIENT_ID"

    /** The Linear client id in force. */
    val linearClientId: String get() = env("SWIM_LINEAR_CLIENT_ID") ?: LINEAR_DEFAULT

    /** The GitHub client id in force. */
    val githubClientId: String get() = env("SWIM_GITHUB_CLIENT_ID") ?: GITHUB_DEFAULT

    /** True while the id is still the compiled-in placeholder. */
    fun isPlaceholder(clientId: String): Boolean = clientId.startsWith("PLACEHOLDER_")
}
