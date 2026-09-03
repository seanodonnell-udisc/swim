package swim.layout

/** How far a route keeps from every card it passes, and how wide the outer margin is. */
internal const val ROUTE_CLEARANCE: Float = 8f

/**
 * How far past its card the surface layer's plain edge reaches before it turns. The test that
 * decides whether an edge needs a route allows for it, so a plain edge stays clear of the cards
 * around it. It mirrors the smooth-step offset in `swim.ui.graph`.
 */
private const val PLAIN_REACH: Float = 20f

/**
 * Corner points for the edges that cannot run straight, keyed by the edge they belong to.
 *
 * ## The contract with the surface layer
 *
 * - An edge **with** an entry is drawn as the polyline the entry holds. The first point is on
 *   the source card's edge, the last is on the target card's edge, and every stretch between
 *   them is horizontal or vertical. Feed the whole list to the smooth-step path builder, which
 *   rounds the corners of any orthogonal polyline, and take the arrowhead direction from the
 *   last two points. Do not re-derive the ends: a routed edge may leave the top of its card and
 *   meet the bottom of the other one, which the plain anchors never do.
 * - An edge **without** an entry is drawn the way it is drawn today. Nothing stands in its way.
 * - The key is the [LayoutEdge] the caller passed in. An edge kind the layout does not model,
 *   `duplicate` for one, routes as [LayoutEdgeKind.RELATED]; two relations between the same pair
 *   of cards share one route, as they share one line today.
 * - Positions are canvas space, the same space [LayoutResult.positions] is in.
 *
 * Call this again with the positions on screen after a drag or a saved layout is applied.
 * [LayoutResult.routes] holds the routes for the placement [layout] produced and nothing else.
 *
 * ponytail: routes are found on a corridor grid, not by true obstacle avoidance. A route always
 * exists, because the margin around the graph is always free, so a card gap too narrow for a
 * lane costs a longer way round rather than a crossed card.
 */
fun routeEdges(
    nodes: List<LayoutNode>,
    positions: Map<String, Position>,
    edges: List<LayoutEdge>,
    params: LayoutParams = LayoutParams(),
): Map<LayoutEdge, List<Position>> {
    if (nodes.size < 2 || edges.isEmpty()) return emptyMap()
    val boxes = boxesOf(nodes, positions)
    val plain = boxes.associateBy { it.id }
    val grown = boxes.map { it.grown(ROUTE_CLEARANCE) }
    val corridors = corridorsOf(grown, params.laneWidth, maxOf(params.levelGap, params.treeGap))
    val taken = mutableSetOf<Stretch>()

    val routes = LinkedHashMap<LayoutEdge, List<Position>>()
    for (edge in edges) {
        if (edge in routes) continue
        val from = plain[edge.from] ?: continue
        val to = plain[edge.to] ?: continue
        if (from.id == to.id) continue
        val ends = setOf(from.id, to.id)
        if (plainPaths(from, to, edge.kind).none { it.crossesACard(grown, ends) }) continue
        val (start, end) = anchorsBetween(from, to)
        val route = searchRoute(
            corridors = corridors.withLane(start.point.x, grown).withLane(end.point.x, grown),
            start = start,
            end = end,
            ends = ends,
            taken = taken,
        )
        if (route != null) routes[edge] = route
    }
    return routes
}

/**
 * True when a run of this orthogonal path enters a card. The two cards in [ends] are allowed to
 * hold the run that leaves the one and the run that meets the other, and nothing else: a path
 * that dives through the body of its own card is as unreadable as one that crosses a stranger.
 */
internal fun List<Position>.crossesACard(cards: List<Box>, ends: Set<String>): Boolean {
    val runs = zipWithNext()
    return runs.withIndex().any { (index, run) ->
        val leavingOrMeeting = index == 0 || index == runs.lastIndex
        cards.any { card ->
            !(leavingOrMeeting && card.id in ends) && card.holdsRun(run.first, run.second)
        }
    }
}

/**
 * The two shapes the surface layer draws an unrouted edge as. It leaves each card by
 * [PLAIN_REACH] and then turns once halfway down or once halfway across; which of the two it
 * picks depends on where the cards sit, so both are answered for.
 *
 * ponytail: a mirror of `swim.ui.graph.edgeEnds` and its smooth-step corners, because `:layout`
 * depends on nothing and cannot call them. Only the question "does this edge need a route" reads
 * it. A change to the plain edge shape belongs here too, and the routes themselves stay right
 * either way: the worst a stale mirror does is route an edge that did not need one.
 */
internal fun plainPaths(from: Box, to: Box, kind: LayoutEdgeKind): List<List<Position>> {
    val (start, end) = plainEnds(from, to, kind)
    val reachedStart = start.reached()
    val reachedEnd = end.reached()
    val middleY = (reachedStart.y + reachedEnd.y) / 2f
    val middleX = (reachedStart.x + reachedEnd.x) / 2f
    fun through(vararg corners: Position) =
        straighten(listOf(start.point, reachedStart) + corners + listOf(reachedEnd, end.point))
    return listOf(
        through(Position(reachedStart.x, middleY), Position(reachedEnd.x, middleY)),
        through(Position(middleX, reachedStart.y), Position(middleX, reachedEnd.y)),
    )
}

/** A point on a card's edge, and the way out of the card from it. */
private class PlainEnd(val point: Position, val awayX: Float, val awayY: Float) {
    fun reached() = Position(point.x + awayX * PLAIN_REACH, point.y + awayY * PLAIN_REACH)
}

/** Blocks edges leave the bottom and meet the top. Every other kind takes the nearest sides. */
private fun plainEnds(from: Box, to: Box, kind: LayoutEdgeKind): Pair<PlainEnd, PlainEnd> {
    if (kind == LayoutEdgeKind.BLOCKS) {
        return PlainEnd(Position(from.centreX, from.bottom), 0f, 1f) to
            PlainEnd(Position(to.centreX, to.top), 0f, -1f)
    }
    var best = PlainEnd(Position(from.left, from.centreY), -1f, 0f) to
        PlainEnd(Position(to.left, to.centreY), -1f, 0f)
    var shortest = Float.MAX_VALUE
    for (fromSide in listOf(-1f, 1f)) {
        for (toSide in listOf(-1f, 1f)) {
            val here = Position(if (fromSide < 0f) from.left else from.right, from.centreY)
            val there = Position(if (toSide < 0f) to.left else to.right, to.centreY)
            val span = (there.x - here.x) * (there.x - here.x) + (there.y - here.y) * (there.y - here.y)
            if (span < shortest) {
                shortest = span
                best = PlainEnd(here, fromSide, 0f) to PlainEnd(there, toSide, 0f)
            }
        }
    }
    return best
}

/**
 * The two ends of a route. It leaves the bottom of the card above and meets the top of the card
 * below, so it reads as the plain edge does. Two cards on one row are joined underneath, which
 * is the only way round that crosses nothing.
 */
private fun anchorsBetween(from: Box, to: Box): Pair<Anchor, Anchor> = when {
    to.top >= from.bottom -> Anchor(Position(from.centreX, from.bottom), below = true) to
        Anchor(Position(to.centreX, to.top), below = false)

    to.bottom <= from.top -> Anchor(Position(from.centreX, from.top), below = false) to
        Anchor(Position(to.centreX, to.bottom), below = true)

    else -> Anchor(Position(from.centreX, from.bottom), below = true) to
        Anchor(Position(to.centreX, to.bottom), below = true)
}
