package swim.core.analysis

import swim.core.blocks
import swim.core.graphOf
import swim.core.issue
import swim.core.model.WorkflowStateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockerChainTest {
    @Test
    fun walksTransitiveBlockersWithIncreasingDepth() {
        // C blocks B, B blocks A: chain for A is [B@1, C@2].
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C")),
            edges = listOf(blocks("B", "A"), blocks("C", "B")),
        )
        val chain = findBlockerChain(graph, "A")
        assertEquals(listOf("B" to 1, "C" to 2), chain.map { it.identifier to it.depth })
    }

    @Test
    fun stopsAtMaxDepth() {
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C")),
            edges = listOf(blocks("B", "A"), blocks("C", "B")),
        )
        val chain = findBlockerChain(graph, "A", maxDepth = 1)
        assertEquals(listOf("B"), chain.map { it.identifier })
    }

    @Test
    fun isCycleSafe() {
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B")),
            edges = listOf(blocks("B", "A"), blocks("A", "B")),
        )
        val chain = findBlockerChain(graph, "A", maxDepth = 10)
        // Must terminate; A itself never appears as a blocker of A in the result.
        assertTrue(chain.size < 20)
    }

    @Test
    fun dedupesADiamondBlockerToItsShallowestDepth() {
        // A is blocked by B and C; both B and C are blocked by D.
        // D is reachable via two paths at the same depth: it must appear once.
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C"), issue("D")),
            edges = listOf(blocks("B", "A"), blocks("C", "A"), blocks("D", "B"), blocks("D", "C")),
        )
        val chain = findBlockerChain(graph, "A")
        val dEntries = chain.filter { it.identifier == "D" }
        assertEquals(1, dEntries.size)
        assertEquals(2, dEntries.single().depth)
    }

    @Test
    fun dedupeKeepsTheShallowerOfTwoDifferentDepths() {
        // D blocks A directly (depth 1) and also blocks B which blocks A (depth 2 via B).
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("D")),
            edges = listOf(blocks("D", "A"), blocks("B", "A"), blocks("D", "B")),
        )
        val chain = findBlockerChain(graph, "A")
        val dEntries = chain.filter { it.identifier == "D" }
        assertEquals(1, dEntries.size)
        assertEquals(1, dEntries.single().depth)
    }

    @Test
    fun blockerOutsideFetchedSetHasNullNodeButKnownState() {
        val graph = graphOf(
            nodes = listOf(issue("A")),
            edges = listOf(blocks("EXT-1", "A")),
            externalBlockerStates = mapOf("EXT-1" to WorkflowStateType.STARTED),
        )
        val entry = findBlockerChain(graph, "A").single()
        assertEquals(null, entry.node)
        assertEquals(WorkflowStateType.STARTED, entry.stateType)
    }

    @Test
    fun filterActiveBlockersExcludesDoneEntries() {
        val graph = graphOf(
            nodes = listOf(
                issue("A"),
                issue("B", stateType = WorkflowStateType.COMPLETED),
                issue("C", stateType = WorkflowStateType.UNSTARTED),
            ),
            edges = listOf(blocks("B", "A"), blocks("C", "A")),
        )
        val active = filterActiveBlockers(findBlockerChain(graph, "A"))
        assertEquals(listOf("C"), active.map { it.identifier })
    }

    @Test
    fun uppercasesTheTargetIdentifier() {
        val graph = graphOf(nodes = listOf(issue("A"), issue("B")), edges = listOf(blocks("B", "A")))
        assertEquals(listOf("B"), findBlockerChain(graph, "a").map { it.identifier })
    }
}
