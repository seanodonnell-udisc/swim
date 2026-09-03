package swim.layout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How close a drawn edge may come to a card it does not belong to. */
private const val MARGIN = 4f

private fun node(id: String, width: Float = 130f, height: Float = 56f) = LayoutNode(id, width, height)

private fun blocks(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.BLOCKS)

private fun related(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.RELATED)

private class Case(val name: String, val nodes: List<LayoutNode>, val edges: List<LayoutEdge>)

private val cycle = Case(
    "three node cycle",
    listOf("A", "B", "C").map { node(it) },
    listOf(blocks("A", "B"), blocks("B", "C"), blocks("C", "A")),
)

private val diamond = Case(
    "diamond",
    listOf("A", "B", "C", "D").map { node(it) },
    listOf(blocks("A", "B"), blocks("A", "C"), blocks("B", "D"), blocks("C", "D")),
)

private val levelSkip = Case(
    "level skip",
    listOf("A", "B", "C", "D", "E").map { node(it) },
    listOf(blocks("A", "B"), blocks("B", "C"), blocks("C", "D"), blocks("D", "E"), blocks("A", "D"), blocks("A", "E")),
)

private val acrossARow = Case(
    "related across a row",
    listOf(node("P")) + (1..6).map { node("P$it") },
    (1..6).map { blocks("P", "P$it") } + listOf(related("P1", "P6"), related("P2", "P5")),
)

private val realProject = Case(
    "real project (60 issues)",
    fullShapeNodes,
    fullShapeBlocks + fullShapeRelated,
)

private val cases = listOf(cycle, diamond, levelSkip, acrossARow, realProject)

/** A blocker forest with cycles, level skips and related pairs, from a fixed seed. */
private fun randomCase(seed: Int): Case {
    val random = Random(seed)
    val count = 6 + random.nextInt(15)
    val nodes = (1..count).map {
        node("R$it", width = 110f + random.nextInt(5) * 40f, height = 48f + random.nextInt(3) * 22f)
    }
    val edges = mutableListOf<LayoutEdge>()
    for (index in 1 until count) {
        if (random.nextFloat() < 0.7f) edges += blocks(nodes[random.nextInt(index)].id, nodes[index].id)
    }
    repeat(1 + count / 3) {
        val one = random.nextInt(count)
        val other = random.nextInt(count)
        if (one != other) {
            val id = nodes[one].id to nodes[other].id
            edges += if (random.nextBoolean()) blocks(id.first, id.second) else related(id.first, id.second)
        }
    }
    return Case("random $seed", nodes, edges)
}

/** The edges that enter a card, drawn the way [routes] says to draw them. */
private fun crossings(shape: Case, result: LayoutResult, routes: Map<LayoutEdge, List<Position>>): Int {
    val cards = boxesOf(shape.nodes, result.positions)
    val byId = cards.associateBy { it.id }
    val padded = cards.map { it.grown(MARGIN) }
    var count = 0
    for (edge in shape.edges.distinct()) {
        val from = byId[edge.from] ?: continue
        val to = byId[edge.to] ?: continue
        val ends = setOf(edge.from, edge.to)
        val drawn = routes[edge]?.let { listOf(it) } ?: plainPaths(from, to, edge.kind)
        if (drawn.any { it.crossesACard(padded, ends) }) count++
    }
    return count
}

class RouteTest {
    @Test
    fun noEdgeCrossesACard() {
        val counts = mutableListOf<String>()
        for (shape in cases) {
            val result = layout(shape.nodes, shape.edges)
            val before = crossings(shape, result, emptyMap())
            val after = crossings(shape, result, result.routes)
            counts += "${shape.name}: ${shape.edges.size} edges, ${result.routes.size} routed, " +
                "$before crossing before, $after after"
            assertEquals(0, after, "${shape.name}: $after edges still cross a card")
        }
        println(counts.joinToString("\n"))
    }

    @Test
    fun noRandomGraphCrossesACard() {
        var before = 0
        var after = 0
        var routed = 0
        for (seed in 1..200) {
            val shape = randomCase(seed)
            val result = layout(shape.nodes, shape.edges)
            before += crossings(shape, result, emptyMap())
            routed += result.routes.size
            val left = crossings(shape, result, result.routes)
            assertEquals(0, left, "${shape.name}: $left edges still cross a card")
            after += left
        }
        println("200 random graphs: $routed routed, $before crossing before, $after after")
    }

    @Test
    fun everyRouteRunsFromItsCardToTheOtherAlongOneAxisAtATime() {
        for (shape in cases) {
            val result = layout(shape.nodes, shape.edges)
            val boxes = boxesOf(shape.nodes, result.positions).associateBy { it.id }
            for ((edge, route) in result.routes) {
                val from = boxes.getValue(edge.from)
                val to = boxes.getValue(edge.to)
                assertTrue(route.size >= 2, "${shape.name}: ${edge.from} to ${edge.to} has no run")
                assertEquals(from.centreX, route.first().x, 0.001f, "${shape.name}: ${edge.from} start")
                assertTrue(
                    route.first().y == from.top || route.first().y == from.bottom,
                    "${shape.name}: ${edge.from} must start on the top or the bottom of its card",
                )
                assertEquals(to.centreX, route.last().x, 0.001f, "${shape.name}: ${edge.to} end")
                assertTrue(
                    route.last().y == to.top || route.last().y == to.bottom,
                    "${shape.name}: ${edge.to} must end on the top or the bottom of its card",
                )
                for ((one, two) in route.zipWithNext()) {
                    assertTrue(
                        one.x == two.x || one.y == two.y,
                        "${shape.name}: ${edge.from} to ${edge.to} runs on both axes at once",
                    )
                }
            }
        }
    }

    @Test
    fun theSameGraphAlwaysRoutesTheSameWay() {
        for (shape in cases + (1..20).map { randomCase(it) }) {
            val first = layout(shape.nodes, shape.edges)
            val second = layout(shape.nodes, shape.edges)
            assertEquals(first.routes, second.routes, shape.name)
        }
    }

    @Test
    fun aStraightEdgeKeepsThePlainShape() {
        // One blocker over two children: nothing stands in the way, so nothing is routed.
        val nodes = listOf("A", "A1", "A2").map { node(it) }
        val result = layout(nodes, listOf(blocks("A", "A1"), blocks("A", "A2")))
        assertEquals(emptyMap(), result.routes)
    }

    @Test
    fun routesFollowTheLanesTheParamsAsk() {
        val wide = layout(acrossARow.nodes, acrossARow.edges, LayoutParams(laneWidth = 60f))
        val narrow = layout(acrossARow.nodes, acrossARow.edges, LayoutParams(laneWidth = 12f))
        assertEquals(wide.positions, narrow.positions, "lane width must not move a card")
        assertTrue(wide.routes.isNotEmpty() && narrow.routes.isNotEmpty())
    }
}
