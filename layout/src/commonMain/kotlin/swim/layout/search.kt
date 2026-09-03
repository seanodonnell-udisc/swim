package swim.layout

import kotlin.math.abs

/** Cost of one corner, so a route bends as few times as the corridors allow. */
private const val TURN_COST = 60f

/**
 * Cost of a stretch another route already took, so parallel runs spread across the lanes. It
 * has to beat the price of stepping one lane over, which is two corners and the lane width.
 */
private const val REUSE_COST = 120f

/** Where a route leaves or meets a card: a point on its edge, and the side the corridor is on. */
internal class Anchor(val point: Position, val below: Boolean)

/**
 * One stretch of corridor a route occupies: the lane or band it runs on, and the far end it
 * starts from. Two routes that take the same stretch draw over each other, so the second one
 * pays for it.
 */
internal data class Stretch(val on: Float, val from: Float, val vertical: Boolean)

/**
 * The cheapest orthogonal path from [start] to [end] along [corridors], as the corner points
 * with both anchors included. Null when either anchor reaches no band.
 *
 * [ends] are the two cards the route belongs to. They block nothing on the way out of the card
 * and into the next corridor, and everything after that.
 *
 * ponytail: a grid search over the corridors, not true obstacle avoidance. It cannot thread a
 * card gap too narrow for a lane, and it cannot move a card out of the way. Both need the
 * placement to change, which is [layout]'s business, not the router's.
 */
internal fun searchRoute(
    corridors: Corridors,
    start: Anchor,
    end: Anchor,
    ends: Set<String>,
    taken: MutableSet<Stretch>,
): List<Position>? {
    val xs = corridors.lanes
    val ys = corridors.bands
    val rows = ys.size
    if (xs.isEmpty() || rows == 0) return null

    val startColumn = xs.indexOf(start.point.x)
    val endColumn = xs.indexOf(end.point.x)
    if (startColumn < 0 || endColumn < 0) return null
    val startRow = bandFrom(start, ys, corridors.blockers[startColumn], ends) ?: return null
    val endRow = bandFrom(end, ys, corridors.blockers[endColumn], ends) ?: return null

    fun state(column: Int, row: Int, vertical: Boolean) =
        (column * rows + row) * 2 + if (vertical) 1 else 0

    val best = FloatArray(xs.size * rows * 2) { Float.MAX_VALUE }
    val cameFrom = IntArray(best.size) { -1 }
    val settled = BooleanArray(best.size)
    val frontier = Frontier()
    val first = state(startColumn, startRow, vertical = true)
    best[first] = 0f
    frontier.push(first, 0f)

    var goal = -1
    while (!frontier.isEmpty) {
        val here = frontier.pop()
        if (settled[here]) continue
        settled[here] = true
        val vertical = here % 2 == 1
        val row = here / 2 % rows
        val column = here / 2 / rows
        if (column == endColumn && row == endRow) {
            goal = here
            break
        }

        fun relax(nextColumn: Int, nextRow: Int, nextVertical: Boolean, span: Float, stretch: Stretch) {
            val next = state(nextColumn, nextRow, nextVertical)
            if (settled[next]) return
            val cost = best[here] + span +
                (if (nextVertical == vertical) 0f else TURN_COST) +
                (if (stretch in taken) REUSE_COST else 0f)
            if (cost >= best[next]) return
            best[next] = cost
            cameFrom[next] = here
            frontier.push(next, cost)
        }

        for (step in listOf(-1, 1)) {
            val sideways = column + step
            if (sideways in xs.indices) {
                relax(
                    sideways,
                    row,
                    nextVertical = false,
                    span = abs(xs[sideways] - xs[column]),
                    stretch = Stretch(ys[row], minOf(xs[sideways], xs[column]), vertical = false),
                )
            }
            val along = row + step
            if (along in 0 until rows && corridors.blockers[column].none { it.spansY(ys[row], ys[along]) }) {
                relax(
                    column,
                    along,
                    nextVertical = true,
                    span = abs(ys[along] - ys[row]),
                    stretch = Stretch(xs[column], minOf(ys[along], ys[row]), vertical = true),
                )
            }
        }
    }
    if (goal < 0) return null

    val corners = ArrayDeque<Position>()
    var step = goal
    while (step >= 0) {
        corners.addFirst(Position(xs[step / 2 / rows], ys[step / 2 % rows]))
        step = cameFrom[step]
    }
    for ((one, two) in corners.zipWithNext()) {
        taken += if (one.x == two.x) {
            Stretch(one.x, minOf(one.y, two.y), vertical = true)
        } else {
            Stretch(one.y, minOf(one.x, two.x), vertical = false)
        }
    }
    return straighten(listOf(start.point) + corners + end.point)
}

/** The nearest band on the anchor's side that the card's own lane reaches without a card in it. */
private fun bandFrom(anchor: Anchor, bands: List<Float>, blockers: List<Box>, ends: Set<String>): Int? {
    val order = if (anchor.below) bands.indices else bands.indices.reversed()
    for (index in order) {
        val band = bands[index]
        if (anchor.below && band < anchor.point.y) continue
        if (!anchor.below && band > anchor.point.y) continue
        val clear = blockers.none { it.id !in ends && it.spansY(anchor.point.y, band) }
        if (clear) return index
    }
    return null
}

/** Drops the points that add no corner. */
internal fun straighten(points: List<Position>): List<Position> {
    val kept = mutableListOf<Position>()
    for (point in points) {
        if (kept.isNotEmpty() && kept.last() == point) continue
        val last = kept.lastOrNull()
        val before = kept.getOrNull(kept.size - 2)
        if (last != null && before != null &&
            ((before.x == last.x && last.x == point.x) || (before.y == last.y && last.y == point.y))
        ) {
            kept.removeAt(kept.lastIndex)
        }
        kept += point
    }
    return kept
}

/** A binary heap of states by cost. The common standard library has no priority queue. */
private class Frontier {
    private val states = mutableListOf<Int>()
    private val costs = mutableListOf<Float>()

    val isEmpty: Boolean get() = states.isEmpty()

    fun push(state: Int, cost: Float) {
        states += state
        costs += cost
        var child = states.lastIndex
        while (child > 0) {
            val parent = (child - 1) / 2
            if (!cheaper(child, parent)) break
            swap(child, parent)
            child = parent
        }
    }

    fun pop(): Int {
        val top = states[0]
        swap(0, states.lastIndex)
        states.removeAt(states.lastIndex)
        costs.removeAt(costs.lastIndex)
        var parent = 0
        while (true) {
            val left = parent * 2 + 1
            var take = parent
            if (left <= states.lastIndex && cheaper(left, take)) take = left
            if (left + 1 <= states.lastIndex && cheaper(left + 1, take)) take = left + 1
            if (take == parent) break
            swap(parent, take)
            parent = take
        }
        return top
    }

    // The state number breaks a tie, so the same graph always gives the same route.
    private fun cheaper(a: Int, b: Int): Boolean =
        costs[a] < costs[b] || (costs[a] == costs[b] && states[a] < states[b])

    private fun swap(a: Int, b: Int) {
        states[a] = states[b].also { states[b] = states[a] }
        costs[a] = costs[b].also { costs[b] = costs[a] }
    }
}
