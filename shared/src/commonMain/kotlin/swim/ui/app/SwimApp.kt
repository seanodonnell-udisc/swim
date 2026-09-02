package swim.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.russhwolf.settings.Settings
import swim.core.auth.LinearAuth
import swim.core.auth.LinearOAuth
import swim.core.auth.LinearTokens
import swim.core.auth.OAuthApps
import swim.core.auth.TokenStore
import swim.core.config.SwimConfig
import swim.core.github.GithubClient
import swim.core.linear.LinearClient
import swim.core.model.FilterOptions
import swim.core.session.AuthStatus
import swim.core.session.FilterStore
import swim.core.session.GraphSession
import swim.core.session.PositionStore
import swim.core.session.SettingsPositionStore
import swim.core.session.authStatus
import swim.ui.auth.LoginScreen
import swim.ui.theme.SwimTheme

/** A menu command the desktop menu bar fires into the graph screen. */
enum class AppCommand { RELOAD, RELAYOUT, ZOOM_IN, ZOOM_OUT, ZOOM_FIT }

/** The one-way channel from the platform menu bar to whatever screen is showing. */
class AppCommands {
    private val _commands = MutableSharedFlow<AppCommand>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Commands the screen collects. */
    val commands: SharedFlow<AppCommand> = _commands.asSharedFlow()

    /** Fires one command. Safe from any thread; a command with no screen listening is dropped. */
    fun send(command: AppCommand) {
        _commands.tryEmit(command)
    }
}

/**
 * Everything the shell needs from the platform it runs on. The desktop app builds one of these
 * and hands it to [SwimApp]; the mobile hosts will build their own.
 */
class SwimEnv(
    val http: HttpClient,
    val tokenStore: TokenStore,
    val settings: Settings,
    val scope: CoroutineScope,
    val config: SwimConfig = SwimConfig(),
    val commands: AppCommands = AppCommands(),
    val openUrl: (String) -> Unit = {},
    val copyToClipboard: (String) -> Unit = {},
    /** Runs the browser sign-in. Null on a platform with no loopback redirect. */
    val linearBrowserSignIn: (suspend () -> LinearTokens)? = null,
    /** Dev hook: `TEAM/Project name`, pre-armed and loaded on launch. See the P3b report. */
    val devAutoload: String? = null,
    /** Where the shell reports what it did. The desktop app sends this to its log file. */
    val log: (String) -> Unit = {},
)

/** The Linear-backed objects one signed-in session needs. */
internal class SwimSession(
    val client: LinearClient,
    val github: GithubClient,
    val filters: FilterStore,
    val positions: PositionStore,
    val session: GraphSession,
    val oauth: LinearOAuth,
)

/** The app: the login screen until Linear is configured, the graph after that. */
@Composable
fun SwimApp(env: SwimEnv) {
    SwimTheme {
        var status by remember { mutableStateOf(authStatus(env.tokenStore)) }
        if (!status.configured) {
            LoginScreen(env) { status = authStatus(env.tokenStore) }
        } else {
            val session = remember(status) { buildSession(env) }
            GraphScreen(env, session, status) { status = authStatus(env.tokenStore) }
        }
    }
}

internal fun buildSession(env: SwimEnv): SwimSession {
    val oauth = LinearOAuth(env.http, OAuthApps.linearClientId, OAuthApps.REDIRECT_URI)
    val auth = LinearAuth.provider(env.tokenStore, oauth)
        ?: error("buildSession without a stored credential")
    val client = LinearClient(env.http, auth, env.config)
    val github = GithubClient(env.http, env.log) { env.tokenStore.getGithub() }
    val filters = FilterStore(env.settings)
    val positions = SettingsPositionStore(env.settings)
    return SwimSession(
        client = client,
        github = github,
        filters = filters,
        positions = positions,
        session = GraphSession(
            client = client,
            github = github,
            filterStore = filters,
            positions = positions,
            scope = env.scope,
        ),
        oauth = oauth,
    )
}

/** Reads the dev autoload flag, which is `TEAM` or `TEAM/Project name`. */
internal fun parseAutoload(spec: String): FilterOptions? {
    val trimmed = spec.trim()
    if (trimmed.isEmpty()) return null
    val team = trimmed.substringBefore('/').trim().ifEmpty { null }
    val project = trimmed.substringAfter('/', "").trim().ifEmpty { null }
    if (team == null && project == null) return null
    return FilterOptions(team = team, project = project)
}
