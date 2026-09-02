package swim.core.analysis

import swim.core.blocks
import swim.core.graphOf
import swim.core.issue
import swim.core.model.CrossTeamBlock
import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertEquals

class PipelineTest {
    @Test
    fun countsIssuesIntoCompletedInProgressBlockedAndReady() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1", stateType = WorkflowStateType.COMPLETED),
                issue("MOB-2", stateType = WorkflowStateType.STARTED),
                issue("MOB-3", stateType = WorkflowStateType.UNSTARTED),
                issue("MOB-4", stateType = WorkflowStateType.UNSTARTED),
            ),
            edges = listOf(blocks("MOB-2", "MOB-4")),
        )
        val stage = analyzePipeline(graph).single { it.team == "MOB" }
        assertEquals(1, stage.completed)
        assertEquals(1, stage.inProgress)
        assertEquals(1, stage.blocked)
        assertEquals(1, stage.ready)
        assertEquals(4, stage.issues.size)
    }

    @Test
    fun ordersStagesByCrossTeamBlockingDescending() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1", team = "MOB"),
                issue("WEB-1", team = "WEB"),
                issue("WEB-2", team = "WEB"),
                issue("API-1", team = "API"),
            ),
            edges = listOf(
                // WEB blocks two other-team issues; MOB blocks one; API blocks none.
                blocks("WEB-1", "MOB-1"),
                blocks("WEB-2", "API-1"),
                blocks("MOB-1", "API-1"),
            ),
        )
        val teamOrder = analyzePipeline(graph).map { it.team }
        assertEquals(listOf("WEB", "MOB", "API"), teamOrder)
    }

    @Test
    fun crossTeamBlocksAggregatesByFromAndToTeam() {
        val graph = graphOf(
            nodes = listOf(
                issue("MOB-1", team = "MOB"),
                issue("MOB-2", team = "MOB"),
                issue("WEB-1", team = "WEB"),
                issue("WEB-2", team = "WEB"),
            ),
            edges = listOf(
                blocks("MOB-1", "WEB-1"),
                blocks("MOB-2", "WEB-2"),
                blocks("MOB-1", "MOB-2"), // same team, excluded
            ),
        )
        assertEquals(listOf(CrossTeamBlock(fromTeam = "MOB", toTeam = "WEB", count = 2)), getCrossTeamBlocks(graph))
    }

    @Test
    fun crossTeamBlocksIgnoresEdgesToUnfetchedNodes() {
        val graph = graphOf(
            nodes = listOf(issue("MOB-1", team = "MOB")),
            edges = listOf(blocks("MOB-1", "EXT-1")),
        )
        assertEquals(emptyList(), getCrossTeamBlocks(graph))
    }
}
