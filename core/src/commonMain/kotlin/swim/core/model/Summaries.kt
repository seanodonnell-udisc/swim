package swim.core.model

import kotlinx.serialization.Serializable

/** A team, as the filter bar and the `teams` command show it. */
@Serializable
data class TeamSummary(val id: String, val key: String, val name: String)

/** A project. `teams` is empty unless the summary came from `getProjectSummaries`. */
@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val state: String,
    val teams: List<String> = emptyList(),
)

/** A label. `team` is null for workspace labels. */
@Serializable
data class LabelSummary(
    val id: String,
    val name: String,
    val color: String,
    val team: String? = null,
)

/** An active workspace member, for the assignee picker. */
@Serializable
data class UserSummary(val id: String, val name: String)

/** The signed-in user, from the `viewer` auth probe. */
@Serializable
data class Viewer(val name: String, val email: String)

/** One relation, read from the point of view of the issue that owns the detail. */
@Serializable
data class IssueRelationDetail(
    val type: String,
    val identifier: String,
    val title: String,
    val state: String,
    val relationId: String,
)

/** One issue with every relation that touches it. */
@Serializable
data class IssueDetail(val node: IssueNode, val relations: List<IssueRelationDetail>)

/** Review and check state for one pull request. */
@Serializable
data class PrStatus(val reviewDecision: String? = null, val checkState: String? = null)
