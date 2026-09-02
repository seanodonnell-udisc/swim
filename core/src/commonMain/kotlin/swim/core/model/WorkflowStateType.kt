package swim.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WorkflowStateType {
    @SerialName("triage") TRIAGE,
    @SerialName("backlog") BACKLOG,
    @SerialName("unstarted") UNSTARTED,
    @SerialName("started") STARTED,
    @SerialName("completed") COMPLETED,
    @SerialName("canceled") CANCELED,
}
