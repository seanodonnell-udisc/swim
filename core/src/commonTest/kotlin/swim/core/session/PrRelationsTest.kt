package swim.core.session

import swim.core.analysis.buildBlockedByMap
import swim.core.analysis.buildBlocksMap
import swim.core.analysis.findBlockerChain
import swim.core.analysis.findReadySet
import swim.core.blocks
import swim.core.graphOf
import swim.core.issue
import swim.core.model.EdgeProvenance
import swim.core.model.IssueNode
import swim.core.model.PrStatus
import swim.core.model.PullRequestRef
import swim.core.model.RelationType
import swim.core.related
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun withPrs(identifier: String, vararg numbers: Int): IssueNode =
    issue(identifier).copy(pullRequests = numbers.map { PullRequestRef(url(it), "PR $it") })

private fun url(number: Int) = "https://github.com/acme/app/pull/$number"

private fun branches(vararg pairs: Pair<Int, Pair<String, String>>): Map<String, PrStatus> =
    pairs.associate { (number, refs) ->
        url(number) to PrStatus(headRefName = refs.first, baseRefName = refs.second)
    }

class PrRelationsTest {
    @Test
    fun aPullRequestStackedOnAnotherOneMakesTheLowerIssueBlockTheUpperOne() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        // ENG-2's branch starts from ENG-1's branch, so ENG-1 must land first.
        val statuses = branches(1 to ("feat-a" to "main"), 2 to ("feat-b" to "feat-a"))

        val edges = derivedBlocks(nodes, statuses, emptyList())

