package swim.core

import swim.core.model.GraphData
import swim.core.model.IssueEdge
import swim.core.model.IssueNode
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType

fun issue(
    identifier: String,
    team: String = "MOB",
    priority: Int = 0,
    stateType: WorkflowStateType = WorkflowStateType.UNSTARTED,
    state: String = "Todo",
    project: String? = null,
    milestone: String? = null,
    assignee: String? = null,
    labels: List<String> = emptyList(),
): IssueNode = IssueNode(
    id = "id-$identifier",
    identifier = identifier,
    title = "Title for $identifier",
    state = state,
    stateType = stateType,
    priority = priority,
    team = team,
    project = project,
    milestone = milestone,
    assignee = assignee,
    labels = labels,
)

fun blocks(from: String, to: String): IssueEdge = IssueEdge(from, to, RelationType.BLOCKS)
fun related(from: String, to: String): IssueEdge = IssueEdge(from, to, RelationType.RELATED)
fun duplicate(from: String, to: String): IssueEdge = IssueEdge(from, to, RelationType.DUPLICATE)

fun graphOf(
    nodes: List<IssueNode>,
    edges: List<IssueEdge> = emptyList(),
    externalBlockerStates: Map<String, WorkflowStateType> = emptyMap(),
): GraphData = GraphData(nodes = nodes, edges = edges, externalBlockerStates = externalBlockerStates)
