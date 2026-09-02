package swim.core.analysis

import swim.core.graphOf
import swim.core.issue
import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertEquals

class UnblockedTest {
    @Test
    fun sortsByPriorityThenIdentifierWithNoPrioritySinkingLast() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-3", priority = 0),
                issue("MOB-1", priority = 1),
                issue("MOB-2", priority = 1),
                issue("MOB-4", priority = 4),
            ),
        )
        assertEquals(listOf("MOB-1", "MOB-2", "MOB-4", "MOB-3"), findUnblockedIssues(graph).map { it.identifier })
    }

    @Test
    fun excludesBlockedStartedAndDoneIssues() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1"),
                issue("MOB-2", stateType = WorkflowStateType.STARTED),
            ),
        )
        assertEquals(listOf("MOB-1"), findUnblockedIssues(graph).map { it.identifier })
    }
}
