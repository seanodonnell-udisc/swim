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
    realProject(),
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

// The shape of a real Linear project, anonymised, at the card size the desktop app uses.
// Each pair is `blocker>blocked`.
private const val REAL_BLOCKS =
    "58>3 14>5 55>5 48>6 50>8 57>13 14>44 57>15 34>16 59>16 22>18 25>18 28>18 33>18 24>18 " +
        "29>18 30>18 24>19 24>20 24>21 30>22 33>22 30>23 28>24 33>24 29>24 39>25 32>25 37>25 " +
        "29>25 29>26 38>27 32>27 29>27 32>28 40>28 38>28 35>29 33>30 32>30 36>31 39>32 40>32 " +
        "41>33 42>34 41>34 41>35 42>35 42>36 42>37 42>38 42>39 42>40 42>41 58>45 55>45 46>60 " +
        "47>50 50>51 58>54 57>56"

private const val REAL_RELATED =
    "3>45 8>48 14>35 20>42 20>32 22>24 29>32 30>41 48>31 33>34 39>41 48>49 50>48 52>48 52>49"

private fun realProject(): SampleGraph {
    fun pairs(text: String) = text.split(" ").map { it.split(">") }
    return SampleGraph(
        name = "real project (60 issues)",
        nodes = (1..60).map { card("F$it", width = 270f, height = 120f) },
        edges = pairs(REAL_BLOCKS).map { blocks("F${it[0]}", "F${it[1]}") } +
            pairs(REAL_RELATED).map { related("F${it[0]}", "F${it[1]}") },
    )
}
