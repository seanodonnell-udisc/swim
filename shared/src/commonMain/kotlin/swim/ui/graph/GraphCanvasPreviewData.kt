package swim.ui.graph

import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.PrStatus
import swim.core.model.PullRequestRef
import swim.core.model.RelationType
import swim.core.model.UserSummary
import swim.core.model.WorkflowStateType
import swim.layout.Position

/** A hand-built graph covering every card category, PR badge, and edge style. */
object GraphCanvasPreview {

    private fun node(
        identifier: String,
        title: String,
        state: String,
        stateType: WorkflowStateType,
        priority: Int,
        estimate: Int? = null,
        assignee: String? = null,
        pullRequests: List<PullRequestRef>? = null,
    ) = IssueNode(
        id = identifier.lowercase(),
        identifier = identifier,
        title = title,
        state = state,
        stateType = stateType,
        priority = priority,
        team = "ENG",
        project = "Swim",
        estimate = estimate,
        assignee = assignee,
        url = "https://linear.app/swim/issue/$identifier",
        pullRequests = pullRequests,
    )

    val users: List<UserSummary> = listOf(
        UserSummary(id = "u1", name = "Ada Lovelace"),
        UserSummary(id = "u2", name = "Grace Hopper"),
    )

    val graph: GraphData = GraphData(
        nodes = listOf(
            node(
                "ENG-101", "Design the graph canvas transform and gesture map",
                "Todo", WorkflowStateType.UNSTARTED, 1, estimate = 3, assignee = "Ada Lovelace",
            ),
            node(
                "ENG-102", "Port the dark IDE palette",
                "Todo", WorkflowStateType.UNSTARTED, 2, estimate = 1,
            ),
            node(
                "ENG-103", "Node card header, body, and footer rows",
                "In Progress", WorkflowStateType.STARTED, 1, estimate = 5,
                assignee = "Grace Hopper",
                pullRequests = listOf(
                    PullRequestRef("https://github.com/swim/swim/pull/412", "Node card rows"),
                ),
            ),
            node(
                "ENG-104", "Edge routing and hit testing",
                "In Review", WorkflowStateType.STARTED, 2, estimate = 3,
                assignee = "Ada Lovelace",
                pullRequests = listOf(
                    PullRequestRef("https://github.com/swim/swim/pull/415", "Edge routing"),
                ),
            ),
            node(
                "ENG-105", "Minimap with viewport rectangle",
                "Blocked", WorkflowStateType.STARTED, 3, estimate = 2,
                pullRequests = listOf(
                    PullRequestRef("https://github.com/swim/swim/pull/418", "Minimap"),
                ),
            ),
            node(
                "ENG-106", "Relation intent chooser",
                "Paused", WorkflowStateType.STARTED, 3, estimate = 2,
            ),
            node(
                "ENG-107", "Persist hand placed node positions",
                "Backlog", WorkflowStateType.BACKLOG, 4, estimate = 5,
            ),
            node(
                "ENG-108", "Ready set highlighting",
                "Done", WorkflowStateType.COMPLETED, 2, estimate = 1,
                assignee = "Grace Hopper",
            ),
            node(
                "ENG-109", "Keyboard shortcuts for zoom and fit",
                "Todo", WorkflowStateType.UNSTARTED, 4, estimate = 1,
            ),
            node(
                "ENG-110", "Headless render test harness",
                "Canceled", WorkflowStateType.CANCELED, 0,
            ),
        ),
        edges = listOf(
            IssueEdge("ENG-101", "ENG-103", RelationType.BLOCKS, "r1"),
            IssueEdge("ENG-101", "ENG-104", RelationType.BLOCKS, "r2"),
            IssueEdge("ENG-102", "ENG-105", RelationType.BLOCKS, "r3"),
            IssueEdge("ENG-103", "ENG-106", RelationType.BLOCKS, "r4"),
            IssueEdge("ENG-104", "ENG-107", RelationType.BLOCKS, "r5"),
            IssueEdge("ENG-105", "ENG-108", RelationType.BLOCKS, "r6"),
            IssueEdge("ENG-107", "ENG-109", RelationType.BLOCKS, "r7"),
            IssueEdge("ENG-108", "ENG-110", RelationType.BLOCKS, "r8"),
            IssueEdge("ENG-102", "ENG-104", RelationType.BLOCKS, "r9"),
            IssueEdge("ENG-110", "ENG-102", RelationType.BLOCKS, "r10"),
            IssueEdge("ENG-106", "ENG-107", RelationType.RELATED, "r11"),
            IssueEdge("ENG-109", "ENG-110", RelationType.DUPLICATE, "r12"),
        ),
    )

    /** ENG-102 blocks ENG-104, but ENG-101 is the deeper blocker that owns the placement. */
    val crossLinks: Set<EdgeKey> = setOf(
        blocksEdgeKey("ENG-102", "ENG-104"),
        blocksEdgeKey("ENG-110", "ENG-102"),
    )

    /** ENG-110 blocks ENG-102, which ENG-110 already depends on: a planning bug, drawn as one. */
    val cycleEdges: Set<EdgeKey> = setOf(blocksEdgeKey("ENG-110", "ENG-102"))

    val readySet: Set<String> = setOf("ENG-101", "ENG-102", "ENG-103")

    val prStatuses: Map<String, PrStatus> = mapOf(
        "https://github.com/swim/swim/pull/412" to PrStatus("APPROVED", "SUCCESS"),
        "https://github.com/swim/swim/pull/415" to PrStatus("CHANGES_REQUESTED", "FAILURE"),
        "https://github.com/swim/swim/pull/418" to PrStatus(null, "PENDING"),
    )

    val positions: Map<String, Position> = mapOf(
        "ENG-101" to Position(60f, 40f),
        "ENG-102" to Position(400f, 40f),
        "ENG-103" to Position(60f, 220f),
        "ENG-104" to Position(400f, 220f),
        "ENG-105" to Position(740f, 220f),
        "ENG-106" to Position(60f, 400f),
        "ENG-107" to Position(400f, 400f),
        "ENG-108" to Position(740f, 400f),
        "ENG-109" to Position(400f, 580f),
        "ENG-110" to Position(740f, 580f),
    )
}
