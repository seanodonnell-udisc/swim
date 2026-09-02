package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GraphData(
    val nodes: List<IssueNode>,
    val edges: List<IssueEdge>,
    val externalBlockerStates: Map<String, WorkflowStateType> = emptyMap(),
    /**
     * Groups of issues whose pull requests share one head branch. The surface draws each group as
     * one pile of offset cards. Empty unless the pull-request derivation ran.
     */
    val stacks: List<Set<String>> = emptyList(),
)
