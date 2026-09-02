@file:OptIn(ExperimentalTime::class)

package swim.core.linear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import swim.core.github.parsePrUrl
import swim.core.model.IssueNode
import swim.core.model.PullRequestRef
import swim.core.model.RelationType
import swim.core.model.WorkflowStateType
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
internal data class GraphQlRequest(val query: String, val variables: JsonObject)

@Serializable
internal data class GraphQlError(val message: String = "", val extensions: Extensions? = null) {
    @Serializable
    data class Extensions(val code: String? = null, val type: String? = null)

    val code: String? get() = extensions?.code ?: extensions?.type
}

@Serializable
internal data class GraphQlEnvelope<T>(val data: T? = null, val errors: List<GraphQlError>? = null)

@Serializable
internal data class PageInfoWire(val hasNextPage: Boolean = false, val endCursor: String? = null)

@Serializable
internal data class Connection<T>(val nodes: List<T> = emptyList(), val pageInfo: PageInfoWire = PageInfoWire())

@Serializable
internal data class Nodes<T>(val nodes: List<T> = emptyList())

@Serializable
internal data class StateWire(val name: String? = null, val type: String? = null)

@Serializable
internal data class KeyWire(val key: String? = null)

@Serializable
internal data class NameWire(val name: String? = null)

// The id is what `setAssignee` needs. Without it the UI has to match the current user by
// display name.
@Serializable
internal data class AssigneeWire(val id: String? = null, val name: String? = null)

@Serializable
internal data class AttachmentWire(val url: String = "", val title: String = "")

@Serializable
internal data class RelatedIssueWire(
    val id: String = "",
    val identifier: String = "",
    val title: String = "",
    val state: StateWire? = null,
)

@Serializable
internal data class RelationWire(
    val id: String = "",
    val type: String = "",
    val relatedIssue: RelatedIssueWire? = null,
    val issue: RelatedIssueWire? = null,
)

@Serializable
internal data class IssueWire(
    val id: String = "",
    val identifier: String = "",
    val title: String = "",
    val priority: Double = 0.0,
    val estimate: Double? = null,
    val description: String? = null,
    val url: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val state: StateWire? = null,
    val team: KeyWire? = null,
    val assignee: AssigneeWire? = null,
    val project: NameWire? = null,
    val labels: Nodes<NameWire> = Nodes(),
    val attachments: Nodes<AttachmentWire> = Nodes(),
    val relations: Nodes<RelationWire>? = null,
    val inverseRelations: Nodes<RelationWire>? = null,
)

@Serializable
internal data class IssuesData(val issues: Connection<IssueWire>)

@Serializable
internal data class IssueNodesData(val issues: Nodes<IssueWire>)

@Serializable
internal data class IssueIdWire(val id: String = "", val identifier: String = "")

@Serializable
internal data class IssueIdsData(val issues: Nodes<IssueIdWire>)

@Serializable
internal data class IssueByIdData(val issue: IssueIdWire? = null)

@Serializable
internal data class TeamWire(val id: String = "", val key: String = "", val name: String = "")

@Serializable
internal data class TeamsData(val teams: Nodes<TeamWire>)

@Serializable
internal data class ProjectWire(
    val id: String = "",
    val name: String = "",
    val state: String = "",
    val teams: Nodes<KeyWire>? = null,
)

@Serializable
internal data class ProjectsData(val projects: Nodes<ProjectWire>)

@Serializable
internal data class ProjectPageData(val projects: Connection<ProjectWire>)

@Serializable
internal data class LabelWire(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val team: KeyWire? = null,
)

@Serializable
internal data class LabelsData(val issueLabels: Connection<LabelWire>)

@Serializable
internal data class UserWire(val id: String = "", val name: String = "")

@Serializable
internal data class UsersData(val users: Nodes<UserWire>)

@Serializable
internal data class ViewerWire(val name: String = "", val email: String = "")

@Serializable
internal data class ViewerData(val viewer: ViewerWire)

@Serializable
internal data class SuccessPayload(val success: Boolean = false, val issueRelation: IssueIdWire? = null)

@Serializable
internal data class RelationCreateData(val issueRelationCreate: SuccessPayload)

@Serializable
internal data class RelationDeleteData(val issueRelationDelete: SuccessPayload)

@Serializable
internal data class IssueUpdateData(val issueUpdate: SuccessPayload)

/**
 * Linear's GitHub integration stores linked pull requests as issue attachments, so no GitHub
 * call is needed to discover them. An attachment the status fetcher cannot parse is not a pull
 * request here either, so no chip is drawn that can never carry a status.
 */
internal fun pullRequestsOf(attachments: List<AttachmentWire>): List<PullRequestRef>? {
    val prs = attachments.filter { parsePrUrl(it.url) != null }
        .map { PullRequestRef(url = it.url, title = it.title) }
    return prs.ifEmpty { null }
}

/**
 * Linear added a native `duplicate` workflow state. That state is closed, so it maps to
 * CANCELED. An unknown value or an absent value maps to BACKLOG. BACKLOG never makes an issue
 * ready.
 */
internal fun workflowStateTypeOf(wire: String?): WorkflowStateType = when (wire?.lowercase()) {
    "triage" -> WorkflowStateType.TRIAGE
    "unstarted" -> WorkflowStateType.UNSTARTED
    "started" -> WorkflowStateType.STARTED
    "completed" -> WorkflowStateType.COMPLETED
    "canceled", "cancelled", "duplicate" -> WorkflowStateType.CANCELED
    else -> WorkflowStateType.BACKLOG
}

/** Linear's `similar` relation type has no place in the graph, so unknown types are dropped. */
internal fun relationTypeOf(wire: String): RelationType? = when (wire.lowercase()) {
    "blocks" -> RelationType.BLOCKS
    "related" -> RelationType.RELATED
    "duplicate" -> RelationType.DUPLICATE
    else -> null
}

private fun instantOrNull(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun IssueWire.toIssueNode(): IssueNode = IssueNode(
    id = id,
    identifier = identifier,
    title = title,
    state = state?.name ?: "Unknown",
    stateType = workflowStateTypeOf(state?.type),
    priority = priority.toInt(),
    team = team?.key ?: "Unknown",
    project = project?.name,
    labels = labels.nodes.mapNotNull { it.name },
    assignee = assignee?.name,
    assigneeId = assignee?.id,
    estimate = estimate?.toInt(),
    description = description,
    url = url,
    pullRequests = pullRequestsOf(attachments.nodes),
    createdAt = instantOrNull(createdAt),
    updatedAt = instantOrNull(updatedAt),
)
