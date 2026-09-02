package swim.layout

/** A node to place, sized as the surface layer will render it. */
data class LayoutNode(val id: String, val width: Float, val height: Float)

/** The relation an edge carries. Only [BLOCKS] edges shape the placement. */
enum class LayoutEdgeKind { BLOCKS, RELATED }

/** A relation between two nodes. For [LayoutEdgeKind.BLOCKS], [from] blocks [to]. */
data class LayoutEdge(val from: String, val to: String, val kind: LayoutEdgeKind)

/** Spacing knobs, owned by the surface layer so each form factor can set its own. */
data class LayoutParams(
    val levelGap: Float = 80f,
    val siblingGap: Float = 40f,
    val treeGap: Float = 120f,
    val relatedAffinityWeight: Float = 0f,
)

/** The top-left corner of a node. */
data class Position(val x: Float, val y: Float)

/** Node positions, plus the BLOCKS edges the placement could not use. */
data class LayoutResult(
    val positions: Map<String, Position>,
    val crossLinks: List<LayoutEdge>,
    val cycleEdges: List<LayoutEdge>,
)

/** Places [nodes] as tidy blocker-trees: layered by blocker depth, packed side by side. */
fun layout(
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    params: LayoutParams = LayoutParams(),
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

    val useAffinity = params.relatedAffinityWeight > 0f && related.isNotEmpty()
    val children = if (!useAffinity) forest.children else forest.children.mapValues { (_, kids) ->
        if (kids.size < 2) kids else orderByAffinity(
            items = kids,
            weight = params.relatedAffinityWeight,
            pairWeight = pairWeightOf(related, membership(kids, forest.children)),
        )
    }
    val roots = if (!useAffinity || forest.roots.size < 2) forest.roots else orderByAffinity(
        items = forest.roots,
        weight = params.relatedAffinityWeight,
        pairWeight = pairWeightOf(related, membership(forest.roots, children)),
    )

    val trees = roots.map { positionTree(it, children, widths, params.siblingGap) }
    val centers = packTrees(trees, widths, params.treeGap)
    val tops = rowTops(nodes, levels, params.levelGap)

    return LayoutResult(
        positions = nodes.associate { node ->
            val center = centers.getValue(node.id)
            node.id to Position(center - node.width / 2f, tops.getValue(levels.getValue(node.id)))
        },
        crossLinks = forest.crossLinks + cycleEdges,
        cycleEdges = cycleEdges,
    )
}
