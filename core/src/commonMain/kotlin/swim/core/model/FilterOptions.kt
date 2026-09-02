package swim.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FilterOptions(
    val team: String? = null,
    val project: String? = null,
    val projectId: String? = null,
    val label: String? = null,
    val excludeLabel: String? = null,
    val priority: Int? = null,
    val state: String? = null,
    val stateType: String? = null,
    val assignee: String? = null,
    val includeCompleted: Boolean = false,
    val cycleId: String? = null,
)
