package swim.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class PullRequestRef(val url: String, val title: String)

@Serializable
data class IssueNode(
    val id: String,
    val identifier: String,
    val title: String,
    val state: String,
    val stateType: WorkflowStateType,
    val priority: Int,
    val team: String,
    val project: String? = null,
    val labels: List<String> = emptyList(),
    val assignee: String? = null,
    val estimate: Int? = null,
    val description: String? = null,
    val url: String? = null,
    val pullRequests: List<PullRequestRef>? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
