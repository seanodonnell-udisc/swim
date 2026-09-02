package swim.core.analysis

import swim.core.blocks
import swim.core.graphOf
import swim.core.issue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun weighsUrgentHighAndMediumPriority() {
        val graph = graphOf(nodes = listOf(issue("A", priority = 1), issue("B", priority = 2), issue("C", priority = 3)))
        val scores = scoreAndRankIssues(graph).associate { it.node.identifier to it.score }
        assertEquals(100, scores.getValue("A"))
        assertEquals(50, scores.getValue("B"))
        assertEquals(25, scores.getValue("C"))
    }

    @Test
    fun scoresUnblockingByFanOutAndFlagsIt() {
        val graph = graphOf(
            nodes = listOf(issue("A", priority = 0), issue("B", priority = 0), issue("C", priority = 0)),
            edges = listOf(blocks("A", "B"), blocks("A", "C")),
        )
        val scored = scoreAndRankIssues(graph).single { it.node.identifier == "A" }
        assertEquals(40, scored.score) // 2 unblocked * 20
        assertTrue(scored.reason.contains("unblocks 2 issues"))
    }

    @Test
    fun addsACrossTeamBonusOnlyForCrossTeamTargets() {
        val graph = graphOf(
            nodes = listOf(
                issue("A", team = "MOB", priority = 0),
                issue("B", team = "MOB", priority = 0),
                issue("C", team = "WEB", priority = 0),
            ),
            edges = listOf(blocks("A", "B"), blocks("A", "C")),
        )
        val scored = scoreAndRankIssues(graph).single { it.node.identifier == "A" }
        // 2 unblocked * 20 + 1 cross-team * 30
        assertEquals(70, scored.score)
        assertTrue(scored.reason.contains("enables 1 cross-team issues"))
    }

    @Test
    fun fallsBackToReadyToStartReasonWhenNothingElseApplies() {
        val graph = graphOf(nodes = listOf(issue("A", priority = 0)))
        assertEquals("ready to start", scoreAndRankIssues(graph).single().reason)
    }

    @Test
    fun excludesBlockedIssuesFromScoring() {
        val graph = graphOf(
            nodes = listOf(issue("A", priority = 1), issue("B", priority = 1)),
            edges = listOf(blocks("B", "A")),
        )
        assertEquals(listOf("B"), scoreAndRankIssues(graph).map { it.node.identifier })
    }

    @Test
    fun ranksDescendingByScoreAndRespectsCount() {
        val graph = graphOf(
            nodes = listOf(issue("A", priority = 4), issue("B", priority = 1), issue("C", priority = 2)),
        )
        assertEquals(listOf("B", "C"), scoreAndRankIssues(graph, count = 2).map { it.node.identifier })
    }
}
