package swim.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RelationType {
    @SerialName("blocks") BLOCKS,
    @SerialName("related") RELATED,
    @SerialName("duplicate") DUPLICATE,
}

@Serializable
data class IssueEdge(
    val from: String,
    val to: String,
    val type: RelationType,
    val relationId: String? = null,
)
