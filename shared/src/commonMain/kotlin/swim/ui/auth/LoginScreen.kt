package swim.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import swim.core.auth.GithubDeviceCode
import swim.core.auth.GithubDeviceFlow
import swim.core.auth.LinearAuthMode
import swim.core.auth.LinearTokens
import swim.core.auth.OAuthApps
import swim.core.auth.TokenStore
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.linear.apiKeyAuth
import swim.core.linear.oauthAuth
import swim.ui.app.SwimButton
import swim.ui.app.SwimEnv
import swim.ui.app.SwimTextField
import swim.ui.graph.Swim

/**
 * Sign-in. Linear is required and comes first; GitHub is offered afterwards and can be skipped.
 * [onDone] runs once the stored credentials are ready for the graph.
 */
@Composable
fun LoginScreen(env: SwimEnv, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var linearName by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(Swim.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Swim", color = Swim.Text, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text(
                text = "See what blocks what, and unblock it.",
                color = Swim.TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.size(4.dp))

            if (linearName == null) {
                LinearCard(env, scope) { linearName = it }
            } else {
                Card("Linear") {
                    StatusLine("Signed in as $linearName", Swim.Green)
                }
                GithubCard(env, scope, onDone = onDone)
            }
        }
    }
}

/** The Linear card: the browser flow when an OAuth app exists, the API key at all times. */
@Composable
private fun LinearCard(env: SwimEnv, scope: CoroutineScope, onSignedIn: (String) -> Unit) {
    val oauthReady = OAuthApps.linearConfigured && env.linearBrowserSignIn != null
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish(tokens: LinearTokens) {
        env.tokenStore.setLinear(tokens)
    }

    Card("Linear") {
        Text(
            text = "Swim reads your issues and their relations, and writes the relations you change.",
            color = Swim.TextMuted,
            fontSize = 12.sp,
        )

        if (waiting != null) {
            StatusLine("Waiting for the browser. Approve Swim in the page that opened.", Swim.Accent)
            SwimButton("Cancel", { waiting?.cancel() })
        } else {
            SwimButton(
                text = "Sign in with Linear",
                primary = oauthReady,
                enabled = oauthReady && !busy,
                onClick = {
                    error = null
                    val run = env.linearBrowserSignIn ?: return@SwimButton
                    waiting = scope.launch {
                        try {
                            val tokens = run()
                            finish(tokens.copy(mode = LinearAuthMode.OAUTH))
                            onSignedIn(viewerName(env, tokens.accessToken, LinearAuthMode.OAUTH))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "The Linear sign-in failed."
                        } finally {
                            waiting = null
                        }
                    }
                },
            )
        }
        if (!oauthReady) {
            Text(
                text = "OAuth app not registered yet. Use a personal API key below.",
                color = Swim.Amber,
                fontSize = 11.sp,
            )
        }

        Divider()
        Text("Personal API key", color = Swim.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            text = "Create one at linear.app → Settings → Security & access → Personal API keys.",
            color = Swim.TextMuted,
            fontSize = 11.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwimTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = "lin_api_…",
                masked = true,
                width = 280.dp,
            )
            SwimButton(
                text = "Connect",
                primary = !oauthReady,
                enabled = apiKey.isNotBlank() && !busy,
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            val name = viewerName(env, apiKey.trim(), LinearAuthMode.API_KEY)
                            finish(LinearTokens(apiKey.trim(), mode = LinearAuthMode.API_KEY))
                            onSignedIn(name)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Linear rejected the key."
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
        error?.let { StatusLine(it, Swim.Red) }
    }
}

/**
 * Confirms a GitHub token works and stores it. Returns the account name for the "Connected as"
 * line. The caller then refreshes the auth status, because the graph reads the store, not this.
 */
internal suspend fun connectGithub(http: HttpClient, tokenStore: TokenStore, token: String): String {
    val login = GithubClient(http) { token }.verifyToken()
    tokenStore.setGithub(token)
    return login
}

/**
 * The GitHub card: the device flow when an OAuth app exists, a pasted token at all times. The
 * graph shows the same card over itself, where [dismissLabel] reads "Cancel" instead of "Skip".
 */
@Composable
internal fun GithubCard(
    env: SwimEnv,
    scope: CoroutineScope,
    dismissLabel: String = "Skip",
    onDone: () -> Unit,
) {
    var code by remember { mutableStateOf<GithubDeviceCode?>(null) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var polling by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<String?>(null) }

    fun store(value: String) {
        busy = true
        scope.launch {
            try {
                login = connectGithub(env.http, env.tokenStore, value)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "GitHub rejected the token."
            } finally {
                busy = false
            }
        }
    }

    Card("GitHub") {
        Text(
            text = "Optional. It adds pull-request review and check status to the cards. " +
                "GitHub has no read-only private scope, so the token asks for `repo`.",
            color = Swim.TextMuted,
            fontSize = 12.sp,
        )

        val current = code
        if (current != null) {
            Text("Enter this code at ${current.verificationUri}", color = Swim.TextMuted, fontSize = 12.sp)
            Text(
                text = current.userCode,
                color = Swim.Accent,
                fontSize = 34.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwimButton("Open github.com/login/device", { env.openUrl(current.verificationUri) }, primary = true)
                SwimButton("Cancel", { polling?.cancel(); code = null })
            }
            StatusLine("Waiting for you to approve the code.", Swim.Accent)
        } else if (OAuthApps.githubConfigured) {
            SwimButton(
                text = "Connect GitHub",
                primary = true,
                enabled = !busy,
                onClick = {
                    error = null
                    polling = scope.launch {
                        val flow = GithubDeviceFlow(env.http, OAuthApps.githubClientId)
                        try {
                            val requested = flow.requestCode()
                            code = requested
                            env.openUrl(requested.verificationUri)
                            val issued = flow.awaitToken(requested)
                            code = null
                            store(issued)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            code = null
                            error = e.message ?: "The GitHub sign-in failed."
                        } finally {
                            polling = null
                        }
                    }
                },
            )
        } else {
            Text(
                text = "OAuth app not registered yet. Paste a personal access token instead.",
                color = Swim.Amber,
                fontSize = 11.sp,
            )
        }

        Divider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwimTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = "ghp_… or gho_…",
                masked = true,
                width = 280.dp,
            )
            SwimButton("Connect", { store(token.trim()) }, enabled = token.isNotBlank() && !busy)
        }
        login?.let { StatusLine("Connected as $it", Swim.Green) }
        error?.let { StatusLine(it, Swim.Red) }

        Divider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Pull-request status stays out of the graph until you connect GitHub.",
                color = Swim.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            SwimButton(dismissLabel, onDone)
        }
    }
}

/** Confirms the credential works and returns the account name, for the "Signed in as" line. */
private suspend fun viewerName(env: SwimEnv, token: String, mode: LinearAuthMode): String {
    val auth = when (mode) {
        LinearAuthMode.API_KEY -> apiKeyAuth(token)
        LinearAuthMode.OAUTH -> oauthAuth { token }
    }
    return LinearClient(env.http, auth, env.config).getViewer().name
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Swim.Card, RoundedCornerShape(8.dp))
            .border(1.dp, Swim.Border, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Swim.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Swim.Border))
}
