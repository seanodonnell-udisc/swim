package swim.layout

/** Where the packer put every node: an x center, and the top of the node's shelf. */
internal class PackedTrees(
    val centers: Map<String, Float>,
    val shelfTops: Map<String, Float>,
)

/**
 * Shelf packing: trees go left to right with [treeGap] between their bounding boxes, and wrap
 * to a new shelf once a tree would end past [maxRowWidth]. A lone tree wider than the limit
 * gets a shelf to itself. [bottoms] carries each node's in-tree bottom edge, so a shelf is as
 * tall as its tallest tree.
 */
internal fun packTrees(
    trees: List<Map<String, Float>>,
    widths: Map<String, Float>,
    bottoms: Map<String, Float>,
    treeGap: Float,
    maxRowWidth: Float,
): PackedTrees {
    val centers = LinkedHashMap<String, Float>()
    val shelfTops = LinkedHashMap<String, Float>()
    var cursor = 0f
    var shelfTop = 0f
    var shelfBottom = 0f
    for (tree in trees) {
        if (tree.isEmpty()) continue
        val left = tree.minOf { (id, center) -> center - widths.getValue(id) / 2f }
        val right = tree.maxOf { (id, center) -> center + widths.getValue(id) / 2f }
        val width = right - left
        if (cursor > 0f && cursor + width > maxRowWidth) {
            shelfTop = shelfBottom + treeGap
            cursor = 0f
        }
        val offset = cursor - left
        for ((id, center) in tree) {
            centers[id] = center + offset
            shelfTops[id] = shelfTop
            shelfBottom = maxOf(shelfBottom, shelfTop + bottoms.getValue(id))
        }
        cursor += width + treeGap
    }
    return PackedTrees(centers, shelfTops)
}

/** Top y of each level's row: the tallest node of every earlier level, plus [levelGap]. */
internal fun rowTops(
    nodes: List<LayoutNode>,
    levels: Map<String, Int>,
    levelGap: Float,
): Map<Int, Float> {
    val rowHeights = LinkedHashMap<Int, Float>()
    for (node in nodes) {
        val level = levels.getValue(node.id)
        rowHeights[level] = maxOf(rowHeights[level] ?: 0f, node.height)
    }

    val tops = LinkedHashMap<Int, Float>()
    var top = 0f
    for (level in rowHeights.keys.sorted()) {
        tops[level] = top
        top += rowHeights.getValue(level) + levelGap
    }
    return tops
}
