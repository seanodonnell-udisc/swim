package swim.ui.app

import androidx.compose.ui.geometry.Offset
import swim.core.model.GraphData
import swim.core.model.IssueNode
import swim.core.model.RelationType
import swim.core.session.GraphGrouping
import swim.layout.LayoutEdge
import swim.layout.LayoutEdgeKind
import swim.layout.LayoutNode
import swim.layout.LayoutParams
import swim.layout.LayoutResult
import swim.layout.Position
import swim.layout.PositionSnapshot
import swim.layout.layout
import swim.layout.reuseAndPlace
import swim.ui.graph.EdgeKey
import swim.ui.graph.GraphCanvasDefaults
import swim.ui.graph.blocksEdgeKey

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
)

/**
 * Where one area's own drag offset lives in the position snapshot. A Linear identifier is
 * `TEAM-123`, so it can never start with `@`, and the key cannot collide with an issue.
 */
internal const val GROUP_OFFSET_PREFIX = "@group:"

internal fun groupOffsetKey(group: String): String = GROUP_OFFSET_PREFIX + group

/** The buckets for members that have no team, project, label or milestone. They sort last. */
private val UNGROUPED = setOf("No project", "No label", "No milestone")

/**
 * Areas run left to right by name, with the bucket for members that have none last.
 *
 * ponytail: by name, not by the order Linear keeps its milestones in. `M1`, `M2`, `M3` sort
 * right; a workspace that names them by theme will not. `IssueNode` carries the milestone name
 * and id only, so a true ordering needs a sort key from `swim.core`.
 */
private val GROUP_ORDER = compareBy<Map.Entry<String, *>>({ it.key in UNGROUPED }, { it.key })

/** Room for the group name above the members, and a margin around them. */
internal const val GROUP_LABEL_BAND: Float = 40f
internal const val GROUP_MARGIN: Float = 20f
internal const val GROUP_GAP: Float = 120f

/** The group a node belongs to. A node with no project or label still gets a group. */
internal fun groupKeyOf(node: IssueNode, groupBy: GraphGrouping): String = when (groupBy) {
    GraphGrouping.NONE -> ""
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

/** The identifiers in one area. */
internal fun idsIn(graph: GraphData, groupBy: GraphGrouping, group: String): Set<String> =
    graph.nodes.filterTo(mutableSetOf()) { groupKeyOf(it, groupBy) == group }
        .mapTo(mutableSetOf()) { it.identifier }

/** Translates [ids] by [delta] and leaves every other node where it is. */
internal fun moveGroup(
    positions: Map<String, Position>,
    ids: Set<String>,
    delta: Offset,
): Map<String, Position> = positions.mapValues { (id, position) ->
    if (id in ids) Position(position.x + delta.x, position.y + delta.y) else position
}

/** Cards are a fixed size, so every node is the same box. */
internal fun layoutNodesOf(graph: GraphData): List<LayoutNode> = graph.nodes.map {
    LayoutNode(it.identifier, GraphCanvasDefaults.NodeWidth, GraphCanvasDefaults.NodeHeight)
}

/** Only `blocks` shapes the placement; `related` nudges sibling order; `duplicate` says nothing. */
internal fun layoutEdgesOf(graph: GraphData): List<LayoutEdge> = graph.edges.mapNotNull { edge ->
    when (edge.type) {
        RelationType.BLOCKS -> LayoutEdge(edge.from, edge.to, LayoutEdgeKind.BLOCKS)
        RelationType.RELATED -> LayoutEdge(edge.from, edge.to, LayoutEdgeKind.RELATED)
        RelationType.DUPLICATE -> null
    }
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
): GraphPlacement {
    if (graph.nodes.isEmpty()) return GraphPlacement(snapshot = snapshot)

    val nodes = layoutNodesOf(graph)
    val edges = layoutEdgesOf(graph)
    val fresh = if (groupBy == GraphGrouping.NONE) {
        layout(nodes, edges, params)
    } else {
        layoutGrouped(graph, nodes, edges, groupBy, params, groupOffsetsIn(snapshot, cacheKey))
    }

    val placed = reuseAndPlace(cacheKey, fresh, nodes, forCache(snapshot, cacheKey, groupBy))
    val areaOffsets = snapshot.byKey[cacheKey].orEmpty()
        .filterKeys { it.startsWith(GROUP_OFFSET_PREFIX) }
    return GraphPlacement(
        positions = placed.positions,
        crossLinks = fresh.crossLinks.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
        cycleEdges = fresh.cycleEdges.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
        groups = groupBoxesOf(graph, groupBy, placed.positions),
        // Only an inherit writes a new entry. Rebuild it on the real snapshot so the area
        // offsets, which the cache never saw, are not dropped on the way back out.
        snapshot = if (placed.inherited) {
            PositionSnapshot(snapshot.byKey + (cacheKey to placed.positions + areaOffsets))
        } else {
            snapshot
        },
    )
}

/**
 * What the layout cache is allowed to see.
 *
 * The area offsets are stripped: a reserved key would make an otherwise absent entry look
 * present, and `reuseAndPlace` reads a present entry as "run the overlap pass" instead of
 * "the fresh layout stands as it is".
 *
 * A grouped view is also cut off from the other queries. `reuseAndPlace` inherits the saved
 * layout that shares the most nodes, and the ungrouped layout of the same issues always shares
 * all of them, so switching to a grouping would reuse the ungrouped arrangement and never lay
 * the areas out at all.
 *
 * ponytail: the reverse hole is open. An ungrouped view of a brand new query can still inherit
 * from a grouped key. Closing it needs the grouping to be readable from the cache key, which
 * `swim.core.session.cacheKey` owns.
 */
private fun forCache(
    snapshot: PositionSnapshot,
    cacheKey: String,
    groupBy: GraphGrouping,
): PositionSnapshot = PositionSnapshot(
    snapshot.byKey
        .filterKeys { groupBy == GraphGrouping.NONE || it == cacheKey }
        .mapValues { (_, saved) -> saved.filterKeys { !it.startsWith(GROUP_OFFSET_PREFIX) } }
        // An entry that held only offsets is now empty, and `reuseAndPlace` reads a present but
        // empty entry as "run the overlap pass". It must look absent instead.
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
    val byNode = nodes.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<LayoutNode>>()
    val groupOf = HashMap<String, String>()
    for (node in graph.nodes) {
        val key = groupKeyOf(node, groupBy)
        groupOf[node.identifier] = key
        byNode[node.identifier]?.let { members.getOrPut(key) { mutableListOf() }.add(it) }
    }
    val ordered = members.entries.sortedWith(GROUP_ORDER)

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
    val members = LinkedHashMap<String, MutableList<Position>>()
    for (node in graph.nodes) {
        val position = positions[node.identifier] ?: continue
        members.getOrPut(groupKeyOf(node, groupBy)) { mutableListOf() }.add(position)
    }
    return members.entries.sortedWith(GROUP_ORDER).map { (label, group) ->
        val left = group.minOf { it.x } - GROUP_MARGIN
        val top = group.minOf { it.y } - GROUP_LABEL_BAND
        val right = group.maxOf { it.x } + GraphCanvasDefaults.NodeWidth + GROUP_MARGIN
        val bottom = group.maxOf { it.y } + GraphCanvasDefaults.NodeHeight + GROUP_MARGIN
        GroupBox(label, left, top, right - left, bottom - top)
    }
}
