package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PipelineStage(
    val name: String,
    val team: String,
    val issues: List<IssueNode>,
    val completed: Int = 0,
    val inProgress: Int = 0,
    val blocked: Int = 0,
    val ready: Int = 0,
)
