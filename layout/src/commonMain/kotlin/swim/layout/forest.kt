package swim.layout

/** A blocker forest: one entry per root, children in input order, unused BLOCKS edges apart. */
internal class Forest(
    val roots: List<String>,
    val children: Map<String, List<String>>,
    val crossLinks: List<LayoutEdge>,
)

/** Keeps the deepest blocker of each node as its parent and reports the rest as cross-links. */
internal fun buildForest(
    nodes: List<LayoutNode>,
    blocks: List<LayoutEdge>,
    levels: Map<String, Int>,
): Forest {
    val primary = LinkedHashMap<String, Int>()
    for ((index, edge) in blocks.withIndex()) {
        val held = primary[edge.to]
        if (held == null || levels.getValue(edge.from) > levels.getValue(blocks[held].from)) {
            primary[edge.to] = index
        }
    }

    val children = LinkedHashMap<String, MutableList<String>>()
    for (node in nodes) children[node.id] = mutableListOf()
    for (node in nodes) {
        val parent = primary[node.id]?.let { blocks[it].from } ?: continue
        children.getValue(parent).add(node.id)
    }

    val chosen = primary.values.toSet()
    return Forest(
        roots = nodes.map { it.id }.filter { it !in primary },
        children = children,
        crossLinks = blocks.filterIndexed { index, _ -> index !in chosen },
    )
}

/** Maps every node reachable from each of [groupRoots] to the root it descends from. */
internal fun membership(groupRoots: List<String>, children: Map<String, List<String>>): Map<String, String> {
    val owner = LinkedHashMap<String, String>()
    for (root in groupRoots) {
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            owner[id] = root
            for (child in children[id].orEmpty()) pending.addLast(child)
        }
    }
    return owner
}
