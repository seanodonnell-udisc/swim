package swim.playground

import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.LayoutNode
import kotlin.random.Random

class SampleGraph(val name: String, val nodes: List<LayoutNode>, val edges: List<LayoutEdge>)

val sampleGraphs: List<SampleGraph> = listOf(
    forest(),
    diamond(),
    cycle(),
    wideFanOut(),
    deepChain(),
    relatedTrees(),
    mixed(),
)

private fun card(id: String, width: Float = 130f, height: Float = 56f) = LayoutNode(id, width, height)

private fun blocks(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.BLOCKS)

private fun related(from: String, to: String) = LayoutEdge(from, to, LayoutEdgeKind.RELATED)

private fun forest() = SampleGraph(
    name = "forest (3 trees)",
    nodes = listOf("A", "A1", "A2", "A1a", "A1b", "B", "B1", "B2", "C").map { card(it) },
    edges = listOf(
        blocks("A", "A1"),
        blocks("A", "A2"),
        blocks("A1", "A1a"),
        blocks("A1", "A1b"),
        blocks("B", "B1"),
        blocks("B", "B2"),
    ),
)

private fun diamond() = SampleGraph(
    name = "diamond",
    nodes = listOf("A", "B", "C", "D").map { card(it) },
    edges = listOf(blocks("A", "B"), blocks("A", "C"), blocks("B", "D"), blocks("C", "D")),
)

private fun cycle() = SampleGraph(
    name = "cycle",
    nodes = listOf("A", "B", "C").map { card(it) },
    edges = listOf(blocks("A", "B"), blocks("B", "C"), blocks("C", "A")),
)

private fun wideFanOut() = SampleGraph(
    name = "wide fan-out (1 blocks 12)",
    nodes = listOf(card("A")) + (1..12).map { card("A$it") },
    edges = (1..12).map { blocks("A", "A$it") },
)

private fun deepChain() = SampleGraph(
    name = "deep chain (8)",
    nodes = (1..8).map { card("N$it") },
    edges = (1..7).map { blocks("N$it", "N${it + 1}") },
)

private fun relatedTrees() = SampleGraph(
    name = "related pairs across trees",
    nodes = listOf("A", "A1", "A2", "B", "B1", "C", "C1", "C2").map { card(it) },
    edges = listOf(
        blocks("A", "A1"),
        blocks("A", "A2"),
        blocks("B", "B1"),
        blocks("C", "C1"),
        blocks("C", "C2"),
        related("A1", "C1"),
        related("A2", "C2"),
    ),
)

private fun mixed(): SampleGraph {
    val random = Random(seed = 7)
    val nodes = (1..60).map {
        card("T-$it", width = 110f + random.nextInt(6) * 20f, height = 48f + random.nextInt(4) * 14f)
    }
    val edges = mutableListOf<LayoutEdge>()

    for (index in 1 until nodes.size) {
        if (random.nextFloat() < 0.78f) {
            edges += blocks(nodes[random.nextInt(index)].id, nodes[index].id)
        }
    }
    repeat(10) {
        val a = random.nextInt(nodes.size)
        val b = random.nextInt(nodes.size)
        if (a < b) edges += blocks(nodes[a].id, nodes[b].id)
    }
    repeat(14) {
        val a = random.nextInt(nodes.size)
        val b = random.nextInt(nodes.size)
        if (a != b) edges += related(nodes[a].id, nodes[b].id)
    }
    return SampleGraph("mixed (60 nodes)", nodes, edges)
}
