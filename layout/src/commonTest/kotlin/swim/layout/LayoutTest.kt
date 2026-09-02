package swim.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun node(id: String, width: Float = 100f, height: Float = 50f) = LayoutNode(id, width, height)

private fun blocks(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.BLOCKS)

private fun related(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.RELATED)

private class Sample(val name: String, val nodes: List<LayoutNode>, val edges: List<LayoutEdge>)

private val forest = Sample(
    "forest",
    listOf("A", "A1", "A2", "B", "B1", "C").map { node(it) },
    listOf(blocks("A", "A1"), blocks("A", "A2"), blocks("B", "B1")),
)

private val diamond = Sample(
    "diamond",
    listOf("A", "B", "C").map { node(it) },
    listOf(blocks("A", "C"), blocks("B", "C")),
)

private val cycle = Sample(
    "cycle",
    listOf("A", "B", "C").map { node(it) },
    listOf(blocks("A", "B"), blocks("B", "C"), blocks("C", "A")),
)

private val fanOut = Sample(
    "fan out",
    listOf(node("A")) + (1..12).map { node("A$it") },
    (1..12).map { blocks("A", "A$it") },
)

private val deepChain = Sample(
    "deep chain",
    (1..8).map { node("N$it") },
    (1..7).map { blocks("N$it", "N${it + 1}") },
)

private val mixedWidths = Sample(
    "mixed widths",
    listOf(
        node("A", width = 300f, height = 90f),
        node("A1", width = 60f),
        node("A2", width = 220f, height = 30f),
        node("A2a", width = 180f),
        node("A2b", width = 40f, height = 120f),
        node("B", width = 80f),
        node("B1", width = 260f),
    ),
    listOf(
        blocks("A", "A1"),
        blocks("A", "A2"),
        blocks("A2", "A2a"),
        blocks("A2", "A2b"),
        blocks("B", "B1"),
        blocks("A1", "B1"),
        related("A1", "B"),
    ),
)

private val relatedTrees = Sample(
    "related trees",
    listOf("A", "A1", "B", "B1", "C", "C1").map { node(it) },
    listOf(blocks("A", "A1"), blocks("B", "B1"), blocks("C", "C1"), related("A1", "C1")),
)

private val samples = listOf(forest, diamond, cycle, fanOut, deepChain, mixedWidths, relatedTrees)

private val paramSets = listOf(
    LayoutParams(),
    LayoutParams(levelGap = 10f, siblingGap = 5f, treeGap = 12f),
    LayoutParams(relatedAffinityWeight = 5f),
)

private fun Sample.sizeOf(id: String) = nodes.first { it.id == id }

class LayoutTest {
    @Test
    fun emptyGraphProducesNoPositions() {
        assertEquals(emptyMap(), layout(nodes = emptyList(), edges = emptyList()).positions)
    }

    @Test
    fun everyNodeIsPlacedOnce() {
        for (sample in samples) {
            val result = layout(sample.nodes, sample.edges)
            assertEquals(sample.nodes.map { it.id }.toSet(), result.positions.keys, sample.name)
        }
    }

    @Test
    fun blockersSitAboveWhatTheyBlock() {
        for (sample in samples) {
            for (params in paramSets) {
                val result = layout(sample.nodes, sample.edges, params)
                val placing = sample.edges
                    .filter { it.kind == LayoutEdgeKind.BLOCKS && it !in result.cycleEdges }
                for (edge in placing) {
                    val from = result.positions.getValue(edge.from)
                    val to = result.positions.getValue(edge.to)
                    assertTrue(
                        from.y + sample.sizeOf(edge.from).height < to.y,
                        "${sample.name}: ${edge.from} must sit above ${edge.to}",
                    )
                }
            }
        }
    }

