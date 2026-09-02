package swim.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import swim.core.auth.LinearAuth
import swim.core.auth.LinearOAuth
import swim.core.auth.TokenStore
import swim.core.auth.createTokenStore
import swim.core.config.SwimConfig
import swim.core.config.loadConfig
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.model.AuthError

/** The objects every command needs. Each one is built at most once per run. */
object Runtime {
    /** The user settings. */
    val config: SwimConfig by lazy { loadConfig() }

    /** The shared HTTP client. */
    val http: HttpClient by lazy { HttpClient(Darwin) }

    /** The platform credential store. */
    val tokenStore: TokenStore by lazy { createTokenStore() }

    /** Only the refresh call is used here, and a refresh sends no redirect URI. */
    val refreshFlow: LinearOAuth by lazy { LinearOAuth(http, OAuthApps.linearClientId, REFRESH_ONLY_REDIRECT) }

    /** Linear, signed in. Throws when nobody has run `swim auth`. */
    val linear: LinearClient by lazy {
        val auth = LinearAuth.provider(tokenStore, refreshFlow)
            ?: throw AuthError("No Linear credentials. Run `swim auth`.")
        LinearClient(http, auth, config)
    }

    /** GitHub, for pull-request status. The token is optional. */
    val github: GithubClient by lazy { GithubClient(http) { tokenStore.getGithub() } }
}

private const val REFRESH_ONLY_REDIRECT = "http://127.0.0.1/callback"
