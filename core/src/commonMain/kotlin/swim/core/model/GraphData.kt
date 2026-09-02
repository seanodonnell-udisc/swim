package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GraphData(
    val nodes: List<IssueNode>,
    val edges: List<IssueEdge>,
    val externalBlockerStates: Map<String, WorkflowStateType> = emptyMap(),
)
