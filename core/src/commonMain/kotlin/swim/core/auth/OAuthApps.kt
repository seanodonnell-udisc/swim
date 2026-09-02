package swim.core.auth

import swim.core.config.envVar

/**
 * The OAuth client ids. Both are PLACEHOLDERS. No OAuth app is registered yet, so a browser
 * sign-in cannot complete: the CLI tells you to use `swim auth --key`, and the app disables the
 * button and offers the API key instead. See `docs/tasks/distribution/0004-register-oauth-apps.md`.
 * An environment variable overrides the compiled-in value, which lets you test a new
 * registration without a rebuild.
 */
object OAuthApps {
    /** Replace after the Linear OAuth app exists. */
    const val LINEAR_DEFAULT: String = "PLACEHOLDER_LINEAR_CLIENT_ID"

    /** Replace after the GitHub OAuth app exists. */
    const val GITHUB_DEFAULT: String = "PLACEHOLDER_GITHUB_CLIENT_ID"

    /** The loopback port the CLI and the desktop app bind. The registered redirect names it. */
    const val LOOPBACK_PORT: Int = 8976

    /** The path the loopback server serves. */
    const val LOOPBACK_PATH: String = "/callback"

    /** The registered loopback redirect URI. It is fixed, because the registration is fixed. */
    const val REDIRECT_URI: String = "http://127.0.0.1:$LOOPBACK_PORT$LOOPBACK_PATH"

    /** The Linear client id in force. */
    val linearClientId: String get() = envVar("SWIM_LINEAR_CLIENT_ID") ?: LINEAR_DEFAULT

    /** The GitHub client id in force. */
    val githubClientId: String get() = envVar("SWIM_GITHUB_CLIENT_ID") ?: GITHUB_DEFAULT

    /** True when a real Linear OAuth app is registered, so the browser flow can run. */
    val linearConfigured: Boolean get() = !isPlaceholder(linearClientId)

    /** True when a real GitHub OAuth app is registered, so the device flow can run. */
    val githubConfigured: Boolean get() = !isPlaceholder(githubClientId)

    /** True while the id is still the compiled-in placeholder. */
    fun isPlaceholder(clientId: String): Boolean = clientId.startsWith("PLACEHOLDER_")
}
