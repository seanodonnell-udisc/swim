package swim.layout

/** A node to place, sized as the surface layer will render it. */
data class LayoutNode(val id: String, val width: Float, val height: Float)

/** The relation an edge carries. Only [BLOCKS] edges shape the placement. */
enum class LayoutEdgeKind { BLOCKS, RELATED }

/** A relation between two nodes. For [LayoutEdgeKind.BLOCKS], [from] blocks [to]. */
data class LayoutEdge(val from: String, val to: String, val kind: LayoutEdgeKind)

/** The order the children of a node take before they are placed. */
enum class ChildOrder {
    /** The order the nodes arrived in. */
    FETCH,

    /**
     * Shortest subtree first. A tidy tree spreads the small subtrees that sit between two tall
     * ones evenly across the space between them, which leaves a small branch alone in a hole.
     * Shortest first moves those branches to the near side, where no hole opens.
     */
    SHORTEST_FIRST,
}

/** Spacing knobs, owned by the surface layer so each form factor can set its own. */
data class LayoutParams(
    val levelGap: Float = 80f,
    val siblingGap: Float = 40f,
    val treeGap: Float = 120f,
    val relatedAffinityWeight: Float = 0f,
    /** Trees pack left to right and wrap to a new shelf past this width. */
    val maxRowWidth: Float = 2600f,
    /** Sibling order. [relatedAffinityWeight] ordering runs after this one and replaces it. */
    val childOrder: ChildOrder = ChildOrder.SHORTEST_FIRST,
    /** How far apart two routed edges run when they share a corridor. See [routeEdges]. */
    val laneWidth: Float = 24f,
)

/** The top-left corner of a node. */
data class Position(val x: Float, val y: Float)

/** Node positions, plus the BLOCKS edges the placement could not use. */
data class LayoutResult(
    val positions: Map<String, Position>,
    val crossLinks: List<LayoutEdge>,
    val cycleEdges: List<LayoutEdge>,
    /** Corner points for the edges that cannot run straight. See [routeEdges] for the contract. */
    val routes: Map<LayoutEdge, List<Position>> = emptyMap(),
)

/**
 * Places [nodes] as tidy blocker-trees: layered by blocker depth, packed side by side, and
 * routes the edges that cannot then run straight.
 */
fun layout(
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    params: LayoutParams = LayoutParams(),
): LayoutResult {
    val placed = place(nodes, edges, params)
    return placed.copy(routes = routeEdges(nodes, placed.positions, edges, params))
}

/** The placement on its own, for the callers that route nothing. */
internal fun place(
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    params: LayoutParams,
): LayoutResult {
    val widths = nodes.associate { it.id to it.width }
    val blocks = edges.filter {
        it.kind == LayoutEdgeKind.BLOCKS && it.from in widths && it.to in widths
    }
    val related = edges.filter {
        it.kind == LayoutEdgeKind.RELATED && it.from in widths && it.to in widths
    }

    val backEdges = backEdgeIndices(nodes, blocks)
    val cycleEdges = blocks.filterIndexed { i, _ -> i in backEdges }
    val acyclic = blocks.filterIndexed { i, _ -> i !in backEdges }

    val levels = assignLevels(nodes, acyclic)
    val forest = buildForest(nodes, acyclic, levels)

    val heights = subtreeHeights(forest.children, levels)
    fun ordered(ids: List<String>) =
        if (params.childOrder == ChildOrder.FETCH) ids else ids.sortedBy { heights.getValue(it) }

    val useAffinity = params.relatedAffinityWeight > 0f && related.isNotEmpty()
    val children = forest.children.mapValues { (_, kids) ->
        val sorted = ordered(kids)
        if (!useAffinity || sorted.size < 2) sorted else orderByAffinity(
            items = sorted,
            weight = params.relatedAffinityWeight,
            pairWeight = pairWeightOf(related, membership(sorted, forest.children)),
        )
    }
    val sortedRoots = ordered(forest.roots)
    val roots = if (!useAffinity || sortedRoots.size < 2) sortedRoots else orderByAffinity(
        items = sortedRoots,
        weight = params.relatedAffinityWeight,
        pairWeight = pairWeightOf(related, membership(sortedRoots, children)),
    )

    val trees = roots.map { positionTree(it, children, widths, params.siblingGap) }
    val tops = rowTops(nodes, levels, params.levelGap)
    val bottoms = nodes.associate { it.id to tops.getValue(levels.getValue(it.id)) + it.height }
    val packed = packTrees(trees, widths, bottoms, params.treeGap, params.maxRowWidth)

    val positions = nodes.associate { node ->
        val center = packed.centers.getValue(node.id)
        val shelf = packed.shelfTops.getValue(node.id)
        node.id to Position(center - node.width / 2f, shelf + tops.getValue(levels.getValue(node.id)))
    }

    return LayoutResult(
        positions = positions,
        crossLinks = forest.crossLinks + cycleEdges,
        cycleEdges = cycleEdges,
    )
}
