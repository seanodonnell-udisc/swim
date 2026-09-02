package swim.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RelationType {
    @SerialName("blocks") BLOCKS,
    @SerialName("related") RELATED,
    @SerialName("duplicate") DUPLICATE,
}

/** Where an edge comes from. Only a [LINEAR] edge has a relation Linear can delete or change. */
@Serializable
enum class EdgeProvenance {
    @SerialName("linear") LINEAR,
    @SerialName("pr") PR_DERIVED,
}

@Serializable
data class IssueEdge(
    val from: String,
    val to: String,
    val type: RelationType,
    val relationId: String? = null,
    val provenance: EdgeProvenance = EdgeProvenance.LINEAR,
)
