package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class BlockerChainNode(
    val identifier: String,
    val depth: Int,
    val stateType: WorkflowStateType? = null,
    val node: IssueNode? = null,
)
