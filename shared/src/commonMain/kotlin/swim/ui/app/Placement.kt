package swim.ui.app

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
        layoutGrouped(graph, nodes, edges, groupBy, params)
    }

    val placed = reuseAndPlace(cacheKey, fresh, nodes, snapshot)
    return GraphPlacement(
        positions = placed.positions,
        crossLinks = fresh.crossLinks.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
        cycleEdges = fresh.cycleEdges.mapTo(mutableSetOf()) { blocksEdgeKey(it.from, it.to) },
        groups = groupBoxesOf(graph, groupBy, placed.positions),
        snapshot = placed.updatedSnapshot,
    )
}

/** Lays out each group on its own, then packs the group boxes left to right. */
private fun layoutGrouped(
    graph: GraphData,
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    groupBy: GraphGrouping,
    params: LayoutParams,
): LayoutResult {
    val byNode = nodes.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<LayoutNode>>()
    val groupOf = HashMap<String, String>()
    for (node in graph.nodes) {
        val key = groupKeyOf(node, groupBy)
        groupOf[node.identifier] = key
        byNode[node.identifier]?.let { members.getOrPut(key) { mutableListOf() }.add(it) }
    }

    val positions = LinkedHashMap<String, Position>()
    val crossLinks = mutableListOf<LayoutEdge>()
    val cycleEdges = mutableListOf<LayoutEdge>()
    var cursor = 0f

    for ((key, groupNodes) in members) {
        // Only edges wholly inside the group shape it; the rest still draw as canvas edges.
        val inside = edges.filter { groupOf[it.from] == key && groupOf[it.to] == key }
        val result = layout(groupNodes, inside, params)
        crossLinks += result.crossLinks
        cycleEdges += result.cycleEdges

        val left = result.positions.values.minOf { it.x }
        val top = result.positions.values.minOf { it.y }
        val right = groupNodes.maxOf { result.positions.getValue(it.id).x + it.width }
        val shiftX = cursor + GROUP_MARGIN - left
        val shiftY = GROUP_LABEL_BAND - top
        for ((id, position) in result.positions) {
            positions[id] = Position(position.x + shiftX, position.y + shiftY)
        }
        cursor += (right - left) + GROUP_MARGIN * 2f + GROUP_GAP
    }

    return LayoutResult(positions, crossLinks, cycleEdges)
}

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
    return members.map { (label, group) ->
        val left = group.minOf { it.x } - GROUP_MARGIN
        val top = group.minOf { it.y } - GROUP_LABEL_BAND
        val right = group.maxOf { it.x } + GraphCanvasDefaults.NodeWidth + GROUP_MARGIN
        val bottom = group.maxOf { it.y } + GraphCanvasDefaults.NodeHeight + GROUP_MARGIN
        GroupBox(label, left, top, right - left, bottom - top)
    }
}
