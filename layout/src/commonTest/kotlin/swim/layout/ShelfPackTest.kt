package swim.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShelfPackTest {
    private fun singletons(count: Int) = (1..count).map { LayoutNode("N-$it", 270f, 120f) }

    @Test
    fun manySingleTreesWrapIntoShelves() {
        val nodes = singletons(20)
        val result = layout(nodes, emptyList(), LayoutParams(maxRowWidth = 1000f))
        val rights = result.positions.map { (id, p) -> p.x + 270f }
        assertTrue(rights.max() <= 1000f, "no tree ends past the row limit")
        val shelfCount = result.positions.values.map { it.y }.distinct().size
        assertTrue(shelfCount >= 7, "twenty 270-wide trees at 1000 max need many shelves")
    }

    @Test
    fun aTreeWiderThanTheLimitGetsItsOwnShelf() {
        val nodes = listOf(
            LayoutNode("A", 270f, 120f),
            LayoutNode("B1", 900f, 120f), LayoutNode("B2", 900f, 120f), LayoutNode("B3", 900f, 120f),
            LayoutNode("root", 270f, 120f),
        )
        val edges = listOf("B1", "B2", "B3").map { LayoutEdge("root", it, LayoutEdgeKind.BLOCKS) }
        val result = layout(nodes, edges, LayoutParams(maxRowWidth = 1000f, siblingGap = 40f))
        val aY = result.positions.getValue("A").y
        val rootY = result.positions.getValue("root").y
        assertTrue(rootY != aY, "the wide tree wraps to its own shelf")
    }

    @Test
    fun shelvesDoNotOverlapVertically() {
        val nodes = singletons(9) + listOf(
            LayoutNode("R", 270f, 120f), LayoutNode("C1", 270f, 200f), LayoutNode("C2", 270f, 200f),
        )
        val edges = listOf(LayoutEdge("R", "C1", LayoutEdgeKind.BLOCKS), LayoutEdge("R", "C2", LayoutEdgeKind.BLOCKS))
        val result = layout(nodes, edges, LayoutParams(maxRowWidth = 900f))
        val boxes = result.positions.map { (id, p) ->
            val n = nodes.first { it.id == id }
            listOf(p.x, p.y, p.x + n.width, p.y + n.height)
        }
        for (a in boxes) for (b in boxes) if (a !== b) {
            val overlap = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
            assertTrue(!overlap, "no two nodes overlap across shelves")
        }
    }

    @Test
    fun oneShelfMatchesTheOldSingleRowPacking() {
        val nodes = singletons(3)
        val result = layout(nodes, emptyList(), LayoutParams(maxRowWidth = 10_000f))
        assertEquals(setOf(0f), result.positions.values.map { it.y }.toSet())
        assertEquals(listOf(0f, 390f, 780f), result.positions.entries.sortedBy { it.value.x }.map { it.value.x })
    }
}
