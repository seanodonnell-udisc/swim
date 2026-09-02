package swim.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

// Ported from @xyflow/system smoothstep-edge.ts, MIT license.

/** Which side of a card an edge leaves from, or arrives at. */
internal enum class EdgePosition { LEFT, RIGHT, TOP, BOTTOM }

internal fun EdgePosition.unit(): Offset = when (this) {
    EdgePosition.LEFT -> Offset(-1f, 0f)
    EdgePosition.RIGHT -> Offset(1f, 0f)
    EdgePosition.TOP -> Offset(0f, -1f)
    EdgePosition.BOTTOM -> Offset(0f, 1f)
}

/** The x or the y component, whichever the primary direction runs along. */
private fun Offset.on(horizontal: Boolean): Float = if (horizontal) x else y

private fun axis(horizontal: Boolean, value: Float): Offset =
    if (horizontal) Offset(value, 0f) else Offset(0f, value)

private fun distance(a: Offset, b: Offset): Float =
    sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))

private fun direction(source: Offset, sourcePosition: EdgePosition, target: Offset): Offset =
    if (sourcePosition == EdgePosition.LEFT || sourcePosition == EdgePosition.RIGHT) {
        if (source.x < target.x) Offset(1f, 0f) else Offset(-1f, 0f)
    } else {
        if (source.y < target.y) Offset(0f, 1f) else Offset(0f, -1f)
    }

/**
 * The corner points of one orthogonal route, source first and target last. This imitates
 * orthogonal edge routing. It is not real routing, but it is fast and it is what the legacy
 * renderer drew.
 */
internal fun smoothStepPoints(
    source: Offset,
    sourcePosition: EdgePosition,
    target: Offset,
    targetPosition: EdgePosition,
    centerX: Float? = null,
    centerY: Float? = null,
    offset: Float = 20f,
    stepPosition: Float = 0.5f,
): List<Offset> {
    val sourceDir = sourcePosition.unit()
    val targetDir = targetPosition.unit()
    val sourceGapped = source + sourceDir * offset
    val targetGapped = target + targetDir * offset
    val dir = direction(sourceGapped, sourcePosition, targetGapped)
    val horizontal = dir.x != 0f
    val currDir = dir.on(horizontal)

    var points: List<Offset>
    var sourceGapOffset = Offset.Zero
    var targetGapOffset = Offset.Zero

    if (sourceDir.on(horizontal) * targetDir.on(horizontal) == -1f) {
        // Opposite handle positions: the default case, split half way.
        val splitX = centerX ?: if (horizontal) {
            sourceGapped.x + (targetGapped.x - sourceGapped.x) * stepPosition
        } else {
            (sourceGapped.x + targetGapped.x) / 2f
        }
        val splitY = centerY ?: if (horizontal) {
            (sourceGapped.y + targetGapped.y) / 2f
        } else {
            sourceGapped.y + (targetGapped.y - sourceGapped.y) * stepPosition
        }
        val verticalSplit = listOf(
            Offset(splitX, sourceGapped.y),
            Offset(splitX, targetGapped.y),
        )
        val horizontalSplit = listOf(
            Offset(sourceGapped.x, splitY),
            Offset(targetGapped.x, splitY),
        )
        points = if (sourceDir.on(horizontal) == currDir) {
            if (horizontal) verticalSplit else horizontalSplit
        } else {
            if (horizontal) horizontalSplit else verticalSplit
        }
    } else {
        // sourceTarget takes x from the source and y from the target. targetSource is the other way.
        val sourceTarget = listOf(Offset(sourceGapped.x, targetGapped.y))
        val targetSource = listOf(Offset(targetGapped.x, sourceGapped.y))
        points = if (horizontal) {
            if (sourceDir.x == currDir) targetSource else sourceTarget
        } else {
            if (sourceDir.y == currDir) sourceTarget else targetSource
        }

        if (sourcePosition == targetPosition) {
            // Two handles on the same side that are closer together than the gap would make the
            // added point overlap the gapped end. Push the ends apart instead.
            val diff = abs(source.on(horizontal) - target.on(horizontal))
            if (diff <= offset) {
                val gapOffset = min(offset - 1f, offset - diff)
                if (sourceDir.on(horizontal) == currDir) {
                    val sign = if (sourceGapped.on(horizontal) > source.on(horizontal)) -1f else 1f
                    sourceGapOffset = axis(horizontal, sign * gapOffset)
                } else {
                    val sign = if (targetGapped.on(horizontal) > target.on(horizontal)) -1f else 1f
                    targetGapOffset = axis(horizontal, sign * gapOffset)
                }
            }
        } else {
            // Mixed handle positions, Right to Bottom for example.
            val sameDir = sourceDir.on(horizontal) == targetDir.on(!horizontal)
            val sourceGreater = sourceGapped.on(!horizontal) > targetGapped.on(!horizontal)
            val sourceLess = sourceGapped.on(!horizontal) < targetGapped.on(!horizontal)
            val flip = if (sourceDir.on(horizontal) == 1f) {
                (!sameDir && sourceGreater) || (sameDir && sourceLess)
            } else {
                (!sameDir && sourceLess) || (sameDir && sourceGreater)
            }
            if (flip) points = if (horizontal) sourceTarget else targetSource
        }
    }

    val gappedSource = sourceGapped + sourceGapOffset
    val gappedTarget = targetGapped + targetGapOffset
    return buildList {
        add(source)
        // Only add a gapped end that differs from the first or last corner. A duplicate point
        // gives the bend nothing to work with.
        if (gappedSource != points.first()) add(gappedSource)
        addAll(points)
        if (gappedTarget != points.last()) add(gappedTarget)
        add(target)
    }
}

/** The corner points as a path, with every corner rounded to at most [borderRadius]. */
internal fun smoothStepPath(points: List<Offset>, borderRadius: Float = 5f): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.lastIndex) {
        path.bend(points[i - 1], points[i], points[i + 1], borderRadius)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

private fun Path.bend(a: Offset, b: Offset, c: Offset, size: Float) {
    val bendSize = min(min(distance(a, b) / 2f, distance(b, c) / 2f), size)

    if ((a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)) {
        lineTo(b.x, b.y)
        return
    }

    if (a.y == b.y) {
        // The segment that arrives is horizontal.
        val xDir = if (a.x < c.x) -1f else 1f
        val yDir = if (a.y < c.y) 1f else -1f
        lineTo(b.x + bendSize * xDir, b.y)
        quadraticTo(b.x, b.y, b.x, b.y + bendSize * yDir)
        return
    }

    val xDir = if (a.x < c.x) 1f else -1f
    val yDir = if (a.y < c.y) -1f else 1f
    lineTo(b.x, b.y + bendSize * yDir)
    quadraticTo(b.x, b.y, b.x + bendSize * xDir, b.y)
}
