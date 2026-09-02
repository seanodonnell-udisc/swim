package swim.core.session

import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifiersTest {
    @Test
    fun theStateNameDecidesTheCategory() {
        val cases = listOf(
            "Done" to StateCategory.DONE,
            "Completed" to StateCategory.DONE,
            "Canceled" to StateCategory.DONE,
            "Cancelled" to StateCategory.DONE,
            "Invalid" to StateCategory.DONE,
            "In Progress" to StateCategory.IN_PROGRESS,
            "Progressing" to StateCategory.IN_PROGRESS,
            "In Review" to StateCategory.IN_REVIEW,
            "Code review" to StateCategory.IN_REVIEW,
            "Blocked" to StateCategory.BLOCKED,
            "Paused" to StateCategory.PAUSED,
            "Todo" to StateCategory.TODO,
            "TODO next" to StateCategory.TODO,
        )
        for ((name, expected) in cases) {
            assertEquals(expected, stateCategory(name), "state name: $name")
        }
    }

    @Test
    fun earlierRulesWinOverLaterOnes() {
        // "Done, blocked follow-up" matches both `done` and `blocked`; done comes first.
        assertEquals(StateCategory.DONE, stateCategory("Done, blocked follow-up"))
        // "In progress review" matches both; progress comes first.
        assertEquals(StateCategory.IN_PROGRESS, stateCategory("In progress review"))
    }

    @Test
    fun theStateTypeSettlesNamesNoSubstringCovers() {
        val cases = listOf(
            WorkflowStateType.COMPLETED to StateCategory.DONE,
            WorkflowStateType.CANCELED to StateCategory.DONE,
            WorkflowStateType.STARTED to StateCategory.IN_PROGRESS,
            WorkflowStateType.UNSTARTED to StateCategory.TODO,
            WorkflowStateType.BACKLOG to StateCategory.BACKLOG,
            WorkflowStateType.TRIAGE to StateCategory.BACKLOG,
        )
        for ((type, expected) in cases) {
            assertEquals(expected, stateCategory("In Limbo", type), "state type: $type")
        }
    }

    @Test
    fun anUnknownNameWithNoTypeIsBacklog() {
        assertEquals(StateCategory.BACKLOG, stateCategory("Shipping"))
    }
}
