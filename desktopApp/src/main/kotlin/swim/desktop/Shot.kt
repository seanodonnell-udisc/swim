package swim.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
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
import swim.ui.app.AppCommand
import swim.ui.app.AppCommands
import swim.ui.app.SwimApp
import swim.ui.app.SwimEnv
import java.io.File

/**
 * Renders the shell offscreen and writes PNGs. The screen-capture permission is not granted to
 * every terminal, and a screenshot of the running window is the only way to review the look, so
 * the same composables render through `ImageComposeScene` instead. `sendPointerEvent` stages the
 * surfaces that only a pointer can open.
 *
 * ponytail: a dev tool, not a test. It talks to the live workspace on purpose.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: ".").also { it.mkdirs() }
    val http = HttpClient(OkHttp)
    val autoload = System.getProperty("swim.dev.autoload")
    val savedFilters = Settings().getStringOrNull(FILTERS)
    val savedSeen = Settings().getBooleanOrNull(SEEN_SHORTCUTS)

    fun graphEnv(commands: AppCommands = AppCommands()) = SwimEnv(
        http, tokenStore(), Settings(), scope(), loadConfig(),
        commands = commands, devAutoload = autoload, log = Log::line,
    )

    // Every shot but the last one wants the first-run overlay out of the way.
    Settings().putBoolean(SEEN_SHORTCUTS, true)

    shoot(outDir, "p3b-shot-login.png", 4, SwimEnv(http, EmptyTokenStore, Settings(), scope(), loadConfig()))
    shoot(outDir, "p3b-shot-graph.png", 60, graphEnv())

    // Five zoom-outs land near 0.40. Cards keep their one full style at every scale.
    val zoom = AppCommands()
    shoot(
        outDir, "p3b-shot-zoomed-out.png", 60, graphEnv(zoom),
        atFrame = 40 to { _ -> repeat(5) { zoom.send(AppCommand.ZOOM_OUT) } },
    )

    shoot(
        outDir, "p3d-shot-context-menu.png", 60, graphEnv(),
        atFrame = 40 to { scene -> scene.rightClick(FIRST_CARD) },
    )
    shoot(
        outDir, "p3d-shot-pick-target.png", 60, graphEnv(),
        atFrame = 40 to { scene ->
            scene.rightClick(FIRST_CARD)
            scene.click(menuRow(FIRST_CARD, ADD_RELATION))
            scene.click(menuRow(submenuOrigin(FIRST_CARD, ADD_RELATION), 0))
            scene.move(PICK_TARGET)
        },
    )

    grouped(savedFilters)
    shoot(outDir, "p3b-shot-grouped.png", 60, graphEnv())
    savedFilters?.let { Settings().putString(FILTERS, it) }

    // Last, because it is the only one that wants the flag clear.
    Settings().remove(SEEN_SHORTCUTS)
    shoot(outDir, "p3d-shot-shortcuts.png", 60, graphEnv())
    if (savedSeen == null) Settings().remove(SEEN_SHORTCUTS) else Settings().putBoolean(SEEN_SHORTCUTS, savedSeen)

    Log.line("shots written to ${outDir.absolutePath}")
    kotlin.system.exitProcess(0)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shoot(
    outDir: File,
    name: String,
    frames: Int,
    env: SwimEnv,
    atFrame: Pair<Int, (ImageComposeScene) -> Unit>? = null,
) {
    val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
        SwimApp(env)
    }
    try {
        var image = scene.render()
        repeat(frames) { frame ->
            if (atFrame != null && frame == atFrame.first) atFrame.second(scene)
            Thread.sleep(FRAME_PAUSE_MS)
            image = scene.render()
        }
        File(outDir, name).writeBytes(requireNotNull(image.encodeToData()).bytes)
        Log.line("wrote $name")
    } finally {
        scene.close()
    }
}

/**
 * A point inside the first card. The fit anchors the graph at (48, 48) below the two 48dp toolbar
 * rows, so this lands inside a 270x120 card at any scale down to about 0.45.
 */
private val FIRST_CARD = Offset(108f, 176f)
private val PICK_TARGET = Offset(560f, 460f)
private const val ADD_RELATION = 3
private const val MENU_WIDTH = 184f
private const val MENU_ROW = 22f
private const val MENU_PADDING = 4f

private fun menuRow(origin: Offset, index: Int) =
    Offset(origin.x + 30f, origin.y + MENU_PADDING + index * MENU_ROW + MENU_ROW / 2f)

private fun submenuOrigin(origin: Offset, index: Int) =
    Offset(origin.x + MENU_WIDTH, origin.y + index * MENU_ROW)

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.move(at: Offset) {
    sendPointerEvent(PointerEventType.Move, at)
    render()
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.click(at: Offset) {
    move(at)
    sendPointerEvent(
        PointerEventType.Press, at,
        button = PointerButton.Primary,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    sendPointerEvent(
        PointerEventType.Release, at,
        button = PointerButton.Primary,
        buttons = PointerButtons(),
    )
    render()
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.rightClick(at: Offset) {
    move(at)
    sendPointerEvent(
        PointerEventType.Press, at,
        button = PointerButton.Secondary,
        buttons = PointerButtons(isSecondaryPressed = true),
    )
    sendPointerEvent(
        PointerEventType.Release, at,
        button = PointerButton.Secondary,
        buttons = PointerButtons(),
    )
    render()
}

/** Persists group-by-project, so the group outlines appear in one shot. */
private fun grouped(saved: String?) {
    saved?.let { Settings().putString(FILTERS, it.replace("\"NONE\"", "\"PROJECT\"")) }
}

private const val FILTERS = "swim.filters"
private const val SEEN_SHORTCUTS = "swim.seenShortcuts"

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
