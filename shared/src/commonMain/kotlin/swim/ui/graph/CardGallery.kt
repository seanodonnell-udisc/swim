package swim.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import swim.core.model.IssueNode
import swim.core.model.WorkflowStateType

/**
 * One card per state category, side by side on the canvas ground.
 *
 * This exists for the shot tool. The legacy outline is two lines and a 2dp gap on a 270dp card,
 * and the only way to review it is to render the cards on their own and at a large density.
 */
@Composable
fun CardGallery(modifier: Modifier = Modifier) {
    val (left, right) = GALLERY.chunked((GALLERY.size + 1) / 2)
    Row(
        modifier = modifier.background(Swim.Bg).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        listOf(left, right).forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                column.forEach { (node, ready) -> GalleryCard(node, ready) }
            }
        }
    }
}

@Composable
private fun GalleryCard(node: IssueNode, ready: Boolean) {
    IssueCard(
        node = node,
        ready = ready,
        selected = false,
        linking = false,
        mode = CanvasMode.INTERACT,
        onOverHandle = {},
        shake = 0,
        onOpenPr = { _, _ -> },
        prStatuses = emptyMap(),
        users = emptyList(),
        handlers = INERT,
        callbacks = GraphCanvasCallbacks(),
    )
}

private val INERT = CardHandlers(
    onSelect = {},
    onOpen = {},
    onDragStart = {},
    onDrag = {},
    onDragEnd = {},
    onDragRefused = {},
    onLinkStart = {},
    onLink = {},
    onLinkEnd = {},
)

private fun card(
    identifier: String,
    state: String,
    stateType: WorkflowStateType,
    priority: Int,
) = IssueNode(
    id = identifier.lowercase(),
    identifier = identifier,
    title = "$state: the two-tone outline, the header wash and the badge",
    state = state,
    stateType = stateType,
    priority = priority,
    team = "ENG",
    estimate = 3,
    assignee = "Ada Lovelace",
)

/** Every category the classifier can reach, plus the two faces of Todo. */
private val GALLERY: List<Pair<IssueNode, Boolean>> = listOf(
    card("ENG-201", "In Progress", WorkflowStateType.STARTED, 1) to true,
    card("ENG-202", "In Review", WorkflowStateType.STARTED, 2) to true,
    card("ENG-203", "Blocked", WorkflowStateType.STARTED, 1) to false,
    card("ENG-204", "Paused", WorkflowStateType.STARTED, 3) to false,
    card("ENG-205", "Todo", WorkflowStateType.UNSTARTED, 2) to true,
    card("ENG-206", "Todo", WorkflowStateType.UNSTARTED, 4) to false,
    card("ENG-207", "Backlog", WorkflowStateType.BACKLOG, 0) to false,
    card("ENG-208", "Done", WorkflowStateType.COMPLETED, 4) to false,
)
