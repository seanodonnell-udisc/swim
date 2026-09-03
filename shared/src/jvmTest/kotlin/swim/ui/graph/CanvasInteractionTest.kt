package swim.ui.graph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import swim.core.model.RelationType
import swim.layout.Position
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

    // Hoisted the way GraphScreen hoists them, so a drop really round trips.
    private var positions by mutableStateOf(GraphCanvasPreview.positions)
    private var selection by mutableStateOf(emptySet<String>())
    private var reported: Map<String, Position> = emptyMap()

    private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        GraphCanvas(
            graph = GraphCanvasPreview.graph,
            positions = positions,
            modifier = Modifier.fillMaxSize(),
            readySet = GraphCanvasPreview.readySet,
            prStatuses = GraphCanvasPreview.prStatuses,
            users = GraphCanvasPreview.users,
            states = GraphCanvasPreview.states,
            crossLinks = GraphCanvasPreview.crossLinks,
            cycleEdges = GraphCanvasPreview.cycleEdges,
            selection = selection,
            state = state,
            callbacks = GraphCanvasCallbacks(
                onOpenIssue = { log += "open:$it" },
                onOpenUrl = { log += "openUrl:$it" },
                onCopyId = { log += "copy:$it" },
                onSetState = { id, stateId, name -> log += "state:$id:$stateId:$name" },
                onSetPriority = { id, p -> log += "priority:$id:$p" },
                onSetEstimate = { id, e -> log += "estimate:$id:$e" },
                onAttachPr = { id, url -> log += "attach:$id:$url" },
                onRemoveFromProject = { log += "unproject:$it" },
                onAssign = { id, user -> log += "assign:$id:$user" },
                onCreateRelation = { from, to, type, reversed ->
                    log += "create:$from:$to:$type:$reversed"
                },
                onChangeRelation = { edge, type, reversed ->
                    log += "change:${edge.from}>${edge.to}:$type:$reversed"
                },
                onRemoveRelation = { log += "remove:${it.from}>${it.to}:${it.type}" },
                onSelectionChange = {
                    log += "select:${it.sorted()}"
                    selection = it
                },
                onNodesMoved = { moved ->
                    log += "moved:${moved.keys.sorted().joinToString(",")}"
                    reported = moved
                    positions = positions + moved
                },
                onRefused = { log += "refused" },
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
    fun aRightDragStillOpensTheMenuAndNeverPans() {
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

        // The right button has one meaning now, so the travel guard that used to hold the menu
        // back for a pan is gone with the pan.
        assertTrue(state.menu is CanvasMenu.Empty, "the right drag opened no menu: ${state.menu}")
        assertEquals(before, state.offset, "a right drag panned the canvas")
    }

    @Test
    fun onlyScrollPans() {
        listOf(CanvasMode.ARRANGE, CanvasMode.INTERACT).forEach { mode ->
            state.switchTo(mode)
            scene.render()
            val before = state.offset
            scene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(700f, 400f),
                scrollDelta = Offset(1f, 2f),
            )
            scene.render()
            assertTrue(
                abs(state.offset.x - before.x) > 20f && abs(state.offset.y - before.y) > 40f,
                "scroll did not pan in $mode: $before to ${state.offset}",
            )
        }
    }

    /**
     * The zoom factor used to be `1 - delta * step`, which turns negative past a delta of about
     * eight — a precise trackpad delta — and slammed the scale into its floor mid-gesture.
     */
    @Test
    fun aFastCmdScrollZoomsSmoothlyAndOpensNothing() {
        val meta = PointerKeyboardModifiers(isMetaPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, Offset(700f, 400f), keyboardModifiers = meta)
        scene.render()
        val before = state.scale
        repeat(3) {
            scene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(700f, 400f),
                scrollDelta = Offset(0f, 12f),
                keyboardModifiers = meta,
            )
            scene.render()
        }

        assertTrue(state.scale < before, "a downward cmd+scroll did not zoom out: ${state.scale}")
        assertTrue(
            state.scale > GraphCanvasDefaults.MinScale,
            "a three-notch zoom hit the floor: ${state.scale}",
        )
        // And nothing was opened on the way. This is the reported leak, pinned.
        assertNull(state.menu, "cmd+scroll opened a context menu")
        assertNull(state.panel, "cmd+scroll opened the relation chooser")
        assertNull(state.prPanel, "cmd+scroll opened the pull-request window")
        assertNull(state.prUrlFor, "cmd+scroll opened the link-a-PR input")
        assertTrue(!state.shortcutsVisible, "cmd+scroll opened the shortcuts overlay")
        assertTrue(log.isEmpty(), "cmd+scroll reported something: $log")
    }

    @Test
    fun aHandleDragOntoAnotherCardCreatesARelation() {
        // Arrange is the default and now owns the handles: drawing a relation is arranging.
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
        // ENG-105, off to the right: no edge runs there yet, so the ghost is drawn against empty
        // canvas instead of hiding along the ENG-101 to ENG-103 line that already exists.
        val drop = cardCentre("ENG-105")
        listOf(0.25f, 0.6f, 1f).forEach { fraction ->
            scene.sendPointerEvent(
                PointerEventType.Move, handle + (drop - handle) * fraction,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            scene.render()
            // Mid-flight, with the ghost line drawn between the handle and the pointer.
            if (fraction == 0.6f) write("canvas-link-ghost.png")
        }
        scene.sendPointerEvent(
            PointerEventType.Release, drop,
            button = PointerButton.Primary,
            buttons = PointerButtons(),
        )
        scene.render()

        val panel = state.panel
        assertTrue(
            panel is CanvasPanel.Create && panel.from == "ENG-101" && panel.to == "ENG-105",
            "the drop did not offer a relation between the two cards: $panel",
        )
        click(Offset(panel.at.x + 20f, panel.at.y + MENU_PADDING + MENU_ROW / 2f))
        assertEquals(listOf("create:ENG-101:ENG-105:BLOCKS:false"), log)
    }

    @Test
    fun vAndISwitchTheModeAndEachSwitchToasts() {
        assertEquals(CanvasMode.ARRANGE, state.mode, "a new canvas must start in Arrange")
        scene.sendKeyEvent(keyDown(Key.I))
        scene.render()
        assertEquals(CanvasMode.INTERACT, state.mode)
        assertEquals("Interact", state.toast?.text, "the switch said nothing")

        scene.sendKeyEvent(keyDown(Key.V))
        scene.render()
        assertEquals(CanvasMode.ARRANGE, state.mode)
        assertEquals("Arrange", state.toast?.text)
        write("canvas-mode-toast.png")
    }

    @Test
    fun arrangeMovesACardAndInteractRefusesWithAShake() {
        val card = cardCentre("ENG-101")
        dragBy(card, Offset(60f, 40f))
        assertTrue(log.any { it.startsWith("moved:ENG-101") }, "Arrange did not move the card: $log")

        log.clear()
        state.switchTo(CanvasMode.INTERACT)
        scene.render()
        val before = state.offset
        val was = positions.getValue("ENG-102")
        dragBy(cardCentre("ENG-102"), Offset(70f, 50f))

        assertTrue(log.none { it.startsWith("moved") }, "a card moved in Interact: $log")
        assertEquals(was, positions.getValue("ENG-102"), "the card moved anyway")
        assertEquals(before, state.offset, "the drag over a card panned instead")
        // The refusal is visible and audible, not a dead gesture.
        assertEquals("ENG-102", state.refusedId, "the refused card was not named")
        assertTrue(state.refusals > 0, "the card did not shake")
        assertEquals(listOf("refused"), log, "the refusal did not beep: $log")
    }

    @Test
    fun aDropLandsExactlyWhereTheUserPutItAndMovesNothingElse() {
        val target = GraphCanvasPreview.positions.getValue("ENG-103")
        val others = positions.filterKeys { it != "ENG-101" }
        dragBy(cardCentre("ENG-101"), cardCentre("ENG-103") - cardCentre("ENG-101"))

        // What the canvas reported is exactly what the caller now holds. No rounding, no nudge.
        assertEquals(reported.getValue("ENG-101"), positions.getValue("ENG-101"))
        assertEquals(others, positions.filterKeys { it != "ENG-101" }, "another card moved")

        // And it really did land on the other card. Overlapping is the user's prerogative.
        val dropped = positions.getValue("ENG-101")
        assertTrue(
            abs(dropped.x - target.x) < 0.5f && abs(dropped.y - target.y) < 0.5f,
            "the drop did not reach the target card: $dropped against $target",
        )
    }

    @Test
    fun aSelectionDragMovesEveryChosenCardByTheSameDelta() {
        rightClick(Offset(1150f, 620f))
        click(menuRow(Offset(1150f, 620f), 3))
        assertEquals(
            GraphCanvasPreview.graph.nodes.mapTo(mutableSetOf()) { it.identifier },
            selection,
            "Select All did not select all",
        )

        val before = positions
        dragBy(cardCentre("ENG-101"), Offset(70f, 45f))

        assertEquals(before.keys, reported.keys, "the selection did not move as one unit")
        // Every reported position is exactly what the caller ended up holding.
        assertEquals(reported, positions)

        // One delta for the whole selection. Adding it to each card rounds in the last bit, so
        // the floats are compared with a tolerance, not for equality.
        val deltas = positions.map { (id, now) ->
            val was = before.getValue(id)
            Offset(now.x - was.x, now.y - was.y)
        }
        val first = deltas.first()
        assertTrue(first != Offset.Zero, "nothing moved")
        assertTrue(
            deltas.all { abs(it.x - first.x) < 0.01f && abs(it.y - first.y) < 0.01f },
            "the cards moved by different amounts: ${deltas.toSet()}",
        )
    }

    @Test
    fun aMarqueeThenAGrabDragsEveryCardInTheSelection() {
        // Touch both cards first. A real window has hovered them long before the drag, and a
        // hover is what starts their gesture coroutines.
        move(cardCentre("ENG-101"))
        move(cardCentre("ENG-103"))

        // A box over the left column only.
        dragBy(state.toScreen(Offset(40f, 20f)), state.toScreen(Offset(350f, 360f)) - state.toScreen(Offset(40f, 20f)))
        assertEquals(setOf("ENG-101", "ENG-103"), selection, "the marquee did not select both")

        val before = positions
        dragBy(cardCentre("ENG-101"), Offset(80f, 60f)) { write("canvas-multi-drag.png") }

        assertSameShift(before, listOf("ENG-101", "ENG-103"))
        assertEquals(before.getValue("ENG-102"), positions.getValue("ENG-102"), "an unselected card moved")
        assertEquals(setOf("ENG-101", "ENG-103"), selection, "the drag changed the selection")
    }

    @Test
    fun shiftClickAccumulatesAndThenDragsBothCards() {
        move(cardCentre("ENG-101"))
        shiftClick(cardCentre("ENG-101"))
        shiftClick(cardCentre("ENG-102"))
        assertEquals(setOf("ENG-101", "ENG-102"), selection, "shift click did not accumulate")

        val before = positions
        dragBy(cardCentre("ENG-102"), Offset(-50f, 70f))
        assertSameShift(before, listOf("ENG-101", "ENG-102"))
    }

    @Test
    fun theMarqueeIsAnArrangeToolOnly() {
        // Empty canvas, below the graph.
        val empty = Offset(1100f, 620f)
        dragBy(empty, Offset(-500f, -300f))
        assertTrue(
            log.any { it.startsWith("select:[ENG") },
            "Arrange drew no selection box: $log",
        )

        log.clear()
        state.switchTo(CanvasMode.INTERACT)
        scene.render()
        val before = state.offset
        dragBy(empty, Offset(-120f, -80f))

        assertTrue(log.none { it.startsWith("select:[ENG") }, "a marquee ran in Interact: $log")
        assertEquals(before, state.offset, "the empty drag panned")
    }

    @Test
    fun theRelationHandlesAreHiddenInInteract() {
        state.switchTo(CanvasMode.INTERACT)
        scene.render()
        val card = GraphCanvasPreview.positions.getValue("ENG-101")
        val handle = state.toScreen(
            Offset(
                card.x + GraphCanvasDefaults.NodeWidth / 2f,
                card.y + GraphCanvasDefaults.NodeHeight - 8f,
            )
        )
        move(state.toScreen(Offset(card.x + 40f, card.y + 40f)))
        move(handle)
        // Interact holds no handles, so the drag reaches the card, which refuses to move.
        dragBy(handle, cardCentre("ENG-103") - handle)

        assertNull(state.panel, "a handle drag offered a relation in Interact")
        assertTrue(log.none { it.startsWith("create") }, "Interact created a relation: $log")
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

    @Test
    fun clickingACardInArrangeSwitchesToInteractAndOpensItsMenu() {
        assertEquals(CanvasMode.ARRANGE, state.mode)
        val at = cardCentre("ENG-101")
        tapCanvas(at)

        assertEquals(CanvasMode.INTERACT, state.mode, "the click did not switch mode")
        assertEquals("Interact", state.toast?.text, "the switch said nothing")
        val menu = state.menu
        assertTrue(
            menu is CanvasMenu.Node && menu.id == "ENG-101",
            "the click did not open the card's menu: $menu",
        )
        write("canvas-interact-menu.png")
    }

    @Test
    fun clickingAnEdgeInArrangeSwitchesToInteractAndOpensItsPanel() {
        // The straight run below ENG-101, as in the right-click test.
        val at = state.toScreen(Offset(195f, 210f))
        tapCanvas(at)

        assertEquals(CanvasMode.INTERACT, state.mode, "the edge click did not switch mode")
        assertEquals(
            CanvasPanel.Edit(EdgeKey("ENG-101", "ENG-103", RelationType.BLOCKS), at),
            state.panel,
        )
    }

    @Test
    fun aModifierClickStillBuildsASelectionWithoutSwitchingMode() {
        move(cardCentre("ENG-101"))
        shiftClick(cardCentre("ENG-101"))
        shiftClick(cardCentre("ENG-102"))

        assertEquals(setOf("ENG-101", "ENG-102"), selection, "shift click did not accumulate")
        assertEquals(CanvasMode.ARRANGE, state.mode, "a shift click switched mode")
        assertNull(state.menu, "a shift click opened a menu")
    }

    @Test
    fun theStatusSubmenuOffersTheTeamsWorkflowStates() {
        val at = cardCentre("ENG-101")
        rightClick(at)
        click(menuRow(at, STATUS))
        move(menuRow(submenuOrigin(at, STATUS), 1))
        write("canvas-status-submenu.png")
        click(menuRow(submenuOrigin(at, STATUS), 1))

        assertEquals(listOf("state:ENG-101:s2:In Progress"), log)
    }

    @Test
    fun thePullRequestRowOpensTheInfoWindow() {
        // ENG-106's PR is the one with both branch names, so the window shows head → base.
        val at = cardCentre("ENG-106")
        rightClick(at)
        click(menuRow(at, PULL_REQUEST))

        val panel = state.prPanel
        assertTrue(
            panel != null && panel.url == "https://github.com/swim/swim/pull/419",
            "the row did not open the pull-request window: $panel",
        )
        write("canvas-pr-window.png")
    }

    @Test
    fun aPrChipClickOpensTheInfoWindowInsteadOfTheBrowser() {
        // The chip sits in the footer of ENG-103, left of the assignee.
        val card = GraphCanvasPreview.positions.getValue("ENG-103")
        tapCanvas(
            state.toScreen(
                Offset(card.x + 78f, card.y + GraphCanvasDefaults.NodeHeight - 12f),
            )
        )

        assertTrue(state.prPanel != null, "the chip opened no window")
        assertTrue(log.none { it.startsWith("open") }, "the chip opened the browser: $log")
    }

    @Test
    fun linkAPrByUrlAsksForOneAndReportsIt() {
        val at = cardCentre("ENG-101")
        rightClick(at)
        click(menuRow(at, LINK_PR))
        assertEquals("ENG-101", state.prUrlFor, "the input did not open")
    }

    // -- PR-derived edges and stacked cards -----------------------------------------------------

    @Test
    fun aDerivedEdgeOffersWhatDerivedItAndNothingToPress() {
        // ENG-106 blocks ENG-111 because ENG-111's PR starts from ENG-106's branch. The straight
        // run below ENG-106 belongs to that edge alone.
        val at = state.toScreen(Offset(195f, 600f))
        tapCanvas(at)
        assertEquals(
            CanvasPanel.Edit(EdgeKey("ENG-106", "ENG-111", RelationType.BLOCKS), at),
            state.panel,
        )
        write("canvas-derived-edge.png")

        // A Linear edge puts three "change to" rows here and Remove on the last. The info panel
        // has no rows at all, so none of those four points may mutate anything.
        repeat(4) { row -> tapCanvas(menuRow(at, row)) }
        assertNoMutation()

        // Same on the right-click menu.
        state.dismissPanels()
        rightClick(at)
        assertEquals(
            CanvasMenu.Edge(EdgeKey("ENG-106", "ENG-111", RelationType.BLOCKS), at),
            state.menu,
        )
        repeat(4) { row -> tapCanvas(menuRow(at, row)) }
        assertNoMutation()
    }

    private fun assertNoMutation() = assertTrue(
        log.none { it.startsWith("change") || it.startsWith("remove") },
        "a PR-derived edge offered a mutation: $log",
    )

    @Test
    fun clickingAPeekingCardBringsItToTheFrontOfThePile() {
        // The pile sits at (60, 760). Only the left strip of the rear card is not covered.
        val peeking = state.toScreen(Offset(66f, 800f))
        assertTrue(state.stackFront.isEmpty(), "a fresh canvas already reordered a pile")

        tapCanvas(peeking)
        assertEquals(mapOf("${STACK_PREFIX}ENG-111" to "ENG-113"), state.stackFront)
        assertEquals(
            setOf("ENG-111", "ENG-112", "ENG-113"),
            selection,
            "the pile did not select as one",
        )
        write("canvas-stack-front.png")

        // The same point now belongs to a different card, which is the pile really reordering:
        // ENG-113 moved to the front and ENG-112 fell to the back.
        tapCanvas(peeking)
        assertEquals("ENG-112", state.stackFront.getValue("${STACK_PREFIX}ENG-111"))
    }

    @Test
    fun aPileDragsAsOneSlot() {
        val before = positions.getValue("${STACK_PREFIX}ENG-111")
        // The front card's middle: the pile's position plus two 14dp steps, plus half a card.
        dragBy(state.toScreen(Offset(223f, 848f)), Offset(90f, 40f))

        assertEquals(
            setOf("${STACK_PREFIX}ENG-111"),
            reported.keys,
            "a pile drag reported something other than the one slot it occupies",
        )
        val after = positions.getValue("${STACK_PREFIX}ENG-111")
        assertTrue(
            abs(after.x - before.x) > 40f && abs(after.y - before.y) > 20f,
            "the pile did not move: $before to $after",
        )
        // No member picked up a position of its own on the way.
        assertTrue(
            positions.keys.none { it in setOf("ENG-111", "ENG-112", "ENG-113") },
            "a stacked member was given its own position: ${positions.keys}",
        )
    }

    // -- driving ------------------------------------------------------------------------------

    /**
     * Every named card moved, and all by the one delta. The size of that delta is not asserted:
     * the drag is sent in screen pixels and the positions are canvas units, so the two differ by
     * the fit scale.
     */
    private fun assertSameShift(before: Map<String, Position>, ids: List<String>) {
        val shifts = ids.map { id ->
            val was = before.getValue(id)
            val now = positions.getValue(id)
            Offset(now.x - was.x, now.y - was.y)
        }
        val first = shifts.first()
        assertTrue(first != Offset.Zero, "${ids.first()} did not move with the selection")
        assertTrue(
            shifts.all { abs(it.x - first.x) < 0.01f && abs(it.y - first.y) < 0.01f },
            "the selection did not move as one unit: ${ids.zip(shifts)}",
        )
    }

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

    /** A left drag: press, three moves so the slop is passed, release. */
    private fun dragBy(from: Offset, delta: Offset, midDrag: () -> Unit = {}) {
        move(from)
        scene.sendPointerEvent(
            PointerEventType.Press, from,
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
        )
        listOf(0.3f, 0.7f, 1f).forEach { fraction ->
            scene.sendPointerEvent(
                PointerEventType.Move, from + delta * fraction,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            scene.render()
            if (fraction == 0.7f) midDrag()
        }
        scene.sendPointerEvent(
            PointerEventType.Release, from + delta,
            button = PointerButton.Primary,
            buttons = PointerButtons(),
        )
        scene.render()
    }

    /**
     * A left click on the canvas itself. The canvas and the cards both offer a double tap, so
     * `detectTapGestures` holds `onTap` back until the double-tap window closes. That window is
     * real time on a real dispatcher, and the scene only runs its dispatcher while it renders.
     */
    private fun tapCanvas(at: Offset) {
        click(at)
        Thread.sleep(350)
        scene.render()
    }

    /**
     * A click with shift held. `detectTapGestures` waits out the double tap window before it
     * reports a single tap, and that timeout runs on the real clock, so the wait is real.
     */
    private fun shiftClick(at: Offset) {
        val shift = PointerKeyboardModifiers(isShiftPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, at, keyboardModifiers = shift)
        scene.render()
        scene.sendPointerEvent(
            PointerEventType.Press, at,
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
            keyboardModifiers = shift,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, at,
            button = PointerButton.Primary,
            buttons = PointerButtons(),
            keyboardModifiers = shift,
        )
        Thread.sleep(350)
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

    // The node menu, in order: Open issue in Linear, Open GitHub PR, Pull request…, Copy ID,
    // Assign to, Status, Priority, Points, Link a PR by URL…, Add relation, Relations,
    // Remove from project.
    private companion object {
        const val PULL_REQUEST = 2
        const val ASSIGN_TO = 4
        const val STATUS = 5
        const val LINK_PR = 8
        const val ADD_RELATION = 9
        const val RELATIONS = 10
    }
}
