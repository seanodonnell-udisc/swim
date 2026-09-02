package swim.core.analysis

import swim.core.blocks
import swim.core.duplicate
import swim.core.graphOf
import swim.core.issue
import kotlin.test.Test
import kotlin.test.assertEquals

class HideDuplicatesTest {
    // A duplicates B, so A goes and B, the canonical issue, stays.
    @Test
    fun removesTheDuplicateSideOfADuplicateEdgeAndItsEdges() {
        val graph = graphOf(
            nodes = listOf(issue("A"), issue("B"), issue("C")),
            edges = listOf(duplicate("A", "B"), blocks("A", "C"), blocks("C", "B")),
        )
        val result = hideDuplicates(graph)
        assertEquals(listOf("B", "C"), result.nodes.map { it.identifier })
        assertEquals(listOf("C" to "B"), result.edges.map { it.from to it.to })
    }

    @Test
    fun leavesGraphUnchangedWhenNoDuplicateEdges() {
        val graph = graphOf(nodes = listOf(issue("A"), issue("B")), edges = listOf(blocks("A", "B")))
        assertEquals(graph, hideDuplicates(graph))
    }
}
