package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ImpactSummary(
    val totalUnblocked: Int,
    val byTeam: Map<String, Int>,
    val byPriority: Map<Int, Int>,
)

@Serializable
data class DownstreamResult(
    val sourceIssues: List<String>,
    val downstreamNodes: List<IssueNode>,
    val downstreamEdges: List<IssueEdge>,
    val impactSummary: ImpactSummary,
)
