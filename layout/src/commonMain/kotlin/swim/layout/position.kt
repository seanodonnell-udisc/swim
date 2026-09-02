package swim.layout

private class Tidy(val id: String, val width: Float, val parent: Tidy?, val number: Int) {
    val children = mutableListOf<Tidy>()
    var prelim = 0f
    var mod = 0f
    var shift = 0f
    var change = 0f
    var thread: Tidy? = null
    var ancestor: Tidy = this
}

/** Buchheim-Junger-Leipert tidy tree: centre x per node, with subtree contours kept apart. */
internal fun positionTree(
    root: String,
    children: Map<String, List<String>>,
    widths: Map<String, Float>,
    siblingGap: Float,
): Map<String, Float> {
    val tree = buildTidy(root, children, widths)
    firstWalk(tree, siblingGap)
    val centers = LinkedHashMap<String, Float>()
    secondWalk(tree, -tree.prelim, centers)
    return centers
}

private fun buildTidy(root: String, children: Map<String, List<String>>, widths: Map<String, Float>): Tidy {
    val tree = Tidy(root, widths.getValue(root), parent = null, number = 1)
    val pending = ArrayDeque(listOf(tree))
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        for ((index, child) in children[node.id].orEmpty().withIndex()) {
            val built = Tidy(child, widths.getValue(child), parent = node, number = index + 1)
            node.children.add(built)
            pending.addLast(built)
        }
    }
    return tree
}

private fun nextLeft(node: Tidy): Tidy? = node.children.firstOrNull() ?: node.thread

private fun nextRight(node: Tidy): Tidy? = node.children.lastOrNull() ?: node.thread

private fun leftSibling(node: Tidy): Tidy? = node.parent?.children?.getOrNull(node.number - 2)

private fun distance(left: Tidy, right: Tidy, siblingGap: Float): Float =
    (left.width + right.width) / 2f + siblingGap

private fun ancestor(candidate: Tidy, node: Tidy, defaultAncestor: Tidy): Tidy =
    if (candidate.ancestor.parent === node.parent) candidate.ancestor else defaultAncestor

// ponytail: recursion depth is the tree depth, as in the paper; an explicit stack
// is the upgrade if blocker chains ever outgrow the JVM stack.
private fun firstWalk(node: Tidy, siblingGap: Float) {
    val left = leftSibling(node)
    if (node.children.isEmpty()) {
        node.prelim = if (left == null) 0f else left.prelim + distance(left, node, siblingGap)
        return
    }

    var defaultAncestor = node.children.first()
    for (child in node.children) {
        firstWalk(child, siblingGap)
        defaultAncestor = apportion(child, defaultAncestor, siblingGap)
    }
    executeShifts(node)

    val midpoint = (node.children.first().prelim + node.children.last().prelim) / 2f
    if (left == null) {
        node.prelim = midpoint
    } else {
        node.prelim = left.prelim + distance(left, node, siblingGap)
        node.mod = node.prelim - midpoint
    }
}

private fun apportion(node: Tidy, defaultAncestor: Tidy, siblingGap: Float): Tidy {
    val left = leftSibling(node) ?: return defaultAncestor

    var insideRight: Tidy = node
    var outsideRight: Tidy = node
    var insideLeft: Tidy = left
    var outsideLeft: Tidy = node.parent!!.children.first()
    var shiftInsideRight = node.mod
    var shiftOutsideRight = node.mod
    var shiftInsideLeft = insideLeft.mod
    var shiftOutsideLeft = outsideLeft.mod

    while (nextRight(insideLeft) != null && nextLeft(insideRight) != null) {
        insideLeft = nextRight(insideLeft)!!
        insideRight = nextLeft(insideRight)!!
        outsideLeft = nextLeft(outsideLeft)!!
        outsideRight = nextRight(outsideRight)!!
        outsideRight.ancestor = node

        val overlap = (insideLeft.prelim + shiftInsideLeft) - (insideRight.prelim + shiftInsideRight) +
            distance(insideLeft, insideRight, siblingGap)
        if (overlap > 0f) {
            moveSubtree(ancestor(insideLeft, node, defaultAncestor), node, overlap)
            shiftInsideRight += overlap
            shiftOutsideRight += overlap
        }
        shiftInsideLeft += insideLeft.mod
        shiftInsideRight += insideRight.mod
        shiftOutsideLeft += outsideLeft.mod
        shiftOutsideRight += outsideRight.mod
    }

    var result = defaultAncestor
    if (nextRight(insideLeft) != null && nextRight(outsideRight) == null) {
        outsideRight.thread = nextRight(insideLeft)
        outsideRight.mod += shiftInsideLeft - shiftOutsideRight
    } else {
        if (nextLeft(insideRight) != null && nextLeft(outsideLeft) == null) {
            outsideLeft.thread = nextLeft(insideRight)
            outsideLeft.mod += shiftInsideRight - shiftOutsideLeft
        }
        result = node
    }
    return result
}

private fun moveSubtree(from: Tidy, to: Tidy, shift: Float) {
    val subtrees = (to.number - from.number).toFloat()
    to.change -= shift / subtrees
    to.shift += shift
    from.change += shift / subtrees
    to.prelim += shift
    to.mod += shift
}

private fun executeShifts(node: Tidy) {
    var shift = 0f
    var change = 0f
    for (child in node.children.asReversed()) {
        child.prelim += shift
        child.mod += shift
        change += child.change
        shift += child.shift + change
    }
}

private fun secondWalk(node: Tidy, modSum: Float, centers: MutableMap<String, Float>) {
    centers[node.id] = node.prelim + modSum
    for (child in node.children) secondWalk(child, modSum + node.mod, centers)
}
