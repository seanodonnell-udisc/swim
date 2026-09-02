package swim.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import swim.core.auth.LinearTokens
import swim.core.auth.TokenStore
import swim.core.config.loadConfig
import swim.ui.app.SwimApp
import swim.ui.app.SwimEnv
import java.io.File

/**
 * Renders the shell offscreen and writes PNGs. The screen-capture permission is not granted to
 * every terminal, and a screenshot of the running window is the only way to review the look, so
 * the same composables render through `ImageComposeScene` instead.
 *
 * ponytail: a dev tool, not a test. It talks to the live workspace on purpose.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: ".").also { it.mkdirs() }
    val http = HttpClient(OkHttp)
    val autoload = System.getProperty("swim.dev.autoload")

    shoot(outDir, "p3b-shot-login.png", 4, SwimEnv(http, EmptyTokenStore, Settings(), scope(), loadConfig()))
    shoot(
        outDir, "p3b-shot-graph.png", 60,
        SwimEnv(http, tokenStore(), Settings(), scope(), loadConfig(), devAutoload = autoload, log = Log::line),
    )

    val saved = Settings().getStringOrNull(FILTERS)
    grouped(saved)
    shoot(
        outDir, "p3b-shot-grouped.png", 60,
        SwimEnv(http, tokenStore(), Settings(), scope(), loadConfig(), devAutoload = autoload, log = Log::line),
    )
    saved?.let { Settings().putString(FILTERS, it) }
    Log.line("shots written to ${outDir.absolutePath}")
    kotlin.system.exitProcess(0)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shoot(outDir: File, name: String, frames: Int, env: SwimEnv) {
    val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
        SwimApp(env)
    }
    try {
        var image = scene.render()
        repeat(frames) {
            Thread.sleep(FRAME_PAUSE_MS)
            image = scene.render()
        }
        File(outDir, name).writeBytes(requireNotNull(image.encodeToData()).bytes)
        Log.line("wrote $name")
    } finally {
        scene.close()
    }
}

/** Persists group-by-project, so the group outlines appear in one shot. */
private fun grouped(saved: String?) {
    saved?.let { Settings().putString(FILTERS, it.replace("\"NONE\"", "\"PROJECT\"")) }
}

private const val FILTERS = "swim.filters"

private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private object EmptyTokenStore : TokenStore {
    override fun getLinear(): LinearTokens? = null
    override fun setLinear(tokens: LinearTokens) = Unit
    override fun clearLinear() = Unit
    override fun getGithub(): String? = null
    override fun setGithub(token: String) = Unit
    override fun clearGithub() = Unit
}

private const val WIDTH = 1440
private const val HEIGHT = 900
private const val FRAME_PAUSE_MS = 250L
