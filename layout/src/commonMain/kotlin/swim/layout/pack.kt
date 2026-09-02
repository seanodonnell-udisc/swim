package swim.layout

/** Shifts each tree right of the one before it, [treeGap] between their bounding boxes. */
internal fun packTrees(
    trees: List<Map<String, Float>>,
    widths: Map<String, Float>,
    treeGap: Float,
): Map<String, Float> {
    val packed = LinkedHashMap<String, Float>()
    var cursor = 0f
    for (tree in trees) {
        if (tree.isEmpty()) continue
        val left = tree.minOf { (id, center) -> center - widths.getValue(id) / 2f }
        val right = tree.maxOf { (id, center) -> center + widths.getValue(id) / 2f }
        val offset = cursor - left
        for ((id, center) in tree) packed[id] = center + offset
        cursor += (right - left) + treeGap
    }
    return packed
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
