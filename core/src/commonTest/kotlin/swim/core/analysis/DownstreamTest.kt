package swim.core.analysis

import swim.core.blocks
import swim.core.graphOf
import swim.core.issue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownstreamTest {
    @Test
    fun reachedSetIncludesSourcesButImpactExcludesThem() {
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C")),
            edges = listOf(blocks("A", "B"), blocks("B", "C")),
        )
        val result = findDownstreamIssues(graph, listOf("A"))
        assertEquals(listOf("A", "B", "C"), result.downstreamNodes.map { it.identifier })
        assertEquals(2, result.impactSummary.totalUnblocked)
    }

    @Test
    fun impactSummaryGroupsByTeamAndPriority() {
        val graph = graphOf(
            nodes = listOf(
                issue("A", team = "MOB", priority = 1),
                issue("B", team = "WEB", priority = 2),
                issue("C", team = "WEB", priority = 2),
            ),
            edges = listOf(blocks("A", "B"), blocks("A", "C")),
        )
        val impact = findDownstreamIssues(graph, listOf("A")).impactSummary
        assertEquals(mapOf("WEB" to 2), impact.byTeam)
        assertEquals(mapOf(2 to 2), impact.byPriority)
    }

    @Test
    fun dedupesEdgesReachedThroughMultiplePaths() {
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C")),
            edges = listOf(blocks("A", "B"), blocks("A", "B"), blocks("B", "C")),
        )
        val result = findDownstreamIssues(graph, listOf("A"))
        assertEquals(2, result.downstreamEdges.size)
    }

    @Test
    fun sourcesStartAtDepthZeroSoMaxDepthOneStillReachesDirectTargets() {
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C")),
            edges = listOf(blocks("A", "B"), blocks("B", "C")),
        )
        val result = findDownstreamIssues(graph, listOf("A"), maxDepth = 1)
        assertTrue("B" in result.downstreamNodes.map { it.identifier })
        assertTrue("C" !in result.downstreamNodes.map { it.identifier })
    }

    @Test
    fun uppercasesSourceIdentifiers() {
        val graph = graphOf(nodes = listOf(issue("A"), issue("B")), edges = listOf(blocks("A", "B")))
        val result = findDownstreamIssues(graph, listOf("a"))
        assertEquals(listOf("A"), result.sourceIssues)
    }
}
