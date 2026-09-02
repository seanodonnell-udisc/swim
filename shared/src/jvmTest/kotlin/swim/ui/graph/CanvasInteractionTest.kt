package swim.ui.graph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import swim.core.model.RelationType
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WIDTH = 1400
private const val HEIGHT = 900

// The context menu geometry, mirrored from ContextMenu.kt so a row can be aimed at.
private const val MENU_WIDTH = 184f
private const val MENU_ROW = 22f
private const val MENU_PADDING = 4f

/**
 * Drives the canvas with a synthetic pointer. Every gesture the app relies on but no unit test
 * could reach — the context menus, the pick-target relation flow, the handle drag — is exercised
 * here, and the three that are worth looking at also write a PNG.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class CanvasInteractionTest {

    private val state = GraphCanvasState()
    private val log = mutableListOf<String>()

    private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        GraphCanvas(
            graph = GraphCanvasPreview.graph,
            positions = GraphCanvasPreview.positions,
            modifier = Modifier.fillMaxSize(),
            readySet = GraphCanvasPreview.readySet,
            prStatuses = GraphCanvasPreview.prStatuses,
            users = GraphCanvasPreview.users,
            crossLinks = GraphCanvasPreview.crossLinks,
            cycleEdges = GraphCanvasPreview.cycleEdges,
            state = state,
            callbacks = GraphCanvasCallbacks(
                onOpenIssue = { log += "open:$it" },
                onCopyId = { log += "copy:$it" },
                onAssign = { id, user -> log += "assign:$id:$user" },
                onCreateRelation = { from, to, type, reversed ->
                    log += "create:$from:$to:$type:$reversed"
                },
                onChangeRelation = { edge, type, reversed ->
                    log += "change:${edge.from}>${edge.to}:$type:$reversed"
                },
                onRemoveRelation = { log += "remove:${it.from}>${it.to}:${it.type}" },
                onSelectionChange = { log += "select:${it.sorted()}" },
                onRelayout = { log += "relayout" },
                onReload = { log += "reload" },
            ),
        )
    }

    init {
        // The first frame sizes the viewport; the second lands the fit that arms.
        repeat(3) { scene.render() }
    }

    @AfterTest
    fun tearDown() = scene.close()

    @Test
    fun aRightClickOnACardOpensItsMenu() {
        val at = cardCentre("ENG-101")
        val before = region(at)
        rightClick(at)

        assertEquals(CanvasMenu.Node("ENG-101", at), state.menu)
        val after = region(at)
        assertTrue(
            differing(before, after) > before.size / 4,
            "the menu did not draw: only ${differing(before, after)} of ${before.size} pixels moved",
        )
        write("canvas-context-menu.png")
    }

    @Test
    fun aRightClickOnEmptyCanvasOffersTheCanvasActions() {
        rightClick(Offset(1150f, 120f))
        val menu = state.menu
        assertTrue(menu is CanvasMenu.Empty, "expected the canvas menu, got $menu")

        click(menuRow(menu.at, 2))
        assertEquals(listOf("reload"), log)
        assertNull(state.menu, "the menu stayed open after an action")
    }

    @Test
    fun theAssignSubmenuFiresTheAssignCallback() {
        val at = cardCentre("ENG-101")
        rightClick(at)
        click(menuRow(at, ASSIGN_TO))
        click(menuRow(submenuOrigin(at, ASSIGN_TO), 2))

        assertEquals(listOf("assign:ENG-101:u2"), log)
    }

    @Test
    fun theAddRelationFlowPicksATargetAndReportsIt() {
        val source = cardCentre("ENG-101")
        rightClick(source)
        click(menuRow(source, ADD_RELATION))
        // "Blocked by…" is the second entry, so the eventual relation is reversed.
        click(menuRow(submenuOrigin(source, ADD_RELATION), 1))

        assertEquals(PickTarget("ENG-101", RelationType.BLOCKS, true), state.pick)
        assertTrue(log.isEmpty(), "picking a relation type must not report anything yet: $log")

        val target = cardCentre("ENG-104")
        move(target)
        write("canvas-pick-target.png")

        click(target)
        assertEquals(listOf("create:ENG-101:ENG-104:BLOCKS:true"), log)
        assertNull(state.pick, "pick mode outlived the click that completed it")
    }

    @Test
    fun theRelationsSubmenuRemovesOneNamedEdge() {
        val at = cardCentre("ENG-101")
        rightClick(at)
        click(menuRow(at, RELATIONS))
        // ENG-101 has two: blocks ENG-103, then blocks ENG-104.
        val relations = submenuOrigin(at, RELATIONS)
        click(menuRow(relations, 1))
        // Three identities to change into, then Remove.
        click(menuRow(submenuOrigin(relations, 1), 3))

        assertEquals(listOf("remove:ENG-101>ENG-104:BLOCKS"), log)
    }

    @Test
    fun escapeCancelsPickMode() {
        val source = cardCentre("ENG-101")
        rightClick(source)
        click(menuRow(source, ADD_RELATION))
        click(menuRow(submenuOrigin(source, ADD_RELATION), 0))
        assertTrue(state.pick != null, "pick mode did not start")

        scene.sendKeyEvent(keyDown(Key.Escape))
        scene.render()

        assertNull(state.pick, "Esc did not cancel pick mode")
        assertTrue(log.isEmpty(), "a cancelled pick reported something: $log")
    }

    @Test
    fun aRightClickOnAnEdgeOffersRemove() {
        // ENG-101 blocks ENG-103: a straight run down one column. Aim below the split, where
        // the trunk that ENG-101 shares with its other blocks edge has already turned away.
        val at = state.toScreen(Offset(195f, 210f))
        rightClick(at)
        assertEquals(
            CanvasMenu.Edge(EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS), at),
            state.menu,
        )

        // Three "change to" identities, then Remove.
        click(menuRow(at, 3))
        assertEquals(listOf("remove:ENG-101>ENG-103:BLOCKS"), log)
    }

    @Test
    fun aRightDragPansAndOpensNoMenu() {
        val start = Offset(1150f, 120f)
        val before = state.offset
        move(start)
        scene.sendPointerEvent(
            PointerEventType.Press, start,
            button = PointerButton.Secondary,
            buttons = PointerButtons(isSecondaryPressed = true),
        )
        listOf(20f, 60f, 90f).forEach { step ->
            scene.sendPointerEvent(
                PointerEventType.Move, start + Offset(step, step / 2f),
                buttons = PointerButtons(isSecondaryPressed = true),
            )
        }
        scene.sendPointerEvent(
            PointerEventType.Release, start + Offset(90f, 45f),
            button = PointerButton.Secondary,
            buttons = PointerButtons(),
        )
        scene.render()

        assertNull(state.menu, "a right drag opened the context menu")
        assertTrue(
            abs(state.offset.x - before.x) > 60f && abs(state.offset.y - before.y) > 30f,
            "the right drag did not pan: ${before} to ${state.offset}",
        )
    }

    @Test
    fun aHandleDragOntoAnotherCardCreatesARelation() {
        val card = GraphCanvasPreview.positions.getValue("ENG-101")
        val handle = state.toScreen(
            Offset(
                card.x + GraphCanvasDefaults.NodeWidth / 2f,
                card.y + GraphCanvasDefaults.NodeHeight - 8f,
            )
        )
        // The handles only exist while the card is hovered, so hover it first.
        move(state.toScreen(Offset(card.x + 40f, card.y + 40f)))
        move(handle)

        scene.sendPointerEvent(
            PointerEventType.Press, handle,
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
        )
        val drop = cardCentre("ENG-103")
        listOf(0.25f, 0.6f, 1f).forEach { fraction ->
            scene.sendPointerEvent(
                PointerEventType.Move, handle + (drop - handle) * fraction,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            scene.render()
        }
        scene.sendPointerEvent(
            PointerEventType.Release, drop,
            button = PointerButton.Primary,
            buttons = PointerButtons(),
        )
        scene.render()

        val panel = state.panel
        assertTrue(
            panel is CanvasPanel.Create && panel.from == "ENG-101" && panel.to == "ENG-103",
            "the drop did not offer a relation between the two cards: $panel",
        )
        click(Offset(panel.at.x + 20f, panel.at.y + MENU_PADDING + MENU_ROW / 2f))
        assertEquals(listOf("create:ENG-101:ENG-103:BLOCKS:false"), log)
    }

    @Test
    fun theShortcutsOverlayOpensOnQuestionMarkAndClosesOnEscape() {
        scene.sendKeyEvent(keyDown(Key.Slash, shift = true))
        scene.render()
        assertTrue(state.shortcutsVisible, "? did not open the shortcuts overlay")
        write("canvas-shortcuts.png")

        scene.sendKeyEvent(keyDown(Key.Escape))
        scene.render()
        assertTrue(!state.shortcutsVisible, "Esc did not close the shortcuts overlay")
    }

    @Test
    fun theZoomReadoutInTheHintBarReturnsToOneToOne() {
        state.zoomIn()
        state.zoomIn()
        scene.render()
        assertTrue(state.scale > 1f, "the zoom did not change")

        // The bar is 24dp tall along the bottom; the readout sits at its right end.
        click(Offset(WIDTH - 30f, HEIGHT - 12f))
        assertEquals(1f, state.scale)
    }

    // -- driving ------------------------------------------------------------------------------

    private fun cardCentre(id: String): Offset {
        val position = GraphCanvasPreview.positions.getValue(id)
        return state.toScreen(
            Offset(
                position.x + GraphCanvasDefaults.NodeWidth / 2f,
                position.y + GraphCanvasDefaults.NodeHeight / 2f,
            )
        )
    }

    private fun move(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.render()
    }

    private fun click(at: Offset) {
        move(at)
        scene.sendPointerEvent(
            PointerEventType.Press, at,
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
        )
        scene.sendPointerEvent(
            PointerEventType.Release, at,
            button = PointerButton.Primary,
            buttons = PointerButtons(),
        )
        scene.render()
    }

    private fun rightClick(at: Offset) {
        move(at)
        scene.sendPointerEvent(
            PointerEventType.Press, at,
            button = PointerButton.Secondary,
            buttons = PointerButtons(isSecondaryPressed = true),
        )
        scene.sendPointerEvent(
            PointerEventType.Release, at,
            button = PointerButton.Secondary,
            buttons = PointerButtons(),
        )
        scene.render()
    }

    private fun keyDown(key: Key, shift: Boolean = false) =
        KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift)

    // -- geometry -----------------------------------------------------------------------------

    private fun menuRow(origin: Offset, index: Int) =
        Offset(origin.x + 30f, origin.y + MENU_PADDING + index * MENU_ROW + MENU_ROW / 2f)

    private fun submenuOrigin(origin: Offset, index: Int) =
        Offset(origin.x + MENU_WIDTH, origin.y + index * MENU_ROW)

    // -- pixels -------------------------------------------------------------------------------

    private fun region(origin: Offset): IntArray {
        val pixels = scene.render().toComposeImageBitmap().toPixelMap()
        val left = origin.x.toInt()
        val top = origin.y.toInt()
        val out = IntArray(MENU_WIDTH.toInt() * 100)
        var i = 0
        for (y in top until top + 100) {
            for (x in left until left + MENU_WIDTH.toInt()) {
                out[i++] = pixels[x, y].toArgb()
            }
        }
        return out
    }

    private fun differing(before: IntArray, after: IntArray) =
        before.indices.count { before[it] != after[it] }

    private fun write(name: String) {
        File("build/reports").mkdirs()
        File("build/reports/$name").writeBytes(requireNotNull(scene.render().encodeToData()).bytes)
    }

    private companion object {
        const val ASSIGN_TO = 2
        const val ADD_RELATION = 3
        const val RELATIONS = 4
    }
}
