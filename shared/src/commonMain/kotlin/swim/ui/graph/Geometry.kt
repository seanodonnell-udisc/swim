package swim.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import swim.core.model.IssueEdge
import swim.core.model.RelationType
import swim.layout.Position
import kotlin.math.abs
import kotlin.math.max

/**
 * Identifies one relation on the canvas. Linear relation ids do not survive a change, so the
 * canvas keys an edge by its endpoints and its type.
 */
data class EdgeKey(val from: String, val to: String, val type: RelationType)

/** The canvas key for this edge. */
fun IssueEdge.key(): EdgeKey = EdgeKey(from, to, type)

/** A blocks cross-link or cycle edge, as [swim.layout.LayoutResult] reports it. */
fun blocksEdgeKey(from: String, to: String): EdgeKey = EdgeKey(from, to, RelationType.BLOCKS)

/** Where an edge meets a card, and which way it leaves. */
internal data class Anchor(val point: Offset, val dir: Offset)

private val DOWN = Offset(0f, 1f)
private val UP = Offset(0f, -1f)
private val LEFT = Offset(-1f, 0f)
private val RIGHT = Offset(1f, 0f)

internal fun nodeRect(p: Position): Rect = Rect(
    left = p.x,
    top = p.y,
    right = p.x + GraphCanvasDefaults.NodeWidth,
    bottom = p.y + GraphCanvasDefaults.NodeHeight,
)

/** Blocks edges run bottom to top. Everything else takes the closest pair of vertical sides. */
internal fun anchorsFor(type: RelationType, from: Rect, to: Rect): Pair<Anchor, Anchor> =
    if (type == RelationType.BLOCKS) {
        Anchor(Offset(from.center.x, from.bottom), DOWN) to Anchor(Offset(to.center.x, to.top), UP)
    } else {
        closestSides(from, to)
    }

/** The nearest of the four left/right side pairings, by straight-line distance. */
internal fun closestSides(from: Rect, to: Rect): Pair<Anchor, Anchor> {
    val sources = listOf(
        Anchor(Offset(from.left, from.center.y), LEFT),
        Anchor(Offset(from.right, from.center.y), RIGHT),
    )
    val targets = listOf(
        Anchor(Offset(to.left, to.center.y), LEFT),
        Anchor(Offset(to.right, to.center.y), RIGHT),
    )
    var best = sources[0] to targets[0]
    var bestDistance = Float.MAX_VALUE
    for (source in sources) {
        for (target in targets) {
            val distance = (source.point - target.point).getDistance()
            if (distance < bestDistance) {
                bestDistance = distance
                best = source to target
            }
        }
    }
    return best
}

private fun handleLength(a: Anchor, b: Anchor): Float {
    val span = if (a.dir == DOWN || a.dir == UP) {
        abs(b.point.y - a.point.y)
    } else {
        abs(b.point.x - a.point.x)
    }
    return max(36f, span / 2f)
}

/** The cubic the edge draws along. */
internal fun edgeCurve(a: Anchor, b: Anchor): Path {
    val length = handleLength(a, b)
    val c1 = a.point + a.dir * length
    val c2 = b.point + b.dir * length
    return Path().apply {
        moveTo(a.point.x, a.point.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, b.point.x, b.point.y)
    }
}

/** The same cubic as points, for hit testing. */
internal fun edgeSamples(a: Anchor, b: Anchor, steps: Int = 24): List<Offset> {
    val length = handleLength(a, b)
    val c1 = a.point + a.dir * length
    val c2 = b.point + b.dir * length
    return (0..steps).map { step ->
        val t = step.toFloat() / steps
        val u = 1f - t
        a.point * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + b.point * (t * t * t)
    }
}

/** The shortest distance from [point] to the polyline through [samples]. */
internal fun distanceToPolyline(samples: List<Offset>, point: Offset): Float {
    var best = Float.MAX_VALUE
    for (i in 0 until samples.lastIndex) {
        val distance = distanceToSegment(samples[i], samples[i + 1], point)
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

/** The arrowhead triangle that lands on [b], pointing into the card. */
internal fun arrowHead(b: Anchor, size: Float): Path {
    val base = b.point + b.dir * size
    val side = Offset(-b.dir.y, b.dir.x) * (size * 0.5f)
    return Path().apply {
        moveTo(b.point.x, b.point.y)
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
