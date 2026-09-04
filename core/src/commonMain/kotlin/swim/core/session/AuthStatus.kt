package swim.core.session

import swim.core.auth.LinearAuthMode
import swim.core.auth.TokenStore
import swim.core.config.envVar
import swim.core.github.DEMO_PRS_ENV
import swim.core.github.demoPrsConfigured

/** How the stored Linear credential authorizes a request. */
enum class AuthMode { LINEAR_OAUTH, API_KEY, NONE }

/** What the app knows about the stored credentials before it makes a call. */
data class AuthStatus(
    val configured: Boolean,
    val mode: AuthMode,
    val githubConfigured: Boolean,
)

/**
 * Reads the credential store. Linear is required; GitHub is optional and only adds PR chips.
 * GitHub also counts as configured in demo mode, so the derive-from-PRs toggle needs no token.
 */
fun authStatus(tokenStore: TokenStore, demoPrsPath: String? = envVar(DEMO_PRS_ENV)): AuthStatus {
    val linear = tokenStore.getLinear()
    return AuthStatus(
        configured = linear != null,
        mode = when (linear?.mode) {
            LinearAuthMode.OAUTH -> AuthMode.LINEAR_OAUTH
            LinearAuthMode.API_KEY -> AuthMode.API_KEY
            null -> AuthMode.NONE
        },
        githubConfigured = tokenStore.getGithub() != null || demoPrsConfigured(demoPrsPath),
    )
}
