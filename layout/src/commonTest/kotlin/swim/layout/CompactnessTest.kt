package swim.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tidy-tree compactness on the shape of a real project graph.
 *
 * A tidy tree does not put every pair of neighbouring siblings at the sibling gap. Walker's
 * fourth aesthetic, which Buchheim keeps, spreads the smaller subtrees that sit between two
 * conflicting larger ones evenly across the space between them, so a small subtree can end up
 * with more room on each side. What must hold for any order is that the space is real: at least
 * one pair of sibling subtrees touches at the sibling gap, or the parent reserved room nothing
 * occupies. [ChildOrder.SHORTEST_FIRST] holds to the stronger rule, that every neighbouring
 * pair touches, because it keeps the small subtrees out from between the tall ones.
 */
private class Shape(val name: String, val nodes: List<LayoutNode>, val edges: List<LayoutEdge>)

private val shapes = listOf(
    Shape("real shape (open issues)", realShapeNodes, realShapeBlocks + realShapeRelated),
    Shape("real shape (every issue)", fullShapeNodes, fullShapeBlocks + fullShapeRelated),
)

private val fullShape = shapes.last()

private class Placed(val shape: Shape, val params: LayoutParams) {
    val result = layout(shape.nodes, shape.edges, params)
    val placing = shape.edges.filter { it.kind == LayoutEdgeKind.BLOCKS && it !in result.cycleEdges }
    val levels = assignLevels(shape.nodes, placing)
    val forest = buildForest(shape.nodes, placing, levels)
    val widths = shape.nodes.associate { it.id to it.width }
    private val heights = subtreeHeights(forest.children, levels)

    /** The children of [parent] in the order the layout placed them. */
    fun childrenOf(parent: String): List<String> {
        val kids = forest.children.getValue(parent)
        return if (params.childOrder == ChildOrder.FETCH) kids else kids.sortedBy { heights.getValue(it) }
    }

    fun subtree(root: String): List<String> {
        val out = mutableListOf<String>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            out += id
            forest.children[id].orEmpty().forEach { pending.addLast(it) }
        }
        return out
    }

    fun left(id: String) = result.positions.getValue(id).x

    fun right(id: String) = left(id) + widths.getValue(id)

    fun span(root: String): Float {
        val members = subtree(root)
        return members.maxOf(::right) - members.minOf(::left)
    }

    /** The clearance between two sibling subtrees at the level where they come closest. */
    fun clearance(left: String, right: String): Float? {
        val leftRows = subtree(left).groupBy { levels.getValue(it) }
        val rightRows = subtree(right).groupBy { levels.getValue(it) }
        val shared = leftRows.keys intersect rightRows.keys
        if (shared.isEmpty()) return null
        return shared.minOf { level ->
            rightRows.getValue(level).minOf(::left) - leftRows.getValue(level).maxOf(::right)
        }
    }
}

class CompactnessTest {
    /** Every parent has two sibling subtrees whose contours meet at the sibling gap. */
    @Test
    fun someSiblingSubtreesTouch() {
        for (order in ChildOrder.entries) {
            val params = LayoutParams(childOrder = order)
            for (shape in shapes) {
                val placed = Placed(shape, params)
                for (parent in placed.forest.children.keys) {
                    val kids = placed.childrenOf(parent)
                    if (kids.size < 2) continue
                    val tightest = kids.indices
                        .flatMap { i -> (i + 1 until kids.size).map { j -> i to j } }
                        .mapNotNull { (i, j) -> placed.clearance(kids[i], kids[j]) }
                        .minOrNull() ?: continue
                    assertTrue(
                        tightest <= params.siblingGap + 1f,
                        "$order ${shape.name}: the children of $parent stay $tightest apart, " +
                            "and the sibling gap is ${params.siblingGap}",
                    )
                }
            }
        }
    }

    /** A loose bound a compact tree always meets: no tree is wider than all of its rows in a line. */
    @Test
    fun noTreeIsWiderThanAllOfItsRowsTogether() {
        for (order in ChildOrder.entries) {
            val params = LayoutParams(childOrder = order)
            for (shape in shapes) {
                val placed = Placed(shape, params)
                for (root in placed.forest.roots) {
                    val members = placed.subtree(root)
                    val allRows = members.groupBy { placed.levels.getValue(it) }.values.sumOf { row ->
                        row.sumOf { placed.widths.getValue(it).toDouble() } +
                            params.siblingGap * (row.size - 1)
                    }.toFloat()
                    assertTrue(
                        placed.span(root) <= allRows + 1f,
                        "$order ${shape.name}: tree $root spans ${placed.span(root)}, " +
                            "and all of its rows in a line are $allRows",
                    )
                }
            }
        }
    }

    /**
     * The holes are gone. With the children in fetch order the worst neighbouring pair on this
     * shape stays 311.25 apart, which is more than a card of empty space. Shortest first brings
     * every neighbouring pair down to the sibling gap.
     */
    @Test
    fun shortestFirstLeavesNoHoleBetweenNeighbours() {
        val params = LayoutParams(childOrder = ChildOrder.SHORTEST_FIRST)
        val placed = Placed(fullShape, params)
        for (parent in placed.forest.children.keys) {
            for ((left, right) in placed.childrenOf(parent).zipWithNext()) {
                val gap = placed.clearance(left, right) ?: continue
                assertTrue(
                    gap <= params.siblingGap + 1f,
                    "${fullShape.name}: $parent leaves $gap between $left and $right, " +
                        "and the sibling gap is ${params.siblingGap}",
                )
            }
        }
    }

    /** Shortest first only moves the slack around. No tree gets wider or narrower. */
    @Test
    fun shortestFirstKeepsEveryTreeTheSameWidth() {
        val fetched = Placed(fullShape, LayoutParams(childOrder = ChildOrder.FETCH))
        val sorted = Placed(fullShape, LayoutParams(childOrder = ChildOrder.SHORTEST_FIRST))
        for (root in fetched.forest.roots) {
            assertEquals(fetched.span(root), sorted.span(root), absoluteTolerance = 0.001f, "tree $root")
        }
    }
}