        assertEquals(1, edges.size)
        assertEquals("ENG-1", edges[0].from)
        assertEquals("ENG-2", edges[0].to)
        assertEquals(RelationType.BLOCKS, edges[0].type)
        assertEquals(EdgeProvenance.PR_DERIVED, edges[0].provenance)
        assertEquals(null, edges[0].relationId)
    }

    @Test
    fun aChainOfThreeBranchesMakesTwoEdgesInAStableOrder() {
        val nodes = listOf(withPrs("ENG-3", 3), withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        val statuses = branches(
            1 to ("feat-a" to "main"),
            2 to ("feat-b" to "feat-a"),
            3 to ("feat-c" to "feat-b"),
        )

        val edges = derivedBlocks(nodes, statuses, emptyList())

        assertEquals(listOf("ENG-1" to "ENG-2", "ENG-2" to "ENG-3"), edges.map { it.from to it.to })
    }

    @Test
    fun aLinearBlocksRelationInEitherDirectionSuppressesTheDerivedOne() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        val statuses = branches(1 to ("feat-a" to "main"), 2 to ("feat-b" to "feat-a"))

        assertTrue(derivedBlocks(nodes, statuses, listOf(blocks("ENG-1", "ENG-2"))).isEmpty())
        assertTrue(derivedBlocks(nodes, statuses, listOf(blocks("ENG-2", "ENG-1"))).isEmpty())
        // Only a `blocks` relation is authoritative. A `related` relation says nothing about order.
        assertEquals(1, derivedBlocks(nodes, statuses, listOf(related("ENG-1", "ENG-2"))).size)
    }

    @Test
    fun aBranchEveryPullRequestStartsFromDerivesNothing() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        val statuses = branches(1 to ("feat-a" to "main"), 2 to ("feat-b" to "main"))

        assertTrue(derivedBlocks(nodes, statuses, emptyList()).isEmpty())
        assertTrue(prStacks(nodes, statuses).isEmpty())
    }

    @Test
    fun twoIssuesOnOneBranchAreOneStack() {
        val nodes = listOf(withPrs("ENG-2", 2), withPrs("ENG-1", 1))
        val statuses = branches(1 to ("feat-a" to "main"), 2 to ("feat-a" to "main"))

        assertEquals(listOf(setOf("ENG-1", "ENG-2")), prStacks(nodes, statuses))
    }

    @Test
    fun anIssueOnTwoBranchesJoinsTheTwoStacksItBelongsTo() {
        val nodes = listOf(
            withPrs("ENG-1", 1),
            withPrs("ENG-2", 2, 3),
            withPrs("ENG-3", 4),
            withPrs("ENG-9", 9),
        )
        val statuses = branches(
            1 to ("feat-a" to "main"),
            2 to ("feat-a" to "main"),
            3 to ("feat-b" to "main"),
            4 to ("feat-b" to "main"),
            9 to ("feat-z" to "main"),
        )

        // ENG-2 shares feat-a with ENG-1 and feat-b with ENG-3, so the three are one stack.
        // ENG-9 is alone on its branch, so it is no stack at all.
        assertEquals(listOf(setOf("ENG-1", "ENG-2", "ENG-3")), prStacks(nodes, statuses))
    }

    @Test
    fun theDerivationAddsBothEdgesAndStacksToTheGraph() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2), withPrs("ENG-3", 3))
        val statuses = branches(
            1 to ("feat-a" to "main"),
            2 to ("feat-a" to "main"),
            3 to ("feat-c" to "feat-a"),
        )
        val data = graphOf(nodes, listOf(related("ENG-1", "ENG-2")))

        val derived = withPrRelations(data, statuses)

        // The Linear edge keeps its place at the front. The derived ones follow it, so a
        // cycle-breaking pass drops a derived edge before a Linear one.
        assertEquals(
            listOf(EdgeProvenance.LINEAR, EdgeProvenance.PR_DERIVED, EdgeProvenance.PR_DERIVED),
            derived.edges.map { it.provenance },
        )
        assertEquals(
            listOf("ENG-1" to "ENG-3", "ENG-2" to "ENG-3"),
            derived.edges.drop(1).map { it.from to it.to },
        )
        assertEquals(listOf(setOf("ENG-1", "ENG-2")), derived.stacks)

        assertEquals(data, withoutPrRelations(derived))
    }

    @Test
    fun anIssueWithNoStatusAndAnEmptyBranchNameDeriveNothing() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2), issue("ENG-3"))
        val statuses = branches(1 to ("" to "main"), 2 to ("feat-b" to ""))

        val data = graphOf(nodes)
        assertEquals(data, withPrRelations(data, statuses))
        assertEquals(data, withPrRelations(data, emptyMap()))
    }

    @Test
    fun theAnalysisReadsThePullRequestGraphTheWayItReadsTheLinearOne() {
        val nodes = listOf(withPrs("ENG-3", 3), withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        val statuses = branches(
            1 to ("feat-a" to "main"),
            2 to ("feat-b" to "feat-a"),
            3 to ("feat-c" to "feat-b"),
        )

        val graph = prGraph(nodes, statuses)

        // Adjacent pairs only. ENG-1 holds ENG-3 up through ENG-2, and no edge says so directly.
        assertEquals(nodes, graph.nodes)
        assertEquals(
            listOf("ENG-1" to "ENG-2", "ENG-2" to "ENG-3"),
            graph.edges.map { it.from to it.to },
        )
        assertEquals(
            mapOf("ENG-1" to setOf("ENG-2"), "ENG-2" to setOf("ENG-3")),
            buildBlocksMap(graph.edges),
        )
        assertEquals(
            mapOf("ENG-2" to setOf("ENG-1"), "ENG-3" to setOf("ENG-2")),
            buildBlockedByMap(graph.edges),
        )
        // Only the bottom of the stack can start.
        assertEquals(setOf("ENG-1"), findReadySet(graph))
        // The chain walks the transitive blocker the two adjacent edges only imply.
        assertEquals(
            listOf("ENG-2" to 1, "ENG-1" to 2),
            findBlockerChain(graph, "ENG-3").map { it.identifier to it.depth },
        )
    }

    @Test
    fun aCycleOfPullRequestsCannotHangTheAnalysis() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        // Each pull request starts from the other's branch. The analysis must end regardless.
        val statuses = branches(1 to ("feat-a" to "feat-b"), 2 to ("feat-b" to "feat-a"))

        val graph = prGraph(nodes, statuses)

        assertEquals(
            listOf("ENG-1" to "ENG-2", "ENG-2" to "ENG-1"),
            graph.edges.map { it.from to it.to },
        )
        assertEquals(
            setOf("ENG-1", "ENG-2"),
            findBlockerChain(graph, "ENG-1").map { it.identifier }.toSet(),
        )
        assertTrue(findReadySet(graph).isEmpty())
    }

    @Test
    fun aPromotedEdgeBecomesTheRealRelationAndComesBackWhenThatRelationGoes() {
        val nodes = listOf(withPrs("ENG-1", 1), withPrs("ENG-2", 2))
        val statuses = branches(1 to ("feat-a" to "main"), 2 to ("feat-b" to "feat-a"))

        // Linear holds nothing, so the pair draws as one derived edge.
        val before = withPrRelations(graphOf(nodes), statuses)
        assertEquals(listOf(EdgeProvenance.PR_DERIVED), before.edges.map { it.provenance })

        // "Make this a real blocker in Linear" writes the same pair. The reload then finds one
        // edge, the real one: the derived edge is suppressed, never doubled.
        val promoted = withPrRelations(graphOf(nodes, listOf(blocks("ENG-1", "ENG-2"))), statuses)
        assertEquals(listOf(EdgeProvenance.LINEAR), promoted.edges.map { it.provenance })
        assertEquals("ENG-1" to "ENG-2", promoted.edges[0].let { it.from to it.to })

        // Delete that relation in Linear and the branches still imply it, so the derived edge
        // returns on its own.
        val reverted = withPrRelations(graphOf(nodes), statuses)
        assertEquals(before.edges, reverted.edges)
    }
}
