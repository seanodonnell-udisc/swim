package swim.layout

import kotlin.math.abs

/** Counts the RELATED edges that join each pair of groups, keyed by the two group ids. */
internal fun pairWeightOf(
    related: List<LayoutEdge>,
    groupOf: Map<String, String>,
): (String, String) -> Float {
    val counts = HashMap<Pair<String, String>, Float>()
    for (edge in related) {
        val a = groupOf[edge.from] ?: continue
        val b = groupOf[edge.to] ?: continue
        if (a == b) continue
        val key = if (a < b) a to b else b to a
        counts[key] = (counts[key] ?: 0f) + 1f
    }
    return { a, b -> counts[if (a < b) a to b else b to a] ?: 0f }
}

/**
 * Orders [items] so related groups sit adjacent, weighing that against the input order.
 *
 * The cost of an order is `weight * sum(pairWeight * slots between the pair)` plus one
 * per slot each item moved from its input index, so `weight` is the price of one slot of
 * reordering: below 1 the input order wins, above it related groups pull together.
 *
 * ponytail: index distance stands in for x-distance and the search is adjacent-swap hill
 * climbing, O(n^2) per pass over a sibling group. Measure real widths into the cost and
 * switch to a proper ordering search only if the playground shows this is not enough.
 */
internal fun orderByAffinity(
    items: List<String>,
    weight: Float,
    pairWeight: (String, String) -> Float,
): List<String> {
    val inputIndex = items.withIndex().associate { (index, id) -> id to index }
    val order = items.toMutableList()

    var pass = 0
    var improved = true
    while (improved && pass < items.size) {
        improved = false
        pass++
        for (slot in 0 until order.size - 1) {
            val before = orderCost(order, inputIndex, weight, pairWeight)
            order.swap(slot, slot + 1)
            if (orderCost(order, inputIndex, weight, pairWeight) < before) improved = true
            else order.swap(slot, slot + 1)
        }
    }
    return order
}

private fun orderCost(
    order: List<String>,
    inputIndex: Map<String, Int>,
    weight: Float,
    pairWeight: (String, String) -> Float,
): Float {
    var cost = 0f
    for (slot in order.indices) {
        cost += abs(slot - inputIndex.getValue(order[slot])).toFloat()
        for (other in slot + 1 until order.size) {
            cost += weight * pairWeight(order[slot], order[other]) * (other - slot)
        }
    }
    return cost
}

private fun MutableList<String>.swap(a: Int, b: Int) {
    val held = this[a]
    this[a] = this[b]
    this[b] = held
}
