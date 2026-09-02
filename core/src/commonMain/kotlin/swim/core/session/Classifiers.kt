package swim.core.session

import swim.core.model.WorkflowStateType

/** What a workflow state means to the graph. Every surface maps this to its own colors. */
enum class StateCategory { DONE, IN_PROGRESS, IN_REVIEW, BLOCKED, PAUSED, TODO, BACKLOG }

/**
 * Reads a workflow state. Linear lets a workspace name its states freely, so the name decides
 * first, by the substrings the original tool matched on. [stateType] settles the names no
 * substring covers.
 */
fun stateCategory(stateName: String, stateType: WorkflowStateType? = null): StateCategory {
    val name = stateName.lowercase()
    return when {
        DONE_WORDS.any { it in name } -> StateCategory.DONE
        "progress" in name -> StateCategory.IN_PROGRESS
        "review" in name -> StateCategory.IN_REVIEW
        "blocked" in name -> StateCategory.BLOCKED
        "paused" in name -> StateCategory.PAUSED
        "todo" in name -> StateCategory.TODO
        else -> when (stateType) {
            WorkflowStateType.COMPLETED, WorkflowStateType.CANCELED -> StateCategory.DONE
            WorkflowStateType.STARTED -> StateCategory.IN_PROGRESS
            WorkflowStateType.UNSTARTED -> StateCategory.TODO
            WorkflowStateType.BACKLOG, WorkflowStateType.TRIAGE, null -> StateCategory.BACKLOG
        }
    }
}

private val DONE_WORDS = listOf("done", "completed", "canceled", "cancelled", "invalid")
