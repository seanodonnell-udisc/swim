package swim.ui.graph

import swim.core.model.GraphData
import swim.core.model.RelationType
import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.LayoutNode
import swim.layout.Position

/**
 * Where one pile of stacked cards sits in the position snapshot. A Linear identifier is
 * `TEAM-123`, so it can never start with `@`, and a pile key cannot collide with an issue. The
 * area drag offsets reserve `@group:` the same way.
 */
const val STACK_PREFIX = "@stack:"

/** How far one card in a pile sits from the card behind it, down and to the right. */
const val STACK_OFFSET = 14f

/** The pile key for a group of stacked issues. The lowest identifier names it, so it is stable. */
fun stackKeyOf(members: Collection<String>): String = STACK_PREFIX + members.min()

/**
 * The stacks this graph still draws. Hiding the duplicates can leave a group with one member,
 * and one card is not a pile.
 */
fun visibleStacks(graph: GraphData): List<Set<String>> {
    if (graph.stacks.isEmpty()) return emptyList()
    val present = graph.nodes.mapTo(mutableSetOf()) { it.identifier }
    return graph.stacks.map { it intersect present }.filter { it.size > 1 }
}

/** Every stacked issue mapped to its whole pile. An issue in no stack is absent. */
fun stackIndex(stacks: List<Set<String>>): Map<String, Set<String>> = buildMap {
    for (members in stacks) {
        for (member in members) put(member, members)
    }
}

/** The layout slot a node occupies: its own card, or the pile it belongs to. */
fun slotOf(id: String, index: Map<String, Set<String>>): String =
    index[id]?.let(::stackKeyOf) ?: id

/** [ids] plus the pile mates of every stacked member. A pile selects and drags as one unit. */
fun withStackMates(ids: Set<String>, index: Map<String, Set<String>>): Set<String> =
    if (index.isEmpty()) ids else ids.flatMapTo(mutableSetOf()) { index[it] ?: setOf(it) }

/** How much wider and taller a pile of [size] cards is than one card. */
fun stackSpread(size: Int): Float = STACK_OFFSET * (size - 1)

/**
 * A pile front to back: the card the user last brought forward first, then the rest by
 * identifier. The front card is whole and the ones behind it peek out at the top left.
 */
fun pileOrder(members: Set<String>, front: String?): List<String> {
    val sorted = members.sorted()
    return if (front == null || front !in members) sorted else listOf(front) + (sorted - front)
}

/** Where card [index] of a pile of [size] sits, on both axes, from the pile's own position. */
fun pileOffset(index: Int, size: Int): Float = STACK_OFFSET * (size - 1 - index)

/**
 * Where one card sits, read from the positions the layout keeps per slot: its own position, or
 * its place in the pile it belongs to. Null when nothing has placed its slot yet.
 */
fun cardPosition(
    id: String,
    positions: Map<String, Position>,
    index: Map<String, Set<String>>,
    front: Map<String, String>,
): Position? {
    val origin = positions[slotOf(id, index)] ?: return null
    val members = index[id] ?: return origin
    val order = pileOrder(members, front[stackKeyOf(members)])
    val step = pileOffset(order.indexOf(id), members.size)
    return Position(origin.x + step, origin.y + step)
}

/**
 * Cards are a fixed size, so every node is the same box. A pile of stacked cards is one box
 * instead of one per member: it takes a single slot, grown by the diagonal offset it draws with.
 */
fun layoutNodesOf(graph: GraphData): List<LayoutNode> {
    val index = stackIndex(visibleStacks(graph))
    val taken = mutableSetOf<String>()
    return graph.nodes.mapNotNull { node ->
        val slot = slotOf(node.identifier, index)
        if (!taken.add(slot)) return@mapNotNull null
        val spread = index[node.identifier]?.let { stackSpread(it.size) } ?: 0f
        LayoutNode(
            slot,
            GraphCanvasDefaults.NodeWidth + spread,
            GraphCanvasDefaults.NodeHeight + spread,
        )
    }
}

/**
 * Only `blocks` shapes the placement; `related` nudges sibling order; `duplicate` says nothing.
 * Both ends run through the pile they belong to, so an edge onto one member pulls the whole pile.
 * An edge between two members of one pile has nowhere to go and is dropped.
 *
 * The router wants every line that is drawn, duplicates included, so that a dashed purple edge is
 * kept off the cards as well. [duplicatesAsRelated] is for that caller and for no other: a
 * duplicate that reached the placement would start nudging sibling order, which it must not.
 */
fun layoutEdgesOf(graph: GraphData, duplicatesAsRelated: Boolean = false): List<LayoutEdge> {
    val index = stackIndex(visibleStacks(graph))
    val seen = mutableSetOf<LayoutEdge>()
    return graph.edges.mapNotNull { edge ->
        val kind = when (edge.type) {
            RelationType.BLOCKS -> LayoutEdgeKind.BLOCKS
            RelationType.RELATED -> LayoutEdgeKind.RELATED
            RelationType.DUPLICATE ->
                if (duplicatesAsRelated) LayoutEdgeKind.RELATED else return@mapNotNull null
        }
        val from = slotOf(edge.from, index)
        val to = slotOf(edge.to, index)
        if (from == to) return@mapNotNull null
        LayoutEdge(from, to, kind).takeIf(seen::add)
    }
}

/**
 * [ids] in draw order: the rear cards of a pile come before its front card, so the front card
 * lands on top of them. A pile takes the place of its first member.
 */
fun drawOrder(
    ids: List<String>,
    stacks: List<Set<String>>,
    front: Map<String, String>,
): List<String> {
    if (stacks.isEmpty()) return ids
    val index = stackIndex(stacks)
    val done = mutableSetOf<String>()
    val out = mutableListOf<String>()
    for (id in ids) {
        val members = index[id]
        if (members == null) {
            out += id
            continue
        }
        val key = stackKeyOf(members)
        if (!done.add(key)) continue
        out += pileOrder(members, front[key]).reversed()
    }
    return out
}
