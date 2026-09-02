package swim.layout

/** Longest-path level per node: 0 with no blockers, else one below the deepest blocker. */
internal fun assignLevels(nodes: List<LayoutNode>, blocks: List<LayoutEdge>): Map<String, Int> {
    val levels = LinkedHashMap<String, Int>()
    val blockerCount = LinkedHashMap<String, Int>()
    for (node in nodes) {
        levels[node.id] = 0
        blockerCount[node.id] = 0
    }

    val blocked = LinkedHashMap<String, MutableList<String>>()
    for (edge in blocks) {
        blocked.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
        blockerCount[edge.to] = blockerCount.getValue(edge.to) + 1
    }

    val ready = ArrayDeque(nodes.map { it.id }.filter { blockerCount.getValue(it) == 0 })
    while (ready.isNotEmpty()) {
        val id = ready.removeFirst()
        for (next in blocked[id].orEmpty()) {
            levels[next] = maxOf(levels.getValue(next), levels.getValue(id) + 1)
            blockerCount[next] = blockerCount.getValue(next) - 1
            if (blockerCount.getValue(next) == 0) ready.addLast(next)
        }
    }
    return levels
}
