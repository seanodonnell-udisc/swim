package swim.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import swim.core.auth.LinearOAuth
import swim.core.auth.LinearTokens
import swim.core.auth.LoopbackServer
import swim.core.auth.OAuthApps
import swim.core.auth.Pkce
import swim.core.auth.TokenStore
import swim.core.auth.JvmTokenStore
import swim.core.auth.createTokenStore
import swim.core.config.loadConfig
import swim.core.model.AuthError
import swim.ui.app.AppCommand
import swim.ui.app.AppCommands
import swim.ui.app.SwimApp
import swim.ui.app.SwimEnv
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.Dispatchers

/** Where the window remembers its size and position. */
private const val WINDOW_KEY = "swim.window"

fun main() {
    Log.start()
    val http = HttpClient(OkHttp)
    val settings = Settings()
    val tokenStore = tokenStore()
    val commands = AppCommands()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val env = SwimEnv(
        http = http,
        tokenStore = tokenStore,
        settings = settings,
        scope = scope,
        config = loadConfig(),
        commands = commands,
        openUrl = ::openUrl,
        copyToClipboard = ::copyToClipboard,
        linearBrowserSignIn = { signInWithLinear(http) },
        devAutoload = System.getProperty("swim.dev.autoload"),
        log = Log::line,
    )

    application {
        val saved = readWindow(settings)
        val state = rememberWindowState(
            size = DpSize(saved.width.dp, saved.height.dp),
            position = if (saved.x < 0) WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
            else WindowPosition(saved.x.dp, saved.y.dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Swim",
        ) {
            LaunchedEffect(state) {
                snapshotFlow { state.size to state.position }.collect { (size, position) ->
                    writeWindow(settings, size, position)
                }
            }
            MenuBar {
                Menu("Go") {
                    Item("Reload", shortcut = KeyShortcut(Key.R, meta = true)) {
                        commands.send(AppCommand.RELOAD)
                    }
                    Item("Re-layout", shortcut = KeyShortcut(Key.L, meta = true)) {
                        commands.send(AppCommand.RELAYOUT)
                    }
                    Separator()
                    Item("Zoom In", shortcut = KeyShortcut(Key.Equals, meta = true)) {
                        commands.send(AppCommand.ZOOM_IN)
                    }
                    Item("Zoom Out", shortcut = KeyShortcut(Key.Minus, meta = true)) {
                        commands.send(AppCommand.ZOOM_OUT)
                    }
                    Item("Zoom to Fit", shortcut = KeyShortcut(Key.Zero, meta = true)) {
                        commands.send(AppCommand.ZOOM_FIT)
                    }
                }
            }
            SwimApp(env)
        }
    }
}

/**
 * The credential store. `-Dswim.insecureStorage=true` keeps the tokens in the 0600 file only,
 * which is what `gh --insecure-storage` does and what a headless test run needs: reading the
 * keychain item the CLI wrote raises a macOS authorization prompt.
 */
internal fun tokenStore(): TokenStore =
    if (System.getProperty("swim.insecureStorage") == "true") {
        Log.line("token store: file only, the keychain is turned off")
        JvmTokenStore(useKeychain = false)
    } else {
        createTokenStore()
    }

/** Runs the Linear browser sign-in over the fixed loopback port the registration names. */
private suspend fun signInWithLinear(http: HttpClient): LinearTokens =
    LoopbackServer(OAuthApps.LOOPBACK_PORT, OAuthApps.LOOPBACK_PATH).use { server ->
        val oauth = LinearOAuth(http, OAuthApps.linearClientId, OAuthApps.REDIRECT_URI)
        val verifier = Pkce.createVerifier()
        val state = Pkce.createState()
        openUrl(oauth.authorizeUrl(Pkce.challenge(verifier), state))

        val callback = withTimeout(CALLBACK_TIMEOUT_MS) { server.awaitCallback() }
        callback["error"]?.let { throw AuthError("Linear refused the sign-in: $it") }
        if (callback["state"] != state) throw AuthError("The Linear callback did not match. Start again.")
        val code = callback["code"] ?: throw AuthError("The Linear callback carried no code.")
        oauth.exchangeCode(code, verifier)
    }

private fun openUrl(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
        .onFailure { Log.line("could not open $url: ${it.message}") }
}

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

private class WindowBox(val width: Int, val height: Int, val x: Int, val y: Int)

private fun readWindow(settings: Settings): WindowBox {
    val stored = settings.getStringOrNull(WINDOW_KEY)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
    if (stored == null || stored.size != 4) return WindowBox(1440, 900, -1, -1)
    return WindowBox(stored[0], stored[1], stored[2], stored[3])
}

private fun writeWindow(
    settings: Settings,
    size: DpSize,
    position: WindowPosition,
) {
    if (!position.isSpecified) return
    settings.putString(
        WINDOW_KEY,
        "${size.width.value.toInt()},${size.height.value.toInt()}," +
            "${position.x.value.toInt()},${position.y.value.toInt()}",
    )
}

private const val CALLBACK_TIMEOUT_MS = 180_000L
