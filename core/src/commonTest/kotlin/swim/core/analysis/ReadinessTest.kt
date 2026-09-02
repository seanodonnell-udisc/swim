package swim.core.analysis

import swim.core.blocks
import swim.core.graphOf
import swim.core.issue
import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessTest {
    @Test
    fun blockedByActiveBlockerIsNotReady() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1", stateType = WorkflowStateType.UNSTARTED),
                issue("MOB-2", stateType = WorkflowStateType.STARTED),
            ),
            edges = listOf(blocks("MOB-2", "MOB-1")),
        )
        assertFalse("MOB-1" in findReadySet(graph))
    }

    @Test
    fun blockedByDoneBlockerIsReady() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1", stateType = WorkflowStateType.UNSTARTED),
                issue("MOB-2", stateType = WorkflowStateType.COMPLETED),
            ),
            edges = listOf(blocks("MOB-2", "MOB-1")),
        )
        assertTrue("MOB-1" in findReadySet(graph))
    }

    @Test
    fun blockedByExternalUnknownStateStaysBlocked() {
        // The blocker isn't in the fetched node set and has no recorded state:
        // conservative rule treats an unknown state as active.
        val graph = graphOf(
            nodes = listOf(issue("MOB-1", stateType = WorkflowStateType.UNSTARTED)),
            edges = listOf(blocks("EXT-1", "MOB-1")),
        )
        assertFalse("MOB-1" in findReadySet(graph))
    }

    @Test
    fun blockedByExternalDoneStateIsReady() {
        val graph = graphOf(
            nodes = listOf(issue("MOB-1", stateType = WorkflowStateType.UNSTARTED)),
            edges = listOf(blocks("EXT-1", "MOB-1")),
            externalBlockerStates = mapOf("EXT-1" to WorkflowStateType.COMPLETED),
        )
        assertTrue("MOB-1" in findReadySet(graph))
    }

    @Test
    fun startedAndDoneIssuesAreExcludedFromReadySet() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1", stateType = WorkflowStateType.STARTED),
                issue("MOB-2", stateType = WorkflowStateType.COMPLETED),
                issue("MOB-3", stateType = WorkflowStateType.CANCELED),
                issue("MOB-4", stateType = WorkflowStateType.UNSTARTED),
            ),
        )
        assertEquals(setOf("MOB-4"), findReadySet(graph))
    }

    @Test
    fun unblockedIssueWithNoBlockersIsReady() {
        val graph = graphOf(nodes = listOf(issue("MOB-1", stateType = WorkflowStateType.BACKLOG)))
        assertTrue("MOB-1" in findReadySet(graph))
    }
}
