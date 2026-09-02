package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ScoredIssue(val node: IssueNode, val score: Int, val reason: String)
