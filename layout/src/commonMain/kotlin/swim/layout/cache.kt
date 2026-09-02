package swim.layout

import kotlin.math.abs

/** Every saved layout, keyed by the query that produced it, then by node id. */
data class PositionSnapshot(val byKey: Map<String, Map<String, Position>> = emptyMap())

/** Where the nodes go, the snapshot to persist, and whether another query's layout was reused. */
data class PlacementResult(
    val positions: Map<String, Position>,
    val updatedSnapshot: PositionSnapshot,
    val inherited: Boolean,
)

/** Card width the collision box is built from. */
const val CACHE_NODE_WIDTH: Float = 280f

/** Rendered cards run taller and wider than the layout box, so collisions use a padded size. */
const val CACHE_COLLISION_WIDTH: Float = CACHE_NODE_WIDTH + 20f

/** Padded collision height. */
const val CACHE_COLLISION_HEIGHT: Float = 140f

/** Gap between the saved cluster and the fresh nodes placed to its right. */
const val CACHE_FRESH_GAP: Float = 120f

/** Cap on the downward steps one node may take before the cascade gives up. */
const val CACHE_OVERLAP_GUARD: Int = 1000

/**
 * Places [nodes] from the saved layout when there is one, and from [freshLayout] when there is
 * not. A query with no layout of its own reuses the saved layout that shares the most nodes with
 * it; the nodes that layout does not know go to the right of it, keeping their fresh arrangement.
 * Saved positions are hand-placed and never move. Everything else moves down until nothing
 * covers anything.
 */
fun reuseAndPlace(
    cacheKey: String,
    freshLayout: LayoutResult,
    nodes: List<LayoutNode>,
    snapshot: PositionSnapshot,
    freshGap: Float = CACHE_FRESH_GAP,
    collisionWidth: Float = CACHE_COLLISION_WIDTH,
    collisionHeight: Float = CACHE_COLLISION_HEIGHT,
    guard: Int = CACHE_OVERLAP_GUARD,
): PlacementResult {
    var saved: Map<String, Position>? = snapshot.byKey[cacheKey]
    var inherited = false

    if (saved.isNullOrEmpty()) {
        val ids = nodes.mapTo(mutableSetOf()) { it.id }
        var best = 0
        for ((key, positions) in snapshot.byKey) {
            if (key == cacheKey) continue
            val shared = positions.keys.count { it in ids }
            if (shared > best) {
                best = shared
                saved = positions
                inherited = true
            }
        }
    }

    // An absent entry means the fresh layout stands as it is. An entry that is present but empty
    // still runs the overlap pass, as the original did.
    val placedBefore = saved ?: return PlacementResult(freshLayout.positions, snapshot, false)

    fun fresh(node: LayoutNode): Position = freshLayout.positions[node.id] ?: Position(0f, 0f)

    var shiftX = 0f
    var shiftY = 0f
    if (inherited) {
        val known = nodes.filter { placedBefore.containsKey(it.id) }
        val unknown = nodes.filter { !placedBefore.containsKey(it.id) }
        if (known.isNotEmpty() && unknown.isNotEmpty()) {
            val right = known.maxOf { placedBefore.getValue(it.id).x + it.width }
            val top = known.minOf { placedBefore.getValue(it.id).y }
            shiftX = right + freshGap - unknown.minOf { fresh(it).x }
            shiftY = top - unknown.minOf { fresh(it).y }
        }
    }

    val fixed = nodes.filter { placedBefore.containsKey(it.id) }.mapTo(mutableSetOf()) { it.id }
    val positioned = nodes.map { node ->
        val position = placedBefore[node.id]
            ?: fresh(node).let { Position(it.x + shiftX, it.y + shiftY) }
        node.id to position
    }

    val resolved = resolveOverlaps(positioned, fixed, collisionWidth, collisionHeight, guard)
    val updated = if (inherited) {
        PositionSnapshot(snapshot.byKey + (cacheKey to resolved))
    } else {
        snapshot
    }
    return PlacementResult(positions = resolved, updatedSnapshot = updated, inherited = inherited)
}

/** Moves auto-placed nodes down until no card covers another. Nodes in [fixed] never move. */
internal fun resolveOverlaps(
    positioned: List<Pair<String, Position>>,
    fixed: Set<String>,
    collisionWidth: Float,
    collisionHeight: Float,
    guard: Int,
): Map<String, Position> {
    val placed = positioned.filter { it.first in fixed }.mapTo(mutableListOf()) { it.second }

    // ponytail: linear scan per node, O(n^2); index by column if graphs pass a few thousand nodes
    fun collides(p: Position): Position? = placed.firstOrNull {
        abs(it.x - p.x) < collisionWidth && abs(it.y - p.y) < collisionHeight
    }

    val out = LinkedHashMap<String, Position>()
    for ((id, start) in positioned) {
        if (id in fixed) {
            out[id] = start
            continue
        }
        var position = start
        var steps = 0
        var hit = collides(position)
        while (hit != null && steps < guard) {
            position = Position(position.x, hit.y + collisionHeight)
            steps++
            hit = collides(position)
        }
        placed += position
        out[id] = position
    }
    return out
}
