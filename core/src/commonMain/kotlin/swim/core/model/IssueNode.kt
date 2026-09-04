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
    val milestoneId: String? = null,
    val milestone: String? = null,
    val milestoneSortOrder: Float? = null,
    val milestoneTargetDate: String? = null,
    val labels: List<String> = emptyList(),
    val assignee: String? = null,
    val assigneeId: String? = null,
    val estimate: Int? = null,
    val description: String? = null,
    val url: String? = null,
    val pullRequests: List<PullRequestRef>? = null,
    @Serializable(with = MillisInstantSerializer::class) val createdAt: Instant? = null,
    @Serializable(with = MillisInstantSerializer::class) val updatedAt: Instant? = null,
)
