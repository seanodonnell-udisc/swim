package swim.ui.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType
import swim.core.session.GraphGrouping
import swim.layout.Position
import swim.layout.PositionSnapshot
import swim.ui.graph.CanvasMode
import swim.ui.graph.GraphCanvas
import swim.ui.graph.GraphCanvasState
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIDTH = 1400
private const val HEIGHT = 900
private const val KEY = "q|milestone"

private fun node(id: String, milestone: String? = null) = IssueNode(
    id = id,
    identifier = id,
    title = id,
    state = "Todo",
    stateType = WorkflowStateType.UNSTARTED,
    priority = 0,
    team = "ENG",
    milestone = milestone,
)

/**
 * Drives the milestone area label with a synthetic pointer. `GroupUnderlay` is the canvas
 * underlay in the real screen, so the test mounts it the same way and skips the session.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class GroupLabelDragTest {

    private val graph = GraphData(
        nodes = listOf(node("A", "M1"), node("B", "M1"), node("C", "M2")),
        edges = listOf(IssueEdge("A", "B", RelationType.BLOCKS, "r1")),
    )

    private val state = GraphCanvasState()
    private var placement by mutableStateOf(
        placeGraph(graph, GraphGrouping.MILESTONE, KEY, PositionSnapshot()),
    )
    private var areaDrag = Offset.Zero
    private var saved: Map<String, Position> = emptyMap()

    private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        GraphCanvas(
            graph = graph,
            positions = placement.positions,
            modifier = Modifier.fillMaxSize(),
            state = state,
            underlay = {
                GroupUnderlay(
                    groups = placement.groups,
                    draggable = state.mode == CanvasMode.ARRANGE,
                    onDrag = { label, delta ->
                        areaDrag += delta
                        val moved = moveGroup(
                            placement.positions,
                            idsIn(graph, GraphGrouping.MILESTONE, label),
                            delta,
                        )
                        placement = placement.copy(positions = moved)
                            .let { it.copy(groups = groupBoxesOf(graph, GraphGrouping.MILESTONE, moved)) }
                    },
                    onDragEnd = { label ->
                        saved = placement.positions +
                            (groupOffsetKey(label) to Position(areaDrag.x, areaDrag.y))
                        areaDrag = Offset.Zero
                    },
                )
            },
        )
    }

    init {
        repeat(3) { scene.render() }
    }

    @AfterTest
    fun tearDown() = scene.close()

    @Test
    fun draggingTheLabelMovesEveryMemberAndPersistsTheOffset() {
        val before = placement.positions
        val m1 = placement.groups.first { it.label == "M1" }
        drag(labelOf(m1), Offset(140f, 90f))

        listOf("A", "B").forEach { id ->
            val was = before.getValue(id)
            val now = placement.positions.getValue(id)
            assertTrue(
                abs(now.x - was.x - 140f) < 1f && abs(now.y - was.y - 90f) < 1f,
                "$id did not follow its area: $was to $now",
            )
        }
        assertEquals(before.getValue("C"), placement.positions.getValue("C"), "M2 moved as well")

        // The whole layout plus the area's own offset went to the store.
        assertEquals(placement.positions, saved.filterKeys { !it.startsWith("@group:") })
        val offset = saved.getValue(groupOffsetKey("M1"))
        assertTrue(
            abs(offset.x - 140f) < 1f && abs(offset.y - 90f) < 1f,
            "the area offset was not recorded: $offset",
        )
    }

    @Test
    fun theLabelIsNotAGripInInteractMode() {
        scene.sendKeyEvent(KeyEvent(Key.H, KeyEventType.KeyDown))
        scene.render()
        val before = placement.positions

        val m1 = placement.groups.first { it.label == "M1" }
        drag(labelOf(m1), Offset(140f, 90f))

        assertEquals(before, placement.positions, "the label dragged the area in Pan and link")
        assertTrue(saved.isEmpty(), "an Interact drag persisted an area offset")
        assertTrue(state.offset != Offset.Zero, "the label drag did not pan instead")
    }

    /** The middle of a group's label chip, in screen pixels. */
    private fun labelOf(group: GroupBox): Offset =
        state.toScreen(Offset(group.x + 20f, group.y + 16f))

    private fun drag(from: Offset, delta: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, from)
        scene.render()
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
        }
        scene.sendPointerEvent(
            PointerEventType.Release, from + delta,
            button = PointerButton.Primary,
            buttons = PointerButtons(),
        )
        scene.render()
    }
}
