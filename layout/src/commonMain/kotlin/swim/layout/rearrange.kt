package swim.layout

/** Cap on the downward steps the moved subtree may take before it settles where it is. */
private const val SETTLE_GUARD = 200

/**
 * Puts the node [newEdge] blocks, and everything under it, below its new blocker, and leaves
 * every other node exactly where [current] has it.
 *
 * This is the arrange flow: the user draws "A blocks B" on a graph that is already on screen, so
 * the placement has to answer for B alone. B keeps a tidy subtree of its own, centred under A one
 * row down. It then moves straight down, as one piece, until it covers nothing that stayed put.
 * B under A already, with room around it, moves nothing at all.
 *
 * [current] is the placement on screen, which is not always the one [layout] produced: a saved
 * layout and every drag since are already in it. [nodes] and [edges] are the whole graph;
 * [newEdge] is the relation the user drew and does not have to be in [edges] yet. The result
 * holds every entry [current] held.
 *
 * Pure. The same arguments always give the same map.
 */
fun relayoutDescendants(
    current: Map<String, Position>,
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    newEdge: LayoutEdge,
    params: LayoutParams = LayoutParams(),
): Map<String, Position> {
    val byId = nodes.associateBy { it.id }
    val blocker = byId[newEdge.from] ?: return current
    val moved = byId[newEdge.to] ?: return current
    val above = current[blocker.id] ?: return current
    val was = current[moved.id] ?: return current

    val moving = movedWith(moved.id, blocker.id, edges + newEdge)
    val fixed = boxesOf(nodes.filter { it.id !in moving }, current)
    val gapX = params.siblingGap
    val gapY = params.levelGap

    // Already below its blocker and in nobody's way: the tidiest answer is to change nothing.
    if (was.y >= above.y + blocker.height &&
        firstOverlap(boxesOf(nodes.filter { it.id in moving }, current), fixed, gapX, gapY) == null
    ) {
        return current
    }

    val movingNodes = nodes.filter { it.id in moving }
    val shape = place(
        nodes = movingNodes,
        edges = edges.filter { it.from in moving && it.to in moving },
        params = params,
    ).positions
    val root = shape.getValue(moved.id)
    val shiftX = above.x + blocker.width / 2f - moved.width / 2f - root.x
    var shiftY = above.y + blocker.height + params.levelGap - root.y

    fun placedAt(offsetY: Float) = movingNodes.map { node ->
        val at = shape.getValue(node.id)
        Box(node.id, at.x + shiftX, at.y + offsetY, at.x + shiftX + node.width, at.y + offsetY + node.height)
    }

    // ponytail: straight down, one card at a time, as the layout cache settles its overlaps.
    // Sliding sideways as well would pack tighter and would have to answer which way.
    var steps = 0
    while (steps < SETTLE_GUARD) {
        val hit = firstOverlap(placedAt(shiftY), fixed, gapX, gapY) ?: break
        shiftY += hit.first.bottom + gapY - hit.second.top
        steps++
    }

    val out = LinkedHashMap(current)
    for (node in movingNodes) {
        val at = shape.getValue(node.id)
        out[node.id] = Position(at.x + shiftX, at.y + shiftY)
    }
    return out
}

/** [start] and every node it blocks, directly or not. [stop] and its own blockers stay put. */
private fun movedWith(start: String, stop: String, edges: List<LayoutEdge>): Set<String> {
    val blocked = LinkedHashMap<String, MutableList<String>>()
    for (edge in edges) {
        if (edge.kind == LayoutEdgeKind.BLOCKS) blocked.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
    }
    val moving = linkedSetOf(start)
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        for (next in blocked[pending.removeFirst()].orEmpty()) {
            if (next != stop && moving.add(next)) pending.addLast(next)
        }
    }
    return moving
}

/**
 * The first card that stands in a moved card's way: the card that stayed put first, the moved
 * card second. A card is in the way when it is closer than one sibling gap across, or one level
 * gap down, which is the room the layout leaves between two cards of its own.
 */
private fun firstOverlap(
    moving: List<Box>,
    fixed: List<Box>,
    gapX: Float,
    gapY: Float,
): Pair<Box, Box>? {
    for (box in moving) {
        val hit = fixed.firstOrNull { it.grown(gapX, gapY).overlaps(box) }
        if (hit != null) return hit to box
    }
    return null
}
