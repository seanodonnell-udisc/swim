package swim.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import swim.core.session.FilePositionStore
import swim.ui.app.AppCommand
import swim.ui.app.AppCommands
import swim.ui.app.SwimApp
import swim.ui.app.SwimEnv
import swim.ui.graph.GraphCanvas
import swim.ui.graph.GraphCanvasPreview
import swim.ui.graph.GraphCanvasState
import swim.ui.graph.rememberGraphCanvasState
import java.io.File

/**
 * Renders the shell offscreen and writes PNGs. The screen-capture permission is not granted to
 * every terminal, and a screenshot of the running window is the only way to review the look, so
 * the same composables render through `ImageComposeScene` instead. `sendPointerEvent` stages the
 * surfaces that only a pointer can open.
 *
 * ponytail: a dev tool, not a test. It talks to the live workspace on purpose.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: ".").also { it.mkdirs() }
    val http = HttpClient(OkHttp)
    val autoload = System.getProperty("swim.dev.autoload")
    val savedFilters = Settings().getStringOrNull(FILTERS)
    val savedSeen = Settings().getBooleanOrNull(SEEN_SHORTCUTS)

    // Its own file store. The settings-backed fallback caps a value at 8 KB, which a real
    // graph's layout passes, and the shots must not write the running app's positions either.
    val positions = java.nio.file.Files.createTempFile("swim-shot-positions", ".json")
    fun graphEnv(commands: AppCommands = AppCommands()) = SwimEnv(
        http, tokenStore(), Settings(), scope(), config = loadConfig(),
        commands = commands, devAutoload = autoload, log = Log::line,
        positionStore = FilePositionStore(positions),
    )

    // Every shot but the last one wants the first-run overlay out of the way.
    Settings().putBoolean(SEEN_SHORTCUTS, true)

    shoot(outDir, "p3b-shot-login.png", 4, SwimEnv(http, EmptyTokenStore, Settings(), scope(), config = loadConfig()))
    shoot(outDir, "p3b-shot-graph.png", 60, graphEnv())

    // Five zoom-outs land near 0.40. Cards keep their one full style at every scale.
    val zoom = AppCommands()
    shoot(
        outDir, "p3b-shot-zoomed-out.png", 60, graphEnv(zoom),
        atFrame = 40 to { _ -> repeat(5) { zoom.send(AppCommand.ZOOM_OUT) } },
    )

    // One shot per editing mode. Interact also hovers a card, so its handles show.
    shoot(
        // 90 frames: these two stage a hover late, so they must not race the sandbox load.
        outDir, "p3d-shot-arrange.png", 90, graphEnv(),
        atFrame = 70 to { scene -> scene.move(FIRST_CARD) },
    )
    shoot(
        outDir, "p3d-shot-interact.png", 90, graphEnv(),
        atFrame = 70 to { scene ->
            scene.sendKeyEvent(KeyEvent(Key.H, KeyEventType.KeyDown))
            scene.render()
            scene.move(FIRST_CARD)
        },
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

    // Milestone mode: default (no cross-area edges), then the sub-toggle on, then an area drag.
    milestone(savedFilters)
    shoot(outDir, "p3e-shot-milestone.png", 90, graphEnv())
    shoot(
        outDir, "p3e-shot-milestone-cross.png", 90, graphEnv(),
        atFrame = 70 to { scene -> scene.click(CROSS_TOGGLE) },
    )
    shoot(
        outDir, "p3e-shot-milestone-dragged.png", 90, graphEnv(),
        atFrame = 70 to { scene -> scene.dragBy(SECOND_LABEL, 0f, 320f) },
    )
    savedFilters?.let { Settings().putString(FILTERS, it) }

    grouped(savedFilters)
    shoot(outDir, "p3b-shot-grouped.png", 60, graphEnv())
    savedFilters?.let { Settings().putString(FILTERS, it) }

    // The live graph, re-laid out. A workspace that has been dragged around for weeks fits at
    // 10%, which says nothing about whether a pile drew; a fresh layout is readable.
    val relayout = AppCommands()
    shoot(
        outDir, "p3f-shot-live.png", 90, graphEnv(relayout),
        atFrame = 50 to { _ -> relayout.send(AppCommand.RELAYOUT) },
    )

    // The pile of stacked cards and the PR-derived edge, off the preview graph. The live
    // workspace has no stacked pull requests, so this is the only place they can be reviewed.
    shootCanvas(outDir, "p3f-shot-stack.png") { scene, state ->
        scene.move(state.toScreen(STACK_BADGE))
    }
    shootCanvas(outDir, "p3f-shot-derived-edge.png") { scene, state ->
        scene.click(state.toScreen(DERIVED_EDGE))
    }

    // Last, because it is the only one that wants the flag clear.
    Settings().remove(SEEN_SHORTCUTS)
    shoot(outDir, "p3d-shot-shortcuts.png", 60, graphEnv())
    if (savedSeen == null) Settings().remove(SEEN_SHORTCUTS) else Settings().putBoolean(SEEN_SHORTCUTS, savedSeen)

    java.nio.file.Files.deleteIfExists(positions)
    Log.line("shots written to ${outDir.absolutePath}")
    kotlin.system.exitProcess(0)
}

/**
 * The canvas on its own, against the hand-built preview graph. The live workspace has no stacked
 * pull requests, so the pile and the derived edge can only be reviewed here. [stage] gets the
 * canvas state, so a point can be named in canvas units instead of guessed in pixels.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun shootCanvas(
    outDir: File,
    name: String,
    stage: (ImageComposeScene, GraphCanvasState) -> Unit,
) {
    lateinit var state: GraphCanvasState
    val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
        state = rememberGraphCanvasState()
        GraphCanvas(
            graph = GraphCanvasPreview.graph,
            positions = GraphCanvasPreview.positions,
            readySet = GraphCanvasPreview.readySet,
            prStatuses = GraphCanvasPreview.prStatuses,
            users = GraphCanvasPreview.users,
            crossLinks = GraphCanvasPreview.crossLinks,
            cycleEdges = GraphCanvasPreview.cycleEdges,
            state = state,
        )
    }
    try {
        // The first frame sizes the viewport; the second lands the fit it arms.
        repeat(3) { scene.render() }
        stage(scene, state)
        // The canvas offers a double tap, so `detectTapGestures` holds a plain tap back until
        // the double-tap window closes. That window is real time, and the scene only runs its
        // dispatcher while it renders, so a staged click needs paced frames after it.
        var image = scene.render()
        repeat(3) {
            Thread.sleep(FRAME_PAUSE_MS)
            image = scene.render()
        }
        File(outDir, name).writeBytes(requireNotNull(image.encodeToData()).bytes)
        Log.line("wrote $name")
    } finally {
        scene.close()
    }
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

/**
 * Canvas units, not pixels: the two points on the preview graph worth staging. The badge sits
 * just off the front card of the pile; the derived edge runs straight down from ENG-106.
 */
private val STACK_BADGE = Offset(370f, 795f)
private val DERIVED_EDGE = Offset(195f, 600f)
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
private fun ImageComposeScene.dragBy(from: Offset, dx: Float, dy: Float) {
    move(from)
    sendPointerEvent(
        PointerEventType.Press, from,
        button = PointerButton.Primary,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    listOf(0.3f, 0.7f, 1f).forEach { step ->
        sendPointerEvent(
            PointerEventType.Move, from + Offset(dx * step, dy * step),
            buttons = PointerButtons(isPrimaryPressed = true),
        )
        render()
    }
    sendPointerEvent(
        PointerEventType.Release, from + Offset(dx, dy),
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

/** Persists group-by-milestone for the three milestone shots. */
private fun milestone(saved: String?) {
    saved?.let { Settings().putString(FILTERS, it.replace("\"NONE\"", "\"MILESTONE\"")) }
}

/** The "Cross-milestone links" checkbox, last in the view toolbar before the counts. */
private val CROSS_TOGGLE = Offset(1180f, 73f)

/** Inside the second area's label band. Read off the default milestone shot. */
private val SECOND_LABEL = Offset(445f, 139f)

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
