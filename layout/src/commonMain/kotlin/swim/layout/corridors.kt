package swim.layout

/** At most this many lanes share one corridor. A wider corridor does not carry more. */
private const val MAX_LANES = 3

/** A card as the router sees it: an axis-aligned box in canvas space. */
internal class Box(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f

    fun grown(by: Float): Box = grown(by, by)

    fun grown(byX: Float, byY: Float): Box = Box(id, left - byX, top - byY, right + byX, bottom + byY)

    /** True when this box stands across the whole x range [from]..[to]. */
    fun coversX(from: Float, to: Float): Boolean = left <= from && right >= to

    /** True when this box stands anywhere in the y range between [a] and [b]. */
    fun spansY(a: Float, b: Float): Boolean = top < maxOf(a, b) && bottom > minOf(a, b)

    fun overlaps(other: Box): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    /** True when the horizontal or vertical run from [a] to [b] enters this box. */
    fun holdsRun(a: Position, b: Position): Boolean =
        left < maxOf(a.x, b.x) && right > minOf(a.x, b.x) &&
            top < maxOf(a.y, b.y) && bottom > minOf(a.y, b.y)
}

internal fun boxesOf(nodes: List<LayoutNode>, positions: Map<String, Position>): List<Box> =
    nodes.mapNotNull { node ->
        val at = positions[node.id] ?: return@mapNotNull null
        Box(node.id, at.x, at.y, at.x + node.width, at.y + node.height)
    }

/** A one-dimensional range. */
internal class Span(val start: Float, val end: Float) {
    val width: Float get() = end - start
    val middle: Float get() = (start + end) / 2f
}

/** The ranges that no span in [spans] covers, plus one [outer] wide range past each end. */
internal fun gapsBetween(spans: List<Span>, outer: Float): List<Span> {
    if (spans.isEmpty()) return emptyList()
    val sorted = spans.sortedBy { it.start }
    val gaps = mutableListOf(Span(sorted.first().start - outer, sorted.first().start))
    var reach = sorted.first().end
    for (span in sorted) {
        if (span.start > reach) gaps += Span(reach, span.start)
        if (span.end > reach) reach = span.end
    }
    gaps += Span(reach, reach + outer)
    return gaps
}

/**
 * Up to [MAX_LANES] lanes, [laneWidth] apart and centred in [span]. None when none fit.
 *
 * ponytail: a channel carries what fits in it and no more, so a gap of one sibling gap holds one
 * lane, and the routes past that point share it and draw over each other. Widening the gap is
 * the upgrade, and it is a placement change, not a router change: the tidy tree takes a
 * separation per pair of neighbours, which is where the extra room would come from. It is not
 * done here because `CompactnessTest.shortestFirstLeavesNoHoleBetweenNeighbours` holds every
 * pair of neighbouring sibling subtrees to one sibling gap. A wider corridor is a wider hole,
 * and the hole is the defect that test was written for.
 */
internal fun lanesIn(span: Span, laneWidth: Float): List<Float> {
    val count = (span.width / laneWidth).toInt().coerceAtMost(MAX_LANES)
    if (count <= 0) return emptyList()
    val first = span.middle - (count - 1) * laneWidth / 2f
    return (0 until count).map { first + it * laneWidth }
}

/**
 * The free space around one placed graph.
 *
 * [bands] are the y positions of the horizontal corridors between the rows of cards. No card
 * occupies any of them at any x, so a route may run along a band from end to end.
 *
 * [lanes] are the x positions of the vertical corridors. Every lane sits in a channel between
 * two card sides, so the same cards stand in it at every y: [blockers] holds them, and a route
 * may run down a lane wherever none of its blockers is in the way.
 *
 * A route that only travels along bands and lanes cannot cross a card.
 */
internal class Corridors(
    val lanes: List<Float>,
    val blockers: List<List<Box>>,
    val bands: List<Float>,
) {
    /** The same corridors with one more lane on [x], for a route to leave or enter a card on. */
    fun withLane(x: Float, boxes: List<Box>): Corridors {
        val at = lanes.indexOfFirst { it >= x }
        if (at >= 0 && lanes[at] == x) return this
        val index = if (at < 0) lanes.size else at
        return Corridors(
            lanes = lanes.toMutableList().apply { add(index, x) },
            blockers = blockers.toMutableList().apply {
                add(index, boxes.filter { it.coversX(x, x) })
            },
            bands = bands,
        )
    }
}

/**
 * Reads the corridors off the placed cards. [grown] are the card boxes with the clearance a
 * route keeps from them already added, so every corridor is clear of the cards by that much.
 * [outer] is how far the corridors reach past the outermost cards; a route that has nowhere
 * else to go takes the way around.
 */
internal fun corridorsOf(grown: List<Box>, laneWidth: Float, outer: Float): Corridors {
    val bands = gapsBetween(grown.map { Span(it.top, it.bottom) }, outer).flatMap { lanesIn(it, laneWidth) }

    // Between two neighbouring card sides the same cards stand at every y, so one blocker list
    // serves the whole channel.
    val cuts = grown.flatMap { listOf(it.left, it.right) }.distinct().sorted()
    val channels = if (cuts.isEmpty()) {
        emptyList()
    } else {
        listOf(Span(cuts.first() - outer, cuts.first())) +
            (0 until cuts.lastIndex).map { Span(cuts[it], cuts[it + 1]) } +
            Span(cuts.last(), cuts.last() + outer)
    }

    val lanes = mutableListOf<Float>()
    val blockers = mutableListOf<List<Box>>()
    for (channel in channels) {
        val standing = grown.filter { it.coversX(channel.start, channel.end) }
        for (x in lanesIn(channel, laneWidth)) {
            lanes += x
            blockers += standing
        }
    }
    return Corridors(lanes, blockers, bands.sorted())
}