    @Test
    fun nodeRectanglesNeverOverlap() {
        for (sample in samples) {
            for (params in paramSets) {
                val result = layout(sample.nodes, sample.edges, params)
                for (a in sample.nodes) {
                    for (b in sample.nodes) {
                        if (a.id >= b.id) continue
                        val pa = result.positions.getValue(a.id)
                        val pb = result.positions.getValue(b.id)
                        val apart = pa.x + a.width <= pb.x || pb.x + b.width <= pa.x ||
                            pa.y + a.height <= pb.y || pb.y + b.height <= pa.y
                        assertTrue(apart, "${sample.name}: ${a.id} overlaps ${b.id}")
                    }
                }
            }
        }
    }

    @Test
    fun rootsShareTheTopRow() {
        for (sample in samples) {
            val result = layout(sample.nodes, sample.edges)
            val blocked = sample.edges
                .filter { it.kind == LayoutEdgeKind.BLOCKS && it !in result.cycleEdges }
                .map { it.to }
                .toSet()
            for (root in sample.nodes.map { it.id } - blocked) {
                assertEquals(0f, result.positions.getValue(root).y, "${sample.name}: $root")
            }
        }
    }

    @Test
    fun leafSiblingsShareARowAndAnEvenGap() {
        val params = LayoutParams(siblingGap = 37f)
        val result = layout(fanOut.nodes, fanOut.edges, params)
        val leaves = (1..12).map { result.positions.getValue("A$it") }
        for (leaf in leaves) assertEquals(leaves.first().y, leaf.y)

        val gaps = leaves.zipWithNext { left, right -> right.x - (left.x + 100f) }
        for (gap in gaps) assertEquals(params.siblingGap, gap, absoluteTolerance = 0.001f)
    }

    @Test
    fun childrenAreCentredUnderTheirParent() {
        val result = layout(fanOut.nodes, fanOut.edges)
        val parent = result.positions.getValue("A").x + 50f
        val first = result.positions.getValue("A1").x
        val last = result.positions.getValue("A12").x + 100f
        assertEquals(parent, (first + last) / 2f, absoluteTolerance = 0.001f)
    }

    @Test
    fun aDiamondLandsOneLevelBelowBothBlockers() {
        val result = layout(diamond.nodes, diamond.edges)
        assertEquals(0f, result.positions.getValue("A").y)
        assertEquals(0f, result.positions.getValue("B").y)
        assertEquals(50f + LayoutParams().levelGap, result.positions.getValue("C").y)
        assertEquals(1, result.crossLinks.size)
        assertTrue(result.crossLinks.single() in diamond.edges)
        assertTrue(result.cycleEdges.isEmpty())
    }

    @Test
    fun theDeepestBlockerBecomesTheParent() {
        val nodes = listOf("A", "B", "C").map { node(it) }
        val edges = listOf(blocks("A", "B"), blocks("A", "C"), blocks("B", "C"))
        val result = layout(nodes, edges)

        assertEquals(listOf(blocks("A", "C")), result.crossLinks)
        assertEquals(
            result.positions.getValue("B").x,
            result.positions.getValue("C").x,
            absoluteTolerance = 0.001f,
        )
    }

    @Test
    fun oneBackEdgeBreaksACycle() {
        val result = layout(cycle.nodes, cycle.edges)
        assertEquals(1, result.cycleEdges.size)
        assertTrue(result.cycleEdges.all { it in result.crossLinks })
        assertEquals(3, result.positions.size)
        assertEquals(setOf(0f, 50f + 80f, 100f + 160f), result.positions.values.map { it.y }.toSet())
    }

    @Test
    fun theSameInputGivesTheSameResult() {
        for (sample in samples) {
            for (params in paramSets) {
                assertEquals(
                    layout(sample.nodes, sample.edges, params),
                    layout(sample.nodes, sample.edges, params),
                    sample.name,
                )
            }
        }
    }

    @Test
    fun affinityPullsRelatedTreesTogether() {
        fun spread(weight: Float): Float {
            val result = layout(
                relatedTrees.nodes,
                relatedTrees.edges,
                LayoutParams(relatedAffinityWeight = weight),
            )
            return abs(result.positions.getValue("A1").x - result.positions.getValue("C1").x)
        }
        assertTrue(spread(5f) < spread(0f), "related trees must pack closer once affinity is on")
    }
}
