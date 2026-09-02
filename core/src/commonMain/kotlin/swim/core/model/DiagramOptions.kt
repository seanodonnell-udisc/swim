package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DiagramOptions(
    val direction: String = "TD",
    val groupBy: String = "none",
    val showState: Boolean = true,
    val showPriority: Boolean = true,
)
