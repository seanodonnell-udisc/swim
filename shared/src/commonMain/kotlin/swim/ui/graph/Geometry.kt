package swim.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import swim.core.model.IssueEdge
import swim.core.model.RelationType
import swim.layout.Position
import kotlin.math.abs

/**
 * Identifies one relation on the canvas. Linear relation ids do not survive a change, so the
 * canvas keys an edge by its endpoints and its type.
 */
data class EdgeKey(val from: String, val to: String, val type: RelationType)

/** The canvas key for this edge. */
fun IssueEdge.key(): EdgeKey = EdgeKey(from, to, type)

/** A blocks cross-link or cycle edge, as [swim.layout.LayoutResult] reports it. */
fun blocksEdgeKey(from: String, to: String): EdgeKey = EdgeKey(from, to, RelationType.BLOCKS)

/** Where one edge starts and ends, and which side of each card it uses. */
internal data class EdgeEnds(
    val source: Offset,
    val sourcePosition: EdgePosition,
    val target: Offset,
    val targetPosition: EdgePosition,
)

internal fun nodeRect(p: Position): Rect = Rect(
    left = p.x,
    top = p.y,
    right = p.x + GraphCanvasDefaults.NodeWidth,
    bottom = p.y + GraphCanvasDefaults.NodeHeight,
)

/** Blocks edges run bottom to top. Everything else takes the closest pair of vertical sides. */
internal fun edgeEnds(type: RelationType, from: Rect, to: Rect): EdgeEnds =
    if (type == RelationType.BLOCKS) {
        EdgeEnds(
            source = Offset(from.center.x, from.bottom),
            sourcePosition = EdgePosition.BOTTOM,
            target = Offset(to.center.x, to.top),
            targetPosition = EdgePosition.TOP,
        )
    } else {
        closestSides(from, to)
    }

/** The nearest of the four left/right side pairings, by straight-line distance. */
internal fun closestSides(from: Rect, to: Rect): EdgeEnds {
    val sources = listOf(
        Offset(from.left, from.center.y) to EdgePosition.LEFT,
        Offset(from.right, from.center.y) to EdgePosition.RIGHT,
    )
    val targets = listOf(
        Offset(to.left, to.center.y) to EdgePosition.LEFT,
        Offset(to.right, to.center.y) to EdgePosition.RIGHT,
    )
    var best = EdgeEnds(sources[0].first, sources[0].second, targets[0].first, targets[0].second)
    var bestDistance = Float.MAX_VALUE
    for ((sourcePoint, sourcePosition) in sources) {
        for ((targetPoint, targetPosition) in targets) {
            val distance = (sourcePoint - targetPoint).getDistance()
            if (distance < bestDistance) {
                bestDistance = distance
                best = EdgeEnds(sourcePoint, sourcePosition, targetPoint, targetPosition)
            }
        }
    }
    return best
}

/** The corners of one edge, which the draw and the hit test both read. */
internal fun edgePoints(type: RelationType, from: Rect, to: Rect): List<Offset> {
    val ends = edgeEnds(type, from, to)
    return smoothStepPoints(
        source = ends.source,
        sourcePosition = ends.sourcePosition,
        target = ends.target,
        targetPosition = ends.targetPosition,
    )
}

/**
 * The corners of one edge: the route the layout found for it when it has one, and the plain pair
 * of anchors when it does not. A routed edge keeps the ends the router chose — it may leave the
 * top of its card and meet the bottom of the other — so the anchors are never re-derived here.
 */
internal fun edgePoints(
    type: RelationType,
    from: Rect,
    to: Rect,
    route: List<Position>?,
): List<Offset> =
    if (route == null || route.size < 2) edgePoints(type, from, to)
    else route.map { Offset(it.x, it.y) }

/**
 * Which side of the target card an edge lands on, read off the last stretch it travels. The
 * arrowhead points into the card from there.
 */
internal fun arrivalSide(points: List<Offset>): EdgePosition {
    val last = points.last()
    val before = points[points.lastIndex - 1]
    return if (abs(last.x - before.x) >= abs(last.y - before.y)) {
        if (last.x >= before.x) EdgePosition.LEFT else EdgePosition.RIGHT
    } else {
        if (last.y >= before.y) EdgePosition.TOP else EdgePosition.BOTTOM
    }
}

/**
 * The rectangle the minimap draws for the viewport: the content extent when the viewport already
 * holds everything, otherwise the viewport clipped to the minimap with [inset] to spare.
 */
internal fun minimapViewRect(view: Rect, content: Rect, width: Float, height: Float, inset: Float): Rect {
    val holdsAll = view.left <= content.left && view.top <= content.top &&
        view.right >= content.right && view.bottom >= content.bottom
    val target = if (holdsAll) content else view
    return Rect(
        left = target.left.coerceIn(inset, width - inset),
        top = target.top.coerceIn(inset, height - inset),
        right = target.right.coerceIn(inset, width - inset),
        bottom = target.bottom.coerceIn(inset, height - inset),
    )
}

/** The shortest distance from [point] to the polyline through [points]. */
internal fun distanceToPolyline(points: List<Offset>, point: Offset): Float {
    var best = Float.MAX_VALUE
    for (i in 0 until points.lastIndex) {
        val distance = distanceToSegment(points[i], points[i + 1], point)
        if (distance < best) best = distance
    }
    return best
}

private fun distanceToSegment(a: Offset, b: Offset, point: Offset): Float {
    val span = b - a
    val lengthSquared = span.x * span.x + span.y * span.y
    if (lengthSquared == 0f) return (point - a).getDistance()
    val t = (((point - a).x * span.x + (point - a).y * span.y) / lengthSquared).coerceIn(0f, 1f)
    return (point - (a + span * t)).getDistance()
}

/** The arrowhead triangle that lands on [point], pointing into the card. */
internal fun arrowHead(point: Offset, position: EdgePosition, size: Float): Path {
    val dir = position.unit()
    val base = point + dir * size
    val side = Offset(-dir.y, dir.x) * (size * 0.5f)
    return Path().apply {
        moveTo(point.x, point.y)
        lineTo(base.x + side.x, base.y + side.y)
        lineTo(base.x - side.x, base.y - side.y)
        close()
    }
}

/** The box every card occupies, or null when there is nothing to bound. */
internal fun contentBoundsOf(rects: Collection<Rect>): Rect? {
    if (rects.isEmpty()) return null
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (rect in rects) {
        if (rect.left < left) left = rect.left
        if (rect.top < top) top = rect.top
        if (rect.right > right) right = rect.right
        if (rect.bottom > bottom) bottom = rect.bottom
    }
    return Rect(left, top, right, bottom)
}
