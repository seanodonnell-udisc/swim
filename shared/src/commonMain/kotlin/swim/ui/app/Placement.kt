package swim.ui.app

import androidx.compose.ui.geometry.Offset
import swim.core.model.GraphData
import swim.core.model.IssueNode
import swim.core.session.GraphGrouping
import swim.core.session.EDITED_KEY
import swim.core.session.groupingOf
import swim.layout.AreaBox
import swim.layout.LayoutEdge
import swim.layout.LayoutNode
import swim.layout.LayoutParams
import swim.layout.LayoutResult
import swim.layout.Position
import swim.layout.PositionSnapshot
import swim.layout.layout
import swim.layout.resolveAreaOverlaps
import swim.layout.reuseAndPlace
import swim.ui.graph.EdgeKey
import swim.ui.graph.GraphCanvasDefaults
import swim.ui.graph.blocksEdgeKey
import swim.ui.graph.layoutEdgesOf
import swim.ui.graph.layoutNodesOf
import swim.ui.graph.slotOf
import swim.ui.graph.stackIndex
import swim.ui.graph.stackKeyOf
import swim.ui.graph.stackSpread
import swim.ui.graph.visibleStacks

/** The outline drawn behind one group of nodes. Canvas units are dp, as the canvas expects. */
data class GroupBox(
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Everything the canvas needs to draw one placed graph, plus the snapshot to persist. */
data class GraphPlacement(
    val positions: Map<String, Position> = emptyMap(),
    val crossLinks: Set<EdgeKey> = emptySet(),
    val cycleEdges: Set<EdgeKey> = emptySet(),
    val groups: List<GroupBox> = emptyList(),
    val snapshot: PositionSnapshot = PositionSnapshot(),
    /**
     * Whether this arrangement has been moved by hand. Collision-avoiding connector routing stops
     * for good once it has: see [swim.core.session.EDITED_KEY].
     */
    val handEdited: Boolean = false,
)

/**
 * Where one area's own drag offset lives in the position snapshot. A Linear identifier is
 * `TEAM-123`, so it can never start with `@`, and the key cannot collide with an issue.
 * [swim.ui.graph.STACK_PREFIX] reserves `@stack:` the same way for a pile of stacked cards; a
 * pile is a real layout node, so its key stays in everything the layout cache reads.
 */
internal const val GROUP_OFFSET_PREFIX = "@group:"

internal fun groupOffsetKey(group: String): String = GROUP_OFFSET_PREFIX + group

/**
 * Whether this entry is bookkeeping rather than a card. An area's drag offset and the hand-edited
 * mark both ride in a key's position map; neither is a node, so neither may reach the layout or
 * the cache. A pile's `@stack:` key is a real layout node and is not reserved.
 */
internal fun isReserved(key: String): Boolean =
    key.startsWith(GROUP_OFFSET_PREFIX) || key == EDITED_KEY

/** The buckets for members that have no team, project, label or milestone. They sort last. */
private val UNGROUPED = setOf("No project", "No label", "No milestone")

/**
 * Areas run left to right by order, with the bucket for members that have none last. A milestone
 * area orders by the tracker's own `sortOrder`, ties and every other grouping by name.
 */
private fun groupOrder(graph: GraphData, groupBy: GraphGrouping): Comparator<Map.Entry<String, *>> {
    if (groupBy != GraphGrouping.MILESTONE) return compareBy({ it.key in UNGROUPED }, { it.key })
    val sortOrderOf = graph.nodes.mapNotNull { it.milestone?.let { name -> name to it.milestoneSortOrder } }.toMap()
    return compareBy({ it.key in UNGROUPED }, { sortOrderOf[it.key] ?: Float.MAX_VALUE }, { it.key })
}

/** Room for the group name above the members, and a margin around them. */
internal const val GROUP_LABEL_BAND: Float = 40f
internal const val GROUP_MARGIN: Float = 20f
internal const val GROUP_GAP: Float = 120f

/** The group a node belongs to. A node with no project or label still gets a group. */
internal fun groupKeyOf(node: IssueNode, groupBy: GraphGrouping): String = when (groupBy) {
    // AUTO never reaches here: `resolveGrouping` runs before anything asks for a group key.
    GraphGrouping.NONE, GraphGrouping.AUTO -> ""
    GraphGrouping.TEAM -> node.team
    GraphGrouping.PROJECT -> node.project ?: "No project"
    GraphGrouping.LABEL -> node.labels.firstOrNull() ?: "No label"
    GraphGrouping.MILESTONE -> node.milestone ?: "No milestone"
}

/**
 * The edges that cross from one area into another. Milestones are usually born from an unblock
 * in the milestone before, so the ordering already implies those links and drawing them buries
 * the graph. The view toolbar can turn them back on.
 */
internal fun withoutCrossGroupEdges(graph: GraphData, groupBy: GraphGrouping): GraphData {
    if (groupBy == GraphGrouping.NONE) return graph
    val group = graph.nodes.associate { it.identifier to groupKeyOf(it, groupBy) }
    return graph.copy(
        edges = graph.edges.filter { group[it.from] != null && group[it.from] == group[it.to] },
    )
}

/**
 * The area every layout slot belongs to. A pile is one slot, so it belongs to exactly one area:
 * the one holding its front member, which is the lowest identifier and the member [stackKeyOf]
 * already names the pile after. A pile whose members span areas still renders, in that one.
 */
internal fun slotGroups(graph: GraphData, groupBy: GraphGrouping): Map<String, String> {
    val index = stackIndex(visibleStacks(graph))
    val out = LinkedHashMap<String, String>()
    for (node in graph.nodes.sortedBy { it.identifier }) {
        out.getOrPut(slotOf(node.identifier, index)) { groupKeyOf(node, groupBy) }
    }
    return out
}

/** The layout slots in one area: one per card, and one per pile of stacked cards. */
internal fun idsIn(graph: GraphData, groupBy: GraphGrouping, group: String): Set<String> =
    slotGroups(graph, groupBy).filterValues { it == group }.keys

/** Translates [ids] by [delta] and leaves every other node where it is. */
internal fun moveGroup(
    positions: Map<String, Position>,
    ids: Set<String>,
    delta: Offset,
): Map<String, Position> = positions.mapValues { (id, position) ->
    if (id in ids) Position(position.x + delta.x, position.y + delta.y) else position
}

/**
 * Places every node: one forest when the graph is not grouped, one forest per group packed side
 * by side when it is, then the saved hand-placed positions on top of that.
 */
fun placeGraph(
    graph: GraphData,
    groupBy: GraphGrouping,
    cacheKey: String,
    snapshot: PositionSnapshot,
    params: LayoutParams = LayoutParams(),
    /**
     * Re-layout. Ignores this key's saved layout, every donor key, and the area offsets, then
     * saves what `layout` produced. Deleting the key alone is not enough: the closest sibling
     * key shares every node, so inheritance hands the discarded arrangement straight back.
     */
    relayout: Boolean = false,
): GraphPlacement {
    if (graph.nodes.isEmpty()) return GraphPlacement(snapshot = snapshot)

    val nodes = layoutNodesOf(graph)
    val edges = layoutEdgesOf(graph)
    val fresh = if (groupBy == GraphGrouping.NONE) {
        layout(nodes, edges, params)
    } else {
        val offsets = if (relayout) emptyMap() else groupOffsetsIn(snapshot, cacheKey)
        layoutGrouped(graph, nodes, edges, groupBy, params, offsets)
    }

    if (relayout) {
        // The saved arrangement goes, and the hand-edited mark goes with it: a re-layout is the
        // user asking the machine to arrange the graph again, so the routes come back too.
        return GraphPlacement(
            positions = fresh.positions,
            crossLinks = fresh.crossLinks.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
            cycleEdges = fresh.cycleEdges.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
            groups = groupBoxesOf(graph, groupBy, fresh.positions),
            snapshot = PositionSnapshot(snapshot.byKey + (cacheKey to fresh.positions)),
            handEdited = false,
        )
    }

    val placed = reuseAndPlace(cacheKey, fresh, nodes, forCache(snapshot, cacheKey))
    val saved = snapshot.byKey[cacheKey].orEmpty()

    // Whatever the saved layout says, two areas must not cover each other after this pass.
    val boxes = groupBoxesOf(graph, groupBy, placed.positions)
    val deltas = resolveAreaOverlaps(
        boxes.map { AreaBox(it.label, it.x, it.y, it.width, it.height) },
        params.treeGap,
        params.maxRowWidth,
    )
    val positions = shiftAreas(graph, groupBy, placed.positions, deltas)
    val groups = if (deltas.isEmpty()) boxes else groupBoxesOf(graph, groupBy, positions)

    val reserved = nextReserved(graph, groupBy, snapshot, cacheKey, deltas)
    // A card the user placed by hand moves with its area, so the saved copy has to move too.
    // A saved card the graph no longer holds keeps the coordinates it had.
    val movedSaved = saved.filterKeys { !isReserved(it) }
        .mapValues { (id, position) -> positions[id] ?: position }
    return GraphPlacement(
        positions = positions,
        crossLinks = fresh.crossLinks.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
        cycleEdges = fresh.cycleEdges.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
        groups = groups,
        // An inherit writes the whole arrangement. A separated area or a pruned offset writes
        // only what was saved already, so an auto-placed card is not frozen where it landed.
        // Either way the entry is rebuilt on the real snapshot, so the reserved entries, which
        // the cache never saw, are not dropped on the way back out.
        snapshot = when {
            placed.inherited ->
                PositionSnapshot(snapshot.byKey + (cacheKey to positions + reserved))
            reserved != saved.filterKeys(::isReserved) || deltas.isNotEmpty() ->
                PositionSnapshot(snapshot.byKey + (cacheKey to movedSaved + reserved))
            else -> snapshot
        },
        // Per key, and never inherited: a key that borrowed another's coordinates has not been
        // arranged by hand under its own arrangement, so it is still the machine's to route.
        // Separating the areas is the machine placing them, so it never sets the mark either.
        handEdited = EDITED_KEY in saved,
    )
}

/** Translates every member of each moved area, so the arrangement inside the area is kept. */
private fun shiftAreas(
    graph: GraphData,
    groupBy: GraphGrouping,
    positions: Map<String, Position>,
    deltas: Map<String, Position>,
): Map<String, Position> {
    if (deltas.isEmpty()) return positions
    val groupOf = slotGroups(graph, groupBy)
    return positions.mapValues { (slot, position) ->
        val delta = deltas[groupOf[slot]] ?: return@mapValues position
        Position(position.x + delta.x, position.y + delta.y)
    }
}

/**
 * The reserved entries to save: each area's drag offset carried by the area's own delta, plus
 * the hand-edited mark when it was already there. An offset for a group the graph no longer
 * holds is dropped, so a milestone that has gone cannot bring a ghost area back on the next load.
 */
private fun nextReserved(
    graph: GraphData,
    groupBy: GraphGrouping,
    snapshot: PositionSnapshot,
    cacheKey: String,
    deltas: Map<String, Position>,
): Map<String, Position> {
    val saved = snapshot.byKey[cacheKey].orEmpty()
    val edited = saved.filterKeys { it == EDITED_KEY }
    if (groupBy == GraphGrouping.NONE) return saved.filterKeys(::isReserved)

    val live = slotGroups(graph, groupBy).values.toSet()
    val offsets = groupOffsetsIn(snapshot, cacheKey)
    val next = (offsets.keys + deltas.keys).filter { it in live }.associate { group ->
        val base = offsets[group] ?: Position(0f, 0f)
        val delta = deltas[group] ?: Position(0f, 0f)
        groupOffsetKey(group) to Position(base.x + delta.x, base.y + delta.y)
    }
    return next + edited
}

/**
 * What the layout cache is allowed to see.
 *
 * The reserved entries are stripped: one would make an otherwise absent entry look present, and
 * `reuseAndPlace` reads a present entry as "run the overlap pass" instead of "the fresh layout
 * stands as it is".
 *
 * Donor keys are also cut down to the ones with the same grouping. `reuseAndPlace` inherits the
 * saved layout that shares the most nodes, and every grouping of one query shares all of them,
 * so a grouped view would reuse the flat arrangement and never lay its areas out, and a flat
 * view would inherit coordinates packed for areas. Same-grouping donation is the intended
 * feature and still runs: the team variants of one flat view keep reusing each other.
 *
 * A key that did not come from `cacheKey` has no grouping. Two of those match each other, so a
 * test may use a plain string for both sides.
 */
private fun forCache(
    snapshot: PositionSnapshot,
    cacheKey: String,
): PositionSnapshot = PositionSnapshot(
    snapshot.byKey
        .filterKeys { it == cacheKey || groupingOf(it) == groupingOf(cacheKey) }
        .mapValues { (_, saved) -> saved.filterKeys { !isReserved(it) } }
        // An entry that held only reserved keys is now empty, and `reuseAndPlace` reads a present
        // but empty entry as "run the overlap pass". It must look absent instead.
        .filterValues { it.isNotEmpty() },
)

/** Lays out each group on its own, then packs the group boxes left to right. */
private fun layoutGrouped(
    graph: GraphData,
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    groupBy: GraphGrouping,
    params: LayoutParams,
    offsets: Map<String, Position>,
): LayoutResult {
    // Keyed by slot, like `nodes` and `edges`. Partitioning the original identifiers instead
    // dropped every pile: a stacked member's identifier names no layout node, so the pile
    // landed in no area and was never placed.
    val groupOf = slotGroups(graph, groupBy)
    val members = LinkedHashMap<String, MutableList<LayoutNode>>()
    for (slot in nodes) {
        members.getOrPut(groupOf[slot.id] ?: continue) { mutableListOf() }.add(slot)
    }
    val ordered = members.entries.sortedWith(groupOrder(graph, groupBy))

    val positions = LinkedHashMap<String, Position>()
    val crossLinks = mutableListOf<LayoutEdge>()
    val cycleEdges = mutableListOf<LayoutEdge>()
    var cursor = 0f

    for ((key, groupNodes) in ordered) {
        // Only edges wholly inside the group shape it; the rest still draw as canvas edges.
        val inside = edges.filter { groupOf[it.from] == key && groupOf[it.to] == key }
        val result = layout(groupNodes, inside, params)
        crossLinks += result.crossLinks
        cycleEdges += result.cycleEdges

        val left = result.positions.values.minOf { it.x }
        val top = result.positions.values.minOf { it.y }
        val right = groupNodes.maxOf { result.positions.getValue(it.id).x + it.width }
        // The area's own drag offset moves the whole bucket, so a member placed for the first
        // time after the drag lands inside the area the user moved, not where it used to be.
        val drag = offsets[key] ?: Position(0f, 0f)
        val shiftX = cursor + GROUP_MARGIN - left + drag.x
        val shiftY = GROUP_LABEL_BAND - top + drag.y
        for ((id, position) in result.positions) {
            positions[id] = Position(position.x + shiftX, position.y + shiftY)
        }
        cursor += (right - left) + GROUP_MARGIN * 2f + GROUP_GAP
    }

    return LayoutResult(positions, crossLinks, cycleEdges)
}

/** The area drag offsets saved for this query, by group key. */
internal fun groupOffsetsIn(snapshot: PositionSnapshot, cacheKey: String): Map<String, Position> =
    snapshot.byKey[cacheKey].orEmpty()
        .filterKeys { it.startsWith(GROUP_OFFSET_PREFIX) }
        .mapKeys { it.key.removePrefix(GROUP_OFFSET_PREFIX) }

/** The outlines, read back from the final positions so a drag moves the box with the cards. */
internal fun groupBoxesOf(
    graph: GraphData,
    groupBy: GraphGrouping,
    positions: Map<String, Position>,
): List<GroupBox> {
    if (groupBy == GraphGrouping.NONE) return emptyList()
    val spreads = visibleStacks(graph).associate { stackKeyOf(it) to stackSpread(it.size) }
    // Position and how far past it the slot reaches: a pile is wider and taller than one card.
    val members = LinkedHashMap<String, MutableList<Pair<Position, Float>>>()
    for ((slot, key) in slotGroups(graph, groupBy)) {
        val position = positions[slot] ?: continue
        members.getOrPut(key) { mutableListOf() }.add(position to (spreads[slot] ?: 0f))
    }
    return members.entries.sortedWith(groupOrder(graph, groupBy)).map { (label, group) ->
        val left = group.minOf { it.first.x } - GROUP_MARGIN
        val top = group.minOf { it.first.y } - GROUP_LABEL_BAND
        val right = group.maxOf { it.first.x + it.second } +
            GraphCanvasDefaults.NodeWidth + GROUP_MARGIN
        val bottom = group.maxOf { it.first.y + it.second } +
            GraphCanvasDefaults.NodeHeight + GROUP_MARGIN
        GroupBox(label, left, top, right - left, bottom - top)
    }
}
