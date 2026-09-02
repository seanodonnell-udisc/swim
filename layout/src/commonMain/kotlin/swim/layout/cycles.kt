package swim.layout

private const val UNVISITED = 0
private const val ON_STACK = 1
private const val DONE = 2

/** Indices into [blocks] of the back-edges a depth-first search must drop to leave a forest. */
internal fun backEdgeIndices(nodes: List<LayoutNode>, blocks: List<LayoutEdge>): Set<Int> {
    val outgoing = LinkedHashMap<String, MutableList<Int>>()
    for ((index, edge) in blocks.withIndex()) outgoing.getOrPut(edge.from) { mutableListOf() }.add(index)

    val state = LinkedHashMap<String, Int>()
    for (node in nodes) state[node.id] = UNVISITED
    val backEdges = LinkedHashSet<Int>()

    // ponytail: recursion depth is the blocker-chain depth; swap in an explicit
    // stack if a real workspace ever nests deeper than the JVM stack allows.
    fun visit(id: String) {
        state[id] = ON_STACK
        for (index in outgoing[id].orEmpty()) {
            val next = blocks[index].to
            when (state[next]) {
                ON_STACK -> backEdges.add(index)
                UNVISITED -> visit(next)
            }
        }
        state[id] = DONE
    }

    for (node in nodes) if (state[node.id] == UNVISITED) visit(node.id)
    return backEdges
}
