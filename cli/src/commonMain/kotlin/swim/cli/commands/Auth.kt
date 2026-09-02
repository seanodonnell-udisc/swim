@file:OptIn(ExperimentalForeignApi::class)

package swim.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import swim.cli.Out
import swim.cli.Runtime
import swim.cli.SwimCommand
import swim.cli.openInBrowser
import swim.cli.shell
import swim.core.auth.GithubDeviceFlow
import swim.core.auth.LinearAuthMode
import swim.core.auth.LinearOAuth
import swim.core.auth.LinearTokens
import swim.core.auth.LoopbackServer
import swim.core.auth.OAuthApps
import swim.core.auth.Pkce
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.linear.apiKeyAuth
import swim.core.model.AuthError
import platform.posix.STDIN_FILENO
import platform.posix.isatty

/**
 * `swim auth` — sign in to Linear, then offer GitHub.
 *
 * Linear uses OAuth with PKCE by default. `--key` stores a personal API key instead.
 * GitHub is optional: without it, pull-request status is absent and nothing else changes.
 */
class AuthCommand : SwimCommand("auth") {
    override fun help(context: Context) =
        "Sign in: store your Linear credentials, and connect GitHub for pull-request status"

    private val key by option(
        "--key",
        metavar = "API_KEY",
        help = "Linear personal API key instead of OAuth; `-` reads the key from stdin",
    )
    private val githubToken by option(
        "--github-token",
        metavar = "TOKEN",
        help = "connect GitHub with this token and stop; `-` reads the token from stdin",
    )
    private val noGithub by option("--no-github", help = "do not connect GitHub").flag()

    override suspend fun execute() {
        if (githubToken != null) {
            connectGithubWithToken(orStdin(githubToken!!, "GitHub token"))
            return
        }

        if (key != null) signInWithApiKey(orStdin(key!!, "Linear API key")) else signInWithOAuth()
        Out.status("Saved to the login keychain.")

        if (noGithub) {
            Out.status(GITHUB_SKIPPED)
            return
        }
        connectGithub()
    }

    /** Verifies the key with the raw-key header form, then stores it as an API-key credential. */
    private suspend fun signInWithApiKey(apiKey: String) {
        val viewer = LinearClient(Runtime.http, apiKeyAuth(apiKey), Runtime.config).getViewer()
        Runtime.tokenStore.setLinear(LinearTokens(accessToken = apiKey, mode = LinearAuthMode.API_KEY))
        Out.status("Linear: signed in as ${viewer.name} <${viewer.email}>")
    }

    /** Runs the authorization-code flow with PKCE over a loopback redirect. */
    private suspend fun signInWithOAuth() {
        val clientId = OAuthApps.linearClientId
        if (OAuthApps.isPlaceholder(clientId)) {
            throw AuthError(
                "No Linear OAuth app is registered yet. Run `swim auth --key <apiKey>`, " +
                    "or set SWIM_LINEAR_CLIENT_ID."
            )
        }

        val server = LoopbackServer(OAuthApps.LOOPBACK_PORT, OAuthApps.LOOPBACK_PATH)
        val oauth = LinearOAuth(Runtime.http, clientId, server.redirectUri)
        val verifier = Pkce.createVerifier()
        val state = Pkce.createState()
        val authorizeUrl = oauth.authorizeUrl(Pkce.challenge(verifier), state)

        Out.status("Linear: open this page to sign in.")
        Out.status(authorizeUrl)
        openInBrowser(authorizeUrl)

        val callback = server.awaitCallback(CALLBACK_TIMEOUT_SECONDS)
        callback["error"]?.let { throw AuthError("Linear refused the sign-in: $it") }
        if (callback["state"] != state) throw AuthError("The Linear callback did not match. Start again.")
        val code = callback["code"] ?: throw AuthError("The Linear callback carried no code.")

        val tokens = oauth.exchangeCode(code, verifier).copy(mode = LinearAuthMode.OAUTH)
        Runtime.tokenStore.setLinear(tokens)
        val viewer = Runtime.linear.getViewer()
        Out.status("Linear: signed in as ${viewer.name} <${viewer.email}>")
    }

    /** Stores a token the user supplied, after GitHub confirms it works. */
    private suspend fun connectGithubWithToken(token: String) {
        val login = GithubClient(Runtime.http) { token }.verifyToken()
        Runtime.tokenStore.setGithub(token)
        Out.status("GitHub: connected as $login")
    }

    /** Reuses the `gh` CLI token, then falls back to the device flow. GitHub stays optional. */
    private suspend fun connectGithub() {
        val ghToken = shell("gh auth token 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }
        if (ghToken != null) {
            try {
                connectGithubWithToken(ghToken)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Out.status("GitHub: the `gh auth token` value did not work. ${e.message}")
            }
        }

        val clientId = OAuthApps.githubClientId
        if (OAuthApps.isPlaceholder(clientId) || isatty(STDIN_FILENO) != 1) {
            Out.status(GITHUB_SKIPPED)
            return
        }

        val flow = GithubDeviceFlow(Runtime.http, clientId)
        val deviceCode = flow.requestCode()
        Out.status("GitHub: open ${deviceCode.verificationUri} and enter the code ${deviceCode.userCode}")
        Out.status("GitHub: waiting for you to approve. Press Ctrl-C to skip.")
        val token = flow.awaitToken(deviceCode)
        Runtime.tokenStore.setGithub(token)
        Out.status("GitHub: connected")
    }
}

/**
 * A secret on the command line reaches `ps` and the shell history. `-` takes it from stdin
 * instead, which is what `gh --with-token` and `docker --password-stdin` do.
 */
private fun orStdin(value: String, what: String): String {
    if (value != "-") return value
    return readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw AuthError("No $what arrived on stdin.")
}

private const val GITHUB_SKIPPED =
    "GitHub is not connected. Pull-request status will be absent. Run `swim auth --github-token <token>` to add it."

private const val CALLBACK_TIMEOUT_SECONDS = 180L
